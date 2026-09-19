package com.arjun.gander

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/** A file inside an archive, as one of [ArchiveProvider]'s URIs names it. */
internal class EntryRef(val archive: Uri, val name: String, val location: EntryLocation)

/**
 * Hands a file inside a .zip to Gander's viewers, straight out of the archive. Issue #30.
 *
 * Everything that shows or passes on a file already reads it through a content URI: the
 * image view, the player, the pages that fetch /doc/, Save a copy and Share. So a file
 * inside a zip gets one, and none of them needs to know where it came from.
 *
 * The URI carries all there is to know about the file: the archive's own URI, and where in
 * it the file is and how it is packed, as the index said when the list was drawn. Nothing is
 * kept here between requests, so a viewer that Android brings back after reclaiming the
 * process asks again with the same URI and gets the same file.
 *
 * Nothing is extracted. A file stored as it is, which some archivers do with photos and video
 * since those do not compress, is handed over as a window onto the archive itself, so a
 * viewer can read it anywhere and a PDF over the range threshold loads in pieces as usual. A
 * compressed one is inflated on a thread into a pipe as the viewer reads it, and never
 * reaches storage. The price of the pipe is that it runs from the start: a large compressed
 * PDF loads whole, and seeking in a long compressed video inflates it again up to the new
 * place.
 *
 * Not exported. Share passes one file's URI on with a one-off read grant, the way the
 * FileProvider does, and ViewerActivity turns these URIs away from anything but Gander's own
 * list, see [ViewerActivity.ENTRY_VIEWER]: whoever holds one can have Gander read whatever
 * archive it names.
 */
class ArchiveProvider : ContentProvider() {

    companion object {
        private const val ARCHIVE = "archive"

        /** One file's worth of pipe, inflated and written. */
        private const val BUFFER = 64 * 1024

        fun authority(context: Context) = "${context.packageName}.archive"

        internal fun uriFor(context: Context, archive: Uri, entry: ArchiveEntry): Uri {
            val at = entry.location
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority(context))
                .appendPath(at.headerOffset.toString())
                .appendPath(at.method.toString())
                .appendPath(at.compressedSize.toString())
                .appendPath(at.size.toString())
                .appendPath(java.lang.Long.toHexString(at.crc))
                // Last, and the file's own name, for anything that names a file after the end
                // of its URI rather than asking, as some share targets do
                .appendPath(entry.name)
                .appendQueryParameter(ARCHIVE, archive.toString())
                .build()
        }

        /** What [uri] names, or null if it is not one of these. */
        internal fun parse(uri: Uri): EntryRef? {
            val parts = uri.pathSegments
            if (parts.size != 6) return null
            val offset = parts[0].toLongOrNull() ?: return null
            val method = parts[1].toIntOrNull() ?: return null
            val compressed = parts[2].toLongOrNull() ?: return null
            val size = parts[3].toLongOrNull() ?: return null
            val crc = parts[4].toLongOrNull(16) ?: return null
            val name = parts[5]
            val archive = uri.getQueryParameter(ARCHIVE)?.let(Uri::parse) ?: return null
            if (offset < 0 || compressed < 0 || size < 0 || name.isEmpty()) return null
            return EntryRef(archive, name, EntryLocation(offset, method, compressed, size, crc))
        }
    }

    /**
     * Where the compressed files are inflated. One thread per file being read, which is
     * usually one; each ends when its reader closes the pipe or reaches the end.
     */
    private val writers = Executors.newCachedThreadPool()

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ref = parse(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> ref.name
                OpenableColumns.SIZE -> ref.location.size
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String? =
        parse(uri)?.let { documentMime(it.name.substringAfterLast('.', "").lowercase()) }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val ref = parse(uri) ?: throw FileNotFoundException("not a file in an archive")
        val resolver = requireNotNull(context).contentResolver
        val archive = resolver.openAssetFileDescriptor(ref.archive, "r")
            ?: throw FileNotFoundException("the archive could not be opened")
        var handedOver = false
        try {
            val source = ZipSource.open(archive)
                ?: throw FileNotFoundException("the archive cannot be read in place")
            val at = ref.location
            val start = ZipReader.dataStart(source, at)

            // A stored file is already the bytes a viewer wants, in one run: hand over that
            // run of the archive. Only below 2 GB, because before Android 14 the stream made
            // from a window keeps its length in an int.
            if (at.method == ZipReader.METHOD_STORED &&
                at.compressedSize == at.size && at.size <= Int.MAX_VALUE
            ) {
                handedOver = true
                return AssetFileDescriptor(
                    archive.parcelFileDescriptor, archive.startOffset + start, at.size
                )
            }

            val (read, write) = ParcelFileDescriptor.createReliablePipe()
            handedOver = true
            writers.execute { pump(source, at, write) }
            return AssetFileDescriptor(read, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } catch (e: IOException) {
            throw e as? FileNotFoundException ?: FileNotFoundException(e.message)
        } finally {
            if (!handedOver) runCatching { archive.close() }
        }
    }

    /**
     * Inflates one file into the pipe a viewer is reading. A reliable pipe, so that when the
     * archive turns out to be damaged partway, the reader is told why rather than taking the
     * short read for the end of the file. A reader that closes early is not an error worth
     * reporting to anybody: the write fails, and the thread ends.
     */
    private fun pump(source: ZipSource, at: EntryLocation, write: ParcelFileDescriptor) {
        try {
            ZipReader.open(source, at).use { input ->
                input.copyTo(FileOutputStream(write.fileDescriptor), BUFFER)
            }
            write.close()
        } catch (e: IOException) {
            runCatching { write.closeWithError(e.message ?: "the file could not be read") }
        } finally {
            runCatching { source.close() }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?
    ) = 0
}
