package com.arjun.gander

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.Locale
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * The provider that hands a file inside a zip to a viewer. Issue #30.
 *
 * The archive itself is served by FixtureProvider, standing in for the app that handed
 * Gander the zip, so everything here goes through a ContentResolver the way a viewer's
 * reads do. A compressed file is served through a pipe, which Robolectric imitates with a
 * file and cannot be trusted to time the way a pipe does; that path is read on a device, in
 * ArchiveDeviceTest, and inflating itself is ZipReaderTest's.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveProviderTest {

    private lateinit var context: Context
    private val archive = FixtureProvider.uriFor("archive.zip")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Robolectric.buildContentProvider(ArchiveProvider::class.java)
            .create(ArchiveProvider.authority(context))
    }

    private fun entry(path: String): ArchiveEntry {
        val raf = RandomAccessFile(Fixtures.file("archive.zip"), "r")
        return ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, Locale.US).single { e -> e.path == path }
        }
    }

    private fun uriOf(path: String): Uri = ArchiveProvider.uriFor(context, archive, entry(path))

    // ---------------------------------------------------------------
    // The URI
    // ---------------------------------------------------------------

    @Test
    fun aUriSaysEverythingNeededToServeItsFile() {
        val entry = entry("reports/six-pages.pdf")
        val ref = ArchiveProvider.parse(ArchiveProvider.uriFor(context, archive, entry))!!
        assertThat(ref.archive).isEqualTo(archive)
        assertThat(ref.name).isEqualTo("six-pages.pdf")
        assertThat(ref.location.headerOffset).isEqualTo(entry.location.headerOffset)
        assertThat(ref.location.method).isEqualTo(entry.location.method)
        assertThat(ref.location.compressedSize).isEqualTo(entry.location.compressedSize)
        assertThat(ref.location.size).isEqualTo(entry.location.size)
        assertThat(ref.location.crc).isEqualTo(entry.location.crc)
    }

    /** A name in any script, with spaces, and an archive URI with a query of its own. */
    @Test
    fun namesAndArchiveUrisSurviveTheRoundTrip() {
        val entry = ArchiveEntry(
            path = "季度报告/会议 记录 (final).pdf",
            isDirectory = false,
            encrypted = false,
            modified = 0,
            location = EntryLocation(12, 8, 34, 56, 0xFFFFFFFFL),
        )
        val odd = Uri.parse("content://test.fixtures/archive.zip?name=Q3%20pack.zip")
        val ref = ArchiveProvider.parse(ArchiveProvider.uriFor(context, odd, entry))!!
        assertThat(ref.name).isEqualTo("会议 记录 (final).pdf")
        assertThat(ref.archive).isEqualTo(odd)
        assertThat(ref.location.crc).isEqualTo(0xFFFFFFFFL)
    }

    @Test
    fun anythingElseIsNotOneOfItsUris() {
        val base = "content://${ArchiveProvider.authority(context)}"
        listOf(
            "$base/1/8/2/3/ff?archive=content%3A%2F%2Fa%2Fb",
            "$base/1/8/2/3/ff/x.pdf",
            "$base/one/8/2/3/ff/x.pdf?archive=content%3A%2F%2Fa%2Fb",
            "$base/-1/8/2/3/ff/x.pdf?archive=content%3A%2F%2Fa%2Fb",
            "$base/1/8/2/3/not-hex/x.pdf?archive=content%3A%2F%2Fa%2Fb",
        ).forEach { assertThat(ArchiveProvider.parse(Uri.parse(it))).isNull() }
    }

    // ---------------------------------------------------------------
    // What the viewer asks before it reads
    // ---------------------------------------------------------------

    @Test
    fun itGivesTheFilesOwnNameAndSize() {
        context.contentResolver.query(uriOf("reports/six-pages.pdf"), null, null, null, null)!!
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                    .isEqualTo("six-pages.pdf")
                assertThat(c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
                    .isEqualTo(Fixtures.file("six-pages.pdf").length())
            }
    }

    /** A column it does not keep is there and empty, rather than missing and a crash. */
    @Test
    fun aColumnItDoesNotKeepComesBackEmpty() {
        context.contentResolver.query(uriOf("plain.txt"), arrayOf("_data"), null, null, null)!!
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(0)).isTrue()
            }
    }

    @Test
    fun theTypeComesFromTheName() {
        assertThat(context.contentResolver.getType(uriOf("reports/six-pages.pdf")))
            .isEqualTo("application/pdf")
        assertThat(context.contentResolver.getType(uriOf("private/table.dat")))
            .isEqualTo("application/octet-stream")
    }

    // ---------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------

    /**
     * A stored file is a run of the archive's own bytes, so it is handed over as a window
     * onto them: anything that seeks can, and nothing is copied.
     */
    @Test
    fun aStoredFileIsAWindowOntoTheArchive() {
        val uri = uriOf("photos/tiny.png")
        context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { afd ->
            assertThat(afd.startOffset).isGreaterThan(0L)
            assertThat(afd.declaredLength).isEqualTo(Fixtures.file("tiny.png").length())
        }
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertThat(bytes).isEqualTo(Fixtures.bytes("tiny.png"))
    }

    /** A file in the archive that has since changed is not served from where it used to be. */
    @Test
    fun aFileThatHasMovedIsNotFound() {
        val real = entry("photos/tiny.png")
        val stale = ArchiveEntry(
            real.path, false, false, 0,
            EntryLocation(real.location.headerOffset + 7, 0, real.size, real.size, real.location.crc),
        )
        val uri = ArchiveProvider.uriFor(context, archive, stale)
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        }
    }

    @Test
    fun itWillNotWrite() {
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openAssetFileDescriptor(uriOf("plain.txt"), "w")
        }
    }

    @Test
    fun aMissingArchiveIsNotFound() {
        val gone = ArchiveProvider.uriFor(context, FixtureProvider.uriFor(FixtureProvider.BROKEN), entry("plain.txt"))
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openAssetFileDescriptor(gone, "r")
        }
    }
}
