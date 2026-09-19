package com.arjun.gander

import android.content.res.AssetFileDescriptor
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Calendar
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/**
 * Reading a .zip where it is: what is in it, and any one file out of it. Issue #30.
 *
 * A zip keeps an index of everything in it at its end, the central directory, and listing
 * an archive reads only that, so a two gigabyte archive lists as quickly as a small one.
 * A file is read by going to where the index says it starts and inflating that one file
 * on its way to whoever asked. Nothing is written anywhere.
 *
 * java.util.zip does most of this already and cannot be used for it, for reasons measured
 * on API 36. ZipFile needs a path, which a file from the picker or another app never has.
 * ZipInputStream has no index, so listing a large archive means inflating all of it. Both
 * turn every name not written as UTF-8 into U+FFFD, which is what ZipNames is for. And
 * ZipFile refuses a whole archive if any one entry in it is encrypted. What is used from it
 * is Inflater, which is sound, and is the platform's own: no library, nothing added to the
 * download. What Inflater cannot read, Deflate64, is Gander's own too, in Deflate64.kt.
 */

/** Where one file's bytes are and how they are packed, as the index describes them. */
internal class EntryLocation(
    /** Where its local header starts, counted from the start of the zip. */
    val headerOffset: Long,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val crc: Long,
)

/** One file or folder in an archive. */
internal class ArchiveEntry(
    /** Where it sits: decoded, separated by "/", with nothing that climbs out or starts at a root. */
    val path: String,
    val isDirectory: Boolean,
    val encrypted: Boolean,
    /** Last modified, in milliseconds, or 0 when the archive does not say. */
    val modified: Long,
    val location: EntryLocation,
) {
    val name: String get() = path.substringAfterLast('/')
    val size: Long get() = location.size

    /**
     * Whether a viewer can be given it. Stored and deflated are what nearly every zip uses, and
     * Deflate64 is what Windows uses for a file over 2 GB. Anything else here is bzip2, LZMA,
     * zstd, AES or rarer, and is listed and refused.
     */
    val readable: Boolean
        get() = !encrypted && location.method in ZipReader.READABLE_METHODS
}

/**
 * A zip that can be read at any position, which is what a zip needs: its index is at the end
 * and points backwards into the rest.
 *
 * [base] is where the zip starts in what the channel reads. It is not zero when the zip is
 * itself a window onto a larger file, which is how a zip stored inside another one arrives.
 * Every read names its own position, so any number of streams can be open on one source.
 */
internal class ZipSource(
    private val channel: FileChannel,
    private val base: Long,
    val length: Long,
    private val owner: Closeable,
) : Closeable {

    /** Exactly [count] bytes starting at [position]. */
    fun read(position: Long, count: Int): ByteArray {
        if (position < 0 || count < 0 || position > length - count) {
            throw EOFException("zip ends before $position + $count")
        }
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = channel.read(ByteBuffer.wrap(out, filled, count - filled), base + position + filled)
            if (n < 0) throw EOFException("zip ends early")
            filled += n
        }
        return out
    }

    /** [count] bytes from [position], as a stream that reads them when asked. */
    fun stream(position: Long, count: Long): InputStream = object : InputStream() {
        private var at = position
        private val end = position + count

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (at >= end) return -1
            val want = minOf(len.toLong(), end - at).toInt()
            val n = channel.read(ByteBuffer.wrap(b, off, want), base + at)
            if (n < 0) throw EOFException("zip ends early")
            at += n
            return n
        }
    }

    override fun close() = owner.close()

    companion object {
        /**
         * Over whatever a provider handed back, or null when it cannot be read out of order.
         * That is a pipe: a provider streaming the file to us rather than handing over a
         * descriptor onto it, which some cloud and messaging apps do. Stat says so up front,
         * with no size.
         */
        fun open(afd: AssetFileDescriptor): ZipSource? {
            if (afd.parcelFileDescriptor.statSize < 0) return null
            // Not closed here: the descriptor belongs to afd, which the source closes
            val channel = FileInputStream(afd.fileDescriptor).channel
            val size = runCatching { channel.size() }.getOrNull() ?: return null
            val length =
                if (afd.declaredLength >= 0) afd.declaredLength else size - afd.startOffset
            return ZipSource(channel, afd.startOffset, maxOf(length, 0L), afd)
        }
    }
}

internal object ZipReader {

    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8
    const val METHOD_DEFLATE64 = 9

    val READABLE_METHODS = setOf(METHOD_STORED, METHOD_DEFLATED, METHOD_DEFLATE64)

    private const val FLAG_ENCRYPTED = 1
    private const val FLAG_DESCRIPTOR = 8

    private const val SIG_LOCAL = 0x04034b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_END = 0x06054b50L
    private const val SIG_ZIP64_END = 0x06064b50L
    private const val SIG_ZIP64_LOCATOR = 0x07064b50L

    private const val END_SIZE = 22
    private const val ZIP64_END_SIZE = 56
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val CENTRAL_SIZE = 46
    private const val LOCAL_SIZE = 30

    /**
     * An index bigger than this is refused rather than read into memory. It is a few hundred
     * thousand entries, many times what a phone could usefully list, and the bound is what
     * stops an archive that claims a gigabyte of index from being taken at its word.
     */
    private const val MAX_INDEX_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 200_000

    private const val BUFFER = 64 * 1024

    /** The archive is real and lists more than Gander will hold at once. */
    class TooLarge : ZipException("too many entries to list")

    /** Everything in the archive, in the order the index lists it. */
    fun entries(source: ZipSource, locale: Locale = Locale.getDefault()): List<ArchiveEntry> {
        val end = findEnd(source)
        val index = source.read(end.indexStart, end.indexSize.toInt())

        val raw = ArrayList<RawName>()
        val parsed = ArrayList<Parsed>()
        var p = 0
        repeat(end.count.toInt()) {
            if (p > index.size - CENTRAL_SIZE || u32(index, p) != SIG_CENTRAL) {
                throw ZipException("damaged index")
            }
            val madeBy = u16(index, p + 4)
            val flags = u16(index, p + 8)
            val method = u16(index, p + 10)
            val time = u16(index, p + 12)
            val date = u16(index, p + 14)
            val crc = u32(index, p + 16)
            var compressed = u32(index, p + 20)
            var size = u32(index, p + 24)
            val nameLength = u16(index, p + 28)
            val extraLength = u16(index, p + 30)
            val commentLength = u16(index, p + 32)
            val external = u32(index, p + 38)
            var offset = u32(index, p + 42)
            val nameAt = p + CENTRAL_SIZE
            val extraAt = nameAt + nameLength
            val next = extraAt + extraLength + commentLength
            if (next > index.size) throw ZipException("damaged index")

            var unicodePath: ByteArray? = null
            var modified = 0L
            var q = extraAt
            while (q + 4 <= extraAt + extraLength) {
                val id = u16(index, q)
                val fieldSize = u16(index, q + 2)
                val data = q + 4
                if (data + fieldSize > extraAt + extraLength) break
                when (id) {
                    // ZIP64: 64 bit values for exactly the fields left at all ones, in this order
                    0x0001 -> {
                        var r = data
                        val limit = data + fieldSize
                        if (size == 0xFFFFFFFFL && r + 8 <= limit) { size = u64(index, r); r += 8 }
                        if (compressed == 0xFFFFFFFFL && r + 8 <= limit) { compressed = u64(index, r); r += 8 }
                        if (offset == 0xFFFFFFFFL && r + 8 <= limit) { offset = u64(index, r) }
                    }
                    0x7075 -> unicodePath = index.copyOfRange(data, data + fieldSize)
                    // Extended timestamp: the modification time in UTC, when its flag says so,
                    // which is better than the DOS time beside it, since that has no zone at all
                    0x5455 ->
                        if (fieldSize >= 5 && index[data].toInt() and 1 != 0) {
                            modified = u32(index, data + 1) * 1000
                        }
                }
                q = data + fieldSize
            }
            if (size < 0 || compressed < 0 || offset < 0) throw ZipException("damaged index")

            val host = madeBy ushr 8
            // FAT, HPFS, NTFS and VFAT: the hosts that separate folders with a backslash
            val dos = host == 0 || host == 6 || host == 10 || host == 14
            val nameBytes = index.copyOfRange(nameAt, extraAt)
            val lastByte = nameBytes.lastOrNull()?.toInt()
            val directory = lastByte == '/'.code ||
                (dos && lastByte == '\\'.code) ||
                (host == 3 && ((external ushr 16) and 0xF000) == 0x4000L) ||
                (dos && external and 0x10 != 0L && size == 0L)
            raw += RawName(nameBytes, flags and 0x800 != 0, unicodePath)
            parsed += Parsed(
                dos, directory, flags, method, crc, compressed, size,
                offset + end.shift, if (modified != 0L) modified else dosTime(date, time)
            )
            p = next
        }

        val names = ZipNames.decode(raw, locale)
        return parsed.indices.mapNotNull { i ->
            val e = parsed[i]
            val path = normalise(names[i], e.dos)
            if (path.isEmpty()) return@mapNotNull null
            ArchiveEntry(
                path = path,
                isDirectory = e.directory,
                encrypted = e.flags and FLAG_ENCRYPTED != 0,
                modified = e.modified,
                location = EntryLocation(e.offset, e.method, e.compressed, e.size, e.crc),
            )
        }
    }

    /** What the index says about one entry, held until its name can be decoded. */
    private class Parsed(
        val dos: Boolean,
        val directory: Boolean,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressed: Long,
        val size: Long,
        val offset: Long,
        val modified: Long,
    )

    private class End(val count: Long, val indexStart: Long, val indexSize: Long, val shift: Long)

    /**
     * The end record, and through it where the index is.
     *
     * It is the last thing in the file unless the archive has a comment, which can be up to
     * 64 KB and can contain anything, the record's own signature included, so the search is
     * backwards and a match has to leave room for exactly the comment it declares.
     */
    private fun findEnd(source: ZipSource): End {
        if (source.length < END_SIZE) throw ZipException("not a zip")
        val tailSize = minOf(source.length, (END_SIZE + 0xFFFF).toLong()).toInt()
        val tailStart = source.length - tailSize
        val tail = source.read(tailStart, tailSize)
        var at = -1
        for (i in tailSize - END_SIZE downTo 0) {
            if (u32(tail, i) == SIG_END && i + END_SIZE + u16(tail, i + 20) <= tailSize) {
                at = i
                break
            }
        }
        if (at < 0) throw ZipException("not a zip")

        val endStart = tailStart + at
        var disk = u16(tail, at + 4).toLong()
        var count = u16(tail, at + 10).toLong()
        var indexSize = u32(tail, at + 12)
        var indexOffset = u32(tail, at + 16)
        var indexEnd = endStart

        if (count == 0xFFFFL || indexSize == 0xFFFFFFFFL || indexOffset == 0xFFFFFFFFL) {
            // ZIP64. The record sits right before its locator, so look there first: where the
            // locator says it is would be wrong for a zip with something in front of it.
            val locatorStart = endStart - ZIP64_LOCATOR_SIZE
            val locator = if (locatorStart >= 0) source.read(locatorStart, ZIP64_LOCATOR_SIZE) else null
            if (locator == null || u32(locator, 0) != SIG_ZIP64_LOCATOR) {
                throw ZipException("zip64 locator missing")
            }
            val candidates = listOf(locatorStart - ZIP64_END_SIZE, u64(locator, 8))
            val recordStart = candidates.firstOrNull { it >= 0 && it <= source.length - ZIP64_END_SIZE &&
                u32(source.read(it, 4), 0) == SIG_ZIP64_END } ?: throw ZipException("zip64 record missing")
            val record = source.read(recordStart, ZIP64_END_SIZE)
            disk = u32(record, 16)
            count = u64(record, 32)
            indexSize = u64(record, 40)
            indexOffset = u64(record, 48)
            indexEnd = recordStart
        }

        if (disk != 0L && disk != 0xFFFFL) throw ZipException("split across disks")
        if (indexSize > MAX_INDEX_BYTES || count > MAX_ENTRIES) throw TooLarge()
        if (count < 0 || indexSize < 0 || indexOffset < 0 || count * CENTRAL_SIZE > indexSize) {
            throw ZipException("damaged end record")
        }
        // Offsets count from where the zip began, which is not the start of the file when
        // something sits in front of it: a self-extracting stub, or a download that was
        // prefixed. The index ends where the end record starts, so the gap is that.
        val shift = indexEnd - (indexOffset + indexSize)
        if (shift < 0) throw ZipException("index runs past its end record")
        return End(count, indexOffset + shift, indexSize, shift)
    }

    /**
     * Where [at]'s bytes start, having checked its local header is where the index says.
     *
     * The URI a viewer holds carries this location, so a file that has since been rewritten
     * would otherwise be read at the old place. The signature and method catch most of that,
     * and so does the checksum, where the header records one rather than trailing it.
     */
    fun dataStart(source: ZipSource, at: EntryLocation): Long {
        val header = source.read(at.headerOffset, LOCAL_SIZE)
        if (u32(header, 0) != SIG_LOCAL) throw ZipException("no entry at ${at.headerOffset}")
        if (u16(header, 8) != at.method) throw ZipException("entry has changed")
        val localCrc = u32(header, 14)
        if (u16(header, 6) and FLAG_DESCRIPTOR == 0 && localCrc != 0L && localCrc != at.crc) {
            throw ZipException("entry has changed")
        }
        val start = at.headerOffset + LOCAL_SIZE + u16(header, 26) + u16(header, 28)
        if (start > source.length - at.compressedSize) throw ZipException("entry runs past the end")
        return start
    }

    /** One file's contents, inflated if they need it, and held to what the index says. */
    fun open(source: ZipSource, at: EntryLocation): InputStream {
        val raw = source.stream(dataStart(source, at), at.compressedSize)
        val body = when (at.method) {
            METHOD_STORED -> raw
            METHOD_DEFLATED -> Inflating(raw)
            METHOD_DEFLATE64 -> Deflate64(raw)
            else -> throw ZipException("method ${at.method} is not supported")
        }
        return Checked(body, at.size, at.crc)
    }

    /**
     * Raw deflate, which is what a zip holds: no zlib header or trailer around it.
     *
     * In that mode zlib may want one byte beyond the end of the data before it will say the
     * stream is finished, so one zero byte is fed after the real ones run out. ZipFile does
     * the same. The inflater is ended on close, which InflaterInputStream only does for one
     * it made itself, and an inflater holds native memory until it is.
     */
    private class Inflating(input: InputStream) :
        InflaterInputStream(input, Inflater(true), BUFFER) {
        private var padded = false

        override fun fill() {
            if (padded) throw EOFException("deflate data ends early")
            len = `in`.read(buf, 0, buf.size)
            if (len == -1) {
                buf[0] = 0
                len = 1
                padded = true
            }
            inf.setInput(buf, 0, len)
        }

        override fun close() {
            super.close()
            inf.end()
        }
    }

    /**
     * An entry held to its own index entry.
     *
     * More bytes than declared is either damage or a zip bomb, one that promises a small
     * file and inflates to fill the phone, so the read stops the byte it goes over and
     * never inflates the rest. Fewer is a truncated archive, and a checksum that does not
     * match is either. All three end in an error, because a document that stops early can
     * look complete, and a viewer shown an error at least says something went wrong.
     */
    private class Checked(
        private val input: InputStream,
        private val size: Long,
        private val crc: Long,
    ) : InputStream() {
        private val sum = CRC32()
        private var count = 0L
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (ended) return -1
            if (len == 0) return 0
            // One byte past what is left, never more: that byte is how an entry larger than it
            // claims is caught, without inflating whatever else it had in store.
            val n = input.read(b, off, minOf(len.toLong(), size - count + 1).toInt())
            if (n < 0) {
                if (count != size) throw ZipException("entry is shorter than its index says")
                if (sum.value != crc) throw ZipException("entry is damaged")
                ended = true
                return -1
            }
            count += n
            if (count > size) throw ZipException("entry is larger than its index says")
            sum.update(b, off, n)
            return n
        }

        override fun close() = input.close()
    }

    /**
     * A name made safe to show and to split on. Only ever displayed, never used as a path, so
     * this is not what keeps a zip from writing outside a folder: nothing here writes at all.
     * It is what keeps "../" and a leading slash from turning into folders called ".." and "",
     * and a backslash from Windows from being read as part of a name.
     */
    internal fun normalise(name: String, dos: Boolean): String {
        // Nearly every name needs nothing done to it, and saying so takes one pass. Rebuilding
        // every name regardless was most of the time a large index took to read: 1.6 of the
        // 2.3 seconds 100,000 names took on a Nothing Phone 2.
        if (isClean(name, dos)) return name
        return (if (dos) name.replace('\\', '/') else name)
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { segment ->
                // Control characters, and the marks that reorder text around them, which could
                // make "photo\u202Egpj.apk" read as a picture's name
                if (segment.none(::unsafe)) segment
                else buildString(segment.length) {
                    segment.forEach { append(if (unsafe(it)) '\uFFFD' else it) }
                }
            }
    }

    private fun unsafe(c: Char) =
        Character.isISOControl(c) || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

    /** Whether [normalise] would hand [name] back as it is: no empty, "." or ".." part, nothing unsafe. */
    private fun isClean(name: String, dos: Boolean): Boolean {
        var segmentStart = 0
        for (i in 0..name.length) {
            val c = if (i < name.length) name[i] else '/'
            if (c == '/') {
                val length = i - segmentStart
                if (length == 0) return false
                if (name[segmentStart] == '.' && (length == 1 || (length == 2 && name[segmentStart + 1] == '.'))) {
                    return false
                }
                segmentStart = i + 1
            } else if (unsafe(c) || (dos && c == '\\')) {
                return false
            }
        }
        return true
    }

    /**
     * A DOS date and time, which is what every zip records, as the phone's local time. It
     * carries no zone, and reading it as local is what every unzip tool does. 0 for the zero
     * date some writers leave, and for one that cannot be a date.
     */
    internal fun dosTime(date: Int, time: Int): Long {
        val month = (date ushr 5) and 0xF
        val day = date and 0x1F
        if (date == 0 || month !in 1..12 || day == 0) return 0
        return Calendar.getInstance().run {
            clear()
            set(1980 + (date ushr 9), month - 1, day, time ushr 11, (time ushr 5) and 0x3F, (time and 0x1F) * 2)
            timeInMillis
        }
    }

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    /** Negative past 2^63, which no real zip reaches; the callers treat that as damage. */
    private fun u64(b: ByteArray, at: Int): Long = u32(b, at) or (u32(b, at + 4) shl 32)
}
