package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Reading a zip in place: the index, and one file out of it. Issue #30.
 *
 * The fixtures are written byte by byte by tests/fixtures/make_fixtures.py rather than by
 * a zip library, because a library writes what it considers correct, and the cases that
 * matter here are the ones real archivers write anyway: sizes that trail the data, a
 * comment after the end record, ZIP64 records, entries nothing can open.
 */
class ZipReaderTest {

    private fun source(file: File): ZipSource {
        val raf = RandomAccessFile(file, "r")
        return ZipSource(raf.channel, 0, raf.length(), raf)
    }

    private fun source(fixture: String) = source(Fixtures.file(fixture))

    /** The same archive rewritten, for the damaged and unusual cases no fixture should carry. */
    private fun source(bytes: ByteArray): ZipSource =
        source(File.createTempFile("zip-reader", ".zip").apply { writeBytes(bytes); deleteOnExit() })

    private fun entries(fixture: String) = source(fixture).use { ZipReader.entries(it, Locale.US) }

    private fun ZipSource.read(entry: ArchiveEntry): ByteArray =
        ZipReader.open(this, entry.location).use { it.readBytes() }

    private fun List<ArchiveEntry>.named(path: String) = single { it.path == path }

    // ---------------------------------------------------------------
    // The index
    // ---------------------------------------------------------------

    @Test
    fun everyEntryIsListedInTheOrderTheIndexHasIt() {
        assertThat(entries("archive.zip").map { it.path }).containsExactly(
            "reports",
            "reports/six-pages.pdf",
            "reports/notes.md",
            "photos/tiny.png",
            "photos/.hidden-thumbs",
            "__MACOSX/photos/._tiny.png",
            "plain.txt",
            "nested/stored.zip",
            "nested/packed.zip",
            "private/locked.txt",
            "private/table.dat",
        ).inOrder()
    }

    @Test
    fun aFolderEntryIsAFolder() {
        val all = entries("archive.zip")
        assertThat(all.named("reports").isDirectory).isTrue()
        assertThat(all.named("reports/six-pages.pdf").isDirectory).isFalse()
    }

    @Test
    fun sizesComeFromTheIndex() {
        val all = entries("archive.zip")
        assertThat(all.named("reports/six-pages.pdf").size)
            .isEqualTo(Fixtures.file("six-pages.pdf").length())
        // Written with its sizes after the data and zeros in its local header, so the index is
        // the only place they are before the file has been read
        assertThat(all.named("reports/notes.md").size).isEqualTo(Fixtures.file("notes.md").length())
    }

    /** Listed so they can say why they will not open, which is better than vanishing. */
    @Test
    fun encryptedFilesAndUnknownMethodsAreListedAndNotReadable() {
        val all = entries("archive.zip")
        assertThat(all.named("private/locked.txt").encrypted).isTrue()
        assertThat(all.named("private/locked.txt").readable).isFalse()
        assertThat(all.named("private/table.dat").encrypted).isFalse()
        assertThat(all.named("private/table.dat").readable).isFalse()
        assertThat(all.named("plain.txt").readable).isTrue()
        assertThat(all.named("photos/tiny.png").readable).isTrue()
    }

    /** The extended timestamp carries a zone, so it beats the DOS time beside it. */
    @Test
    fun theUtcTimestampIsPreferredWhereThereIsOne() {
        assertThat(entries("archive.zip").named("reports/six-pages.pdf").modified)
            .isEqualTo(1_767_225_600_000L)
    }

    /** Without one, the DOS time is read as the phone's own, as every unzip tool reads it. */
    @Test
    fun aDosTimeIsReadAsLocalTime() {
        val local = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis
        assertThat(entries("archive.zip").named("plain.txt").modified).isEqualTo(local)
    }

    @Test
    fun aDateThatCannotBeADateIsNoDate() {
        assertThat(ZipReader.dosTime(0, 0)).isEqualTo(0)
        // Month 13
        assertThat(ZipReader.dosTime((46 shl 9) or (13 shl 5) or 1, 0)).isEqualTo(0)
        // Day 0
        assertThat(ZipReader.dosTime((46 shl 9) or (1 shl 5), 0)).isEqualTo(0)
    }

    @Test
    fun zip64RecordsAreFollowed() {
        source("zip64.zip").use { zip ->
            val all = ZipReader.entries(zip, Locale.US)
            assertThat(all.map { it.path }).containsExactly("big/report.pdf", "big/notes.txt")
            assertThat(zip.read(all.named("big/report.pdf")))
                .isEqualTo(Fixtures.bytes("six-pages.pdf"))
        }
    }

    /**
     * The end record is found by searching backwards, and a comment can hold anything,
     * its signature included. A match has to leave room for exactly the comment it
     * declares, or the search stops inside the comment and reads garbage as an index.
     */
    @Test
    fun aCommentHoldingTheEndSignatureIsNotMistakenForTheEnd() {
        val original = Fixtures.bytes("archive.zip")
        val oldComment = "Gander test archive".toByteArray()
        val body = original.copyOf(original.size - oldComment.size)
        val comment = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(30) { 0x20 }
        // The end record's last field is the comment's length
        body[body.size - 2] = comment.size.toByte()
        body[body.size - 1] = 0
        source(body + comment).use { zip ->
            assertThat(ZipReader.entries(zip, Locale.US)).hasSize(11)
        }
    }

    /**
     * Offsets count from where the zip began, which is not the start of the file when
     * something is in front of it: a self-extracting stub, most often.
     */
    @Test
    fun somethingInFrontOfTheZipIsSkipped() {
        val prefixed = ByteArray(1000) { 0x55 } + Fixtures.bytes("archive.zip")
        source(prefixed).use { zip ->
            val all = ZipReader.entries(zip, Locale.US)
            assertThat(all).hasSize(11)
            assertThat(zip.read(all.named("photos/tiny.png"))).isEqualTo(Fixtures.bytes("tiny.png"))
        }
    }

    @Test
    fun whatIsNotAZipIsRefused() {
        source("plain.txt").use { zip ->
            assertThrows(ZipException::class.java) { ZipReader.entries(zip, Locale.US) }
        }
    }

    /**
     * Not archive.zip: that one has a zip stored whole inside it, and with the outer end
     * record cut away the inner one is the last in the file, so it is what gets listed. The
     * format gives nothing else to go on.
     */
    @Test
    fun aZipCutShortIsRefused() {
        val whole = Fixtures.bytes("odd-names.zip")
        source(whole.copyOf(whole.size - 10)).use { zip ->
            assertThrows(ZipException::class.java) { ZipReader.entries(zip, Locale.US) }
        }
    }

    // ---------------------------------------------------------------
    // One file out of it
    // ---------------------------------------------------------------

    @Test
    fun aCompressedFileReadsBackAsItWent() {
        source("archive.zip").use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("reports/six-pages.pdf")
            assertThat(entry.location.method).isEqualTo(ZipReader.METHOD_DEFLATED)
            assertThat(zip.read(entry)).isEqualTo(Fixtures.bytes("six-pages.pdf"))
        }
    }

    @Test
    fun aStoredFileReadsBackAsItWent() {
        source("archive.zip").use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("photos/tiny.png")
            assertThat(entry.location.method).isEqualTo(ZipReader.METHOD_STORED)
            assertThat(zip.read(entry)).isEqualTo(Fixtures.bytes("tiny.png"))
        }
    }

    @Test
    fun aFileWhoseSizesTrailItsDataReadsBack() {
        source("archive.zip").use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("reports/notes.md")
            assertThat(zip.read(entry)).isEqualTo(Fixtures.bytes("notes.md"))
        }
    }

    /** Any number of files can be read at once from one archive, which a viewer does. */
    @Test
    fun twoFilesCanBeReadAtOnce() {
        source("archive.zip").use { zip ->
            val all = ZipReader.entries(zip, Locale.US)
            val pdf = ZipReader.open(zip, all.named("reports/six-pages.pdf").location)
            val png = ZipReader.open(zip, all.named("photos/tiny.png").location)
            val a = pdf.read()
            val b = png.read()
            assertThat(pdf.readBytes()).isEqualTo(Fixtures.bytes("six-pages.pdf").drop(1).toByteArray())
            assertThat(png.readBytes()).isEqualTo(Fixtures.bytes("tiny.png").drop(1).toByteArray())
            assertThat(a).isEqualTo(Fixtures.bytes("six-pages.pdf")[0].toInt() and 0xFF)
            assertThat(b).isEqualTo(Fixtures.bytes("tiny.png")[0].toInt() and 0xFF)
        }
    }

    /** Where the index entry for [path] starts, in the archive's own bytes. */
    private fun indexEntry(bytes: ByteArray, path: String): Int {
        val name = path.toByteArray()
        return (0 until bytes.size - 46).first { at ->
            bytes[at] == 0x50.toByte() && bytes[at + 1] == 0x4B.toByte() &&
                bytes[at + 2] == 0x01.toByte() && bytes[at + 3] == 0x02.toByte() &&
                bytes.copyOfRange(at + 46, at + 46 + name.size).contentEquals(name)
        }
    }

    private fun ByteArray.putInt(at: Int, value: Long) {
        for (i in 0 until 4) this[at + i] = (value ushr (8 * i)).toByte()
    }

    /**
     * A zip bomb promises a small file and inflates to fill the phone. The index is held to
     * its word: the read stops at the first byte past what it declared.
     */
    @Test
    fun aFileLargerThanItsIndexSaysIsStopped() {
        val bytes = Fixtures.bytes("archive.zip")
        bytes.putInt(indexEntry(bytes, "plain.txt") + 24, 10)
        source(bytes).use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("plain.txt")
            assertThat(entry.size).isEqualTo(10)
            val e = assertThrows(ZipException::class.java) { zip.read(entry) }
            assertThat(e).hasMessageThat().contains("larger")
        }
    }

    @Test
    fun aFileShorterThanItsIndexSaysIsAnError() {
        val bytes = Fixtures.bytes("archive.zip")
        bytes.putInt(indexEntry(bytes, "plain.txt") + 24, 500)
        source(bytes).use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("plain.txt")
            val e = assertThrows(ZipException::class.java) { zip.read(entry) }
            assertThat(e).hasMessageThat().contains("shorter")
        }
    }

    /** A damaged byte in a stored file gets past everything but the checksum. */
    @Test
    fun aDamagedFileFailsItsChecksum() {
        val bytes = Fixtures.bytes("archive.zip")
        val png = Fixtures.bytes("tiny.png")
        // The PNG is stored, so its bytes appear as they are; damage one in the middle
        val at = (0 until bytes.size - png.size).first { i ->
            bytes.copyOfRange(i, i + png.size).contentEquals(png)
        } + png.size / 2
        bytes[at] = (bytes[at].toInt() xor 0xFF).toByte()
        source(bytes).use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("photos/tiny.png")
            val e = assertThrows(ZipException::class.java) { zip.read(entry) }
            assertThat(e).hasMessageThat().contains("damaged")
        }
    }

    /**
     * A viewer holds a file's location in its URI, and the archive can change under it. The
     * local header has to be at the offset, with the method and checksum the URI expects.
     */
    @Test
    fun aLocationThatNoLongerMatchesIsRefused() {
        source("archive.zip").use { zip ->
            val real = ZipReader.entries(zip, Locale.US).named("plain.txt").location
            val moved = EntryLocation(real.headerOffset + 1, real.method, real.compressedSize, real.size, real.crc)
            val otherMethod = EntryLocation(real.headerOffset, 0, real.compressedSize, real.size, real.crc)
            val otherCrc = EntryLocation(real.headerOffset, real.method, real.compressedSize, real.size, real.crc xor 1)
            listOf(moved, otherMethod, otherCrc).forEach { at ->
                assertThrows(ZipException::class.java) { ZipReader.dataStart(zip, at) }
            }
            assertThat(ZipReader.dataStart(zip, real)).isGreaterThan(real.headerOffset)
        }
    }

    @Test
    fun aLocationPastTheEndIsRefused() {
        source("archive.zip").use { zip ->
            val real = ZipReader.entries(zip, Locale.US).named("plain.txt").location
            val tooLong = EntryLocation(real.headerOffset, real.method, zip.length, real.size, real.crc)
            assertThrows(ZipException::class.java) { ZipReader.dataStart(zip, tooLong) }
        }
    }

    @Test
    fun aMethodNobodyWritesIsNotOpened() {
        source("archive.zip").use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("private/table.dat")
            assertThrows(ZipException::class.java) { ZipReader.open(zip, entry.location) }
        }
    }

    @Test
    fun anEmptyFileReadsAsEmpty() {
        source("archive.zip").use { zip ->
            // The folder entry is stored with no data at all
            val folder = ZipReader.entries(zip, Locale.US).named("reports")
            assertThat(zip.read(folder)).isEmpty()
        }
    }

    // ---------------------------------------------------------------
    // Names made safe to show
    // ---------------------------------------------------------------

    @Test
    fun namesThatClimbOrStartAtARootAreBroughtIn() {
        assertThat(entries("odd-names.zip").map { it.path }).containsExactly(
            "escaped.txt",
            "absolute/path.txt",
            // Written on Windows, where a backslash separates folders
            "docs/readme.txt",
            // Written on Unix, where it is part of a name
            "unix\\name.txt",
            "a/b/c.txt",
            "dup.txt",
            "dup.txt",
            // The mark that would have turned the rest of the name round is shown for what it is
            "photo\uFFFDgpj.apk",
        ).inOrder()
    }

    @Test
    fun normaliseDropsEmptyDotAndDotDotSegments() {
        assertThat(ZipReader.normalise("/a/./b//../c/", dos = false)).isEqualTo("a/b/c")
        assertThat(ZipReader.normalise("..\\..\\win\\x", dos = true)).isEqualTo("win/x")
        assertThat(ZipReader.normalise("..\\..\\win\\x", dos = false)).isEqualTo("..\\..\\win\\x")
        assertThat(ZipReader.normalise("../..", dos = false)).isEmpty()
        assertThat(ZipReader.normalise("tab\there", dos = false)).isEqualTo("tab\uFFFDhere")
    }
}
