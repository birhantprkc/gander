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

    private fun ZipSource.read(entry: ArchiveEntry, password: String? = null): ByteArray =
        ZipReader.open(this, entry.location, password).use { it.readBytes() }

    private fun List<ArchiveEntry>.named(path: String) = single { it.path == path }

    /** Where [path]'s data begins in the archive [bytes]. */
    private fun dataOf(bytes: ByteArray, path: String): Int = source(bytes).use { zip ->
        ZipReader.dataStart(zip, ZipReader.entries(zip, Locale.US).named(path).location).toInt()
    }

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

    /**
     * An unknown method is listed so it can say why it will not open, which is better than
     * vanishing. A password-protected file is readable, given its password.
     */
    @Test
    fun encryptedFilesAreReadableAndUnknownMethodsAreNot() {
        val all = entries("archive.zip")
        assertThat(all.named("private/locked.txt").encrypted).isTrue()
        assertThat(all.named("private/locked.txt").readable).isTrue()
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

    /** Where the end record starts, found the same way the reader finds it. */
    private fun endRecord(bytes: ByteArray): Int =
        (bytes.size - 22 downTo 0).first { at ->
            bytes[at] == 0x50.toByte() && bytes[at + 1] == 0x4B.toByte() &&
                bytes[at + 2] == 0x05.toByte() && bytes[at + 3] == 0x06.toByte()
        }

    /**
     * An archive that claims a larger index than a phone should hold is refused before anything
     * is read into memory, rather than taken at its word.
     */
    @Test
    fun anIndexTooLargeToHoldIsRefusedUnread() {
        val bytes = Fixtures.bytes("odd-names.zip")
        bytes.putInt(endRecord(bytes) + 12, 40L * 1024 * 1024)
        source(bytes).use { zip ->
            assertThrows(ZipReader.TooLarge::class.java) { ZipReader.entries(zip, Locale.US) }
        }
    }

    /** And so is one that claims more entries than that, which only ZIP64 can express. */
    @Test
    fun tooManyEntriesIsRefusedUnread() {
        val bytes = Fixtures.bytes("zip64.zip")
        // The ZIP64 end record sits 56 bytes before its 20 byte locator, which sits before the
        // end record; its two entry counts are at 24 and 32
        val record = endRecord(bytes) - 20 - 56
        for (field in listOf(24, 32)) {
            bytes.putInt(record + field, 250_000L)
            bytes.putInt(record + field + 4, 0L)
        }
        source(bytes).use { zip ->
            assertThrows(ZipReader.TooLarge::class.java) { ZipReader.entries(zip, Locale.US) }
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
    // Under a password
    // ---------------------------------------------------------------

    /** What each file in locked.zip is, going by the generator, and what it holds. */
    private val lockedFiles = mapOf(
        "open.txt" to "plain.txt",
        "zipcrypto.txt" to "plain.txt",
        "zipcrypto-trailing.md" to "notes.md",
        "zipcrypto.png" to "tiny.png",
        "aes128.txt" to "plain.txt",
        "aes192.pdf" to "six-pages.pdf",
        "aes256.png" to "tiny.png",
        "aes256-deflate64.md" to "notes.md",
    )

    @Test
    fun eachFileSaysHowItIsLocked() {
        val all = entries("locked.zip").associateBy { it.path }
        assertThat(all.mapValues { it.value.location.lock }).containsExactly(
            "open.txt", Lock.NONE,
            "zipcrypto.txt", Lock.ZIPCRYPTO,
            "zipcrypto-trailing.md", Lock.ZIPCRYPTO,
            "zipcrypto.png", Lock.ZIPCRYPTO,
            "aes128.txt", Lock.AES128,
            "aes192.pdf", Lock.AES192,
            "aes256.png", Lock.AES256,
            "aes256-deflate64.md", Lock.AES256,
            "strong.bin", Lock.UNSUPPORTED,
        )
    }

    /** AES puts 99 in the method field and the real method in a field of its own. */
    @Test
    fun anAesFileHasTheMethodInsideTheEncryption() {
        val all = entries("locked.zip")
        assertThat(all.named("aes128.txt").location.method).isEqualTo(ZipReader.METHOD_DEFLATED)
        assertThat(all.named("aes256.png").location.method).isEqualTo(ZipReader.METHOD_STORED)
        assertThat(all.named("aes256-deflate64.md").location.method).isEqualTo(ZipReader.METHOD_DEFLATE64)
    }

    /**
     * Every scheme and version, stored and compressed, read with the right password. The
     * fixture's encryption is the generator's own, and 7-Zip and Info-ZIP both read it back
     * as well, so this is not only Gander agreeing with itself.
     */
    @Test
    fun everyLockedFileReadsBackWithItsPassword() {
        source("locked.zip").use { zip ->
            val all = ZipReader.entries(zip, Locale.US)
            lockedFiles.forEach { (path, original) ->
                assertThat(zip.read(all.named(path), "gander")).isEqualTo(Fixtures.bytes(original))
            }
        }
    }

    @Test
    fun aWrongPasswordIsSaidToBeWrong() {
        source("locked.zip").use { zip ->
            val all = ZipReader.entries(zip, Locale.US)
            lockedFiles.keys.filter { all.named(it).encrypted }.forEach { path ->
                assertThrows(ZipReader.WrongPassword::class.java) {
                    ZipReader.open(zip, all.named(path).location, "goose")
                }
            }
        }
    }

    /**
     * The older encryption's check is one byte, so one wrong password in 256 passes it. For a
     * compressed file that is not the end of it: its first bytes decrypt to nothing an inflater
     * accepts, and that is caught before the file is handed over.
     */
    @Test
    fun aWrongPasswordThatPassesTheOneByteCheckIsStillCaught() {
        source("locked.zip").use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("zipcrypto.txt")
            val local = ZipReader.local(zip, entry.location)
            val header = zip.read(local.dataStart, ZipCrypto.HEADER_SIZE)
            val check = (entry.location.crc ushr 24).toInt() and 0xFF
            val lucky = generateSequence(0) { it + 1 }.map { "wrong$it" }
                .first { ZipCrypto.keyed(it.toByteArray()).checks(header, check) }
            assertThrows(ZipReader.WrongPassword::class.java) { ZipReader.open(zip, entry.location, lucky) }
        }
    }

    @Test
    fun noPasswordAtAllIsAskedFor() {
        source("locked.zip").use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("aes256.png")
            assertThrows(ZipReader.PasswordNeeded::class.java) { ZipReader.open(zip, entry.location) }
        }
    }

    /** PKWARE's Strong Encryption, which nothing Gander reads, is listed and refused. */
    @Test
    fun strongEncryptionIsListedAndNotReadable() {
        source("locked.zip").use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("strong.bin")
            assertThat(entry.encrypted).isTrue()
            assertThat(entry.readable).isFalse()
            assertThrows(ZipException::class.java) { ZipReader.open(zip, entry.location, "gander") }
        }
    }

    /**
     * A password is not always ASCII, and the older encryption took one in the code page of
     * the machine that made the zip: here a Russian Windows machine's. AES took UTF-8.
     */
    @Test
    fun aPasswordThatIsNotAsciiOpensInWhateverCodePageItWasWrittenIn() {
        source("locked-cyrillic.zip").use { zip ->
            val all = ZipReader.entries(zip, Locale.US)
            assertThat(zip.read(all.named("zipcrypto.txt"), "пароль")).isEqualTo(Fixtures.bytes("plain.txt"))
            assertThat(zip.read(all.named("aes.txt"), "пароль")).isEqualTo(Fixtures.bytes("plain.txt"))
        }
    }

    /**
     * AE-2 leaves the checksum at nought, so a damaged byte in the encrypted data is caught
     * only by the authentication code at the end. It has to be checked, or a damaged photo
     * would open as a damaged photo.
     */
    @Test
    fun aDamagedAesFileFailsItsAuthenticationCode() {
        val bytes = Fixtures.bytes("locked.zip")
        // Past the 16 byte salt and 2 byte verifier, into the encrypted photo itself
        val at = dataOf(bytes, "aes256.png") + 18 + 40
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
        source(bytes).use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("aes256.png")
            val e = assertThrows(ZipException::class.java) { zip.read(entry, "gander") }
            assertThat(e).hasMessageThat().contains("damaged")
        }
    }

    /** And when a decompressor stops reading short of the code, the code is still read and checked. */
    @Test
    fun theCodeIsCheckedEvenWhenTheDecompressorStopsShort() {
        val bytes = Fixtures.bytes("locked.zip")
        source(bytes).use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("aes192.pdf")
            val codeStart = ZipReader.dataStart(zip, entry.location).toInt() + entry.location.compressedSize.toInt() - 10
            bytes[codeStart] = (bytes[codeStart].toInt() xor 0x01).toByte()
        }
        source(bytes).use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("aes192.pdf")
            assertThrows(ZipException::class.java) { zip.read(entry, "gander") }
        }
    }

    // ---------------------------------------------------------------
    // Deflate64
    // ---------------------------------------------------------------

    /**
     * Written by the generator's own Deflate64 encoder, which 7-Zip reads back: a stored
     * block, a fixed one and two dynamic ones, matches from past 32 KB and past 48 KB back,
     * and lengths far past 258. The checksum at the end is what says every byte came out right.
     */
    @Test
    fun deflate64ReadsBack() {
        source("deflate64.zip").use { zip ->
            val all = ZipReader.entries(zip, Locale.US)
            all.forEach { assertThat(it.location.method).isEqualTo(ZipReader.METHOD_DEFLATE64) }
            assertThat(zip.read(all.named("short.txt"))).isEqualTo(Fixtures.bytes("plain.txt"))
            assertThat(zip.read(all.named("empty.txt"))).isEmpty()
            val long = zip.read(all.named("long.txt"))
            assertThat(long.size.toLong()).isEqualTo(all.named("long.txt").size)
            assertThat(long.size).isGreaterThan(64 * 1024)
        }
    }

    /** Read a byte at a time too, since a match can be cut off by the reader and resumed. */
    @Test
    fun deflate64ReadsBackAByteAtATime() {
        source("deflate64.zip").use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("long.txt")
            val whole = zip.read(entry)
            val bytewise = ZipReader.open(zip, entry.location).use { input ->
                java.io.ByteArrayOutputStream().also { out ->
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        out.write(b)
                    }
                }.toByteArray()
            }
            assertThat(bytewise).isEqualTo(whole)
        }
    }

    @Test
    fun damagedDeflate64IsAnError() {
        val bytes = Fixtures.bytes("deflate64.zip")
        // Into the dynamic blocks, well past the stored one at the start
        val at = dataOf(bytes, "long.txt") + 16 * 1024 + 800
        bytes[at] = (bytes[at].toInt() xor 0x5A).toByte()
        source(bytes).use { zip ->
            val entry = ZipReader.entries(zip, Locale.US).named("long.txt")
            // Damage shows as a code that means nothing, data that ends early, or a checksum
            // that does not match, depending on where it lands; every one is an error
            assertThrows(java.io.IOException::class.java) { zip.read(entry) }
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
