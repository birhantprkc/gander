package com.arjun.gander

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * A document provider that renames, standing in for the phone's own storage.
 *
 * It speaks the two parts of the document provider protocol the viewer uses: a query that
 * reports a document's flags, and the call DocumentsContract.renameDocument makes. It is not a
 * DocumentsProvider itself, because Robolectric's resolver queries through the old five-argument
 * form, which DocumentsProvider refuses and will not let a subclass answer. Everything that class
 * adds around a rename is permission checks and grants, which Robolectric does not model anyway.
 *
 * A document's id is its name, the way the phone's storage uses a path, so a rename ends the old
 * id and answers a new one. With [stableIds] it keeps the id and changes only the name, as a
 * cloud account's provider does.
 *
 * Names are taken the way the phone's storage takes them: without regard to case, and with
 * " (1)" added to one already in use, even when the file in use is the one being renamed. That
 * is FileSystemProvider.renameDocument, which asks File.exists of a folder that ignores case.
 */
internal class RenamingProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "test.renaming"

        /** DocumentsContract's own names for these, which it keeps out of the public API. */
        private const val METHOD_RENAME = "android:renameDocument"
        private const val EXTRA_URI = "uri"

        fun install(): RenamingProvider =
            Robolectric.buildContentProvider(RenamingProvider::class.java)
                .create(AUTHORITY)
                .get()

        fun uriFor(id: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, id)

        /** The same document, reached through a granted folder, as the home screen reaches it. */
        fun inFolder(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root"), id
        )
    }

    private class Doc(val file: File, var name: String, val flags: Int)

    private val docs = mutableMapOf<String, Doc>()

    /** Every rename asked of this provider, as the id it was asked of and the name it was given. */
    val renames = mutableListOf<Pair<String, String>>()

    var stableIds = false

    /** How many renames go through before the rest are turned down, as for a file it cannot change. */
    var refuseFrom = Int.MAX_VALUE

    /** Serves the fixture [fixture] as the document [id], renamable unless told otherwise. */
    fun add(id: String, fixture: String = id, renamable: Boolean = true): Uri {
        val flags = Document.FLAG_SUPPORTS_WRITE or
            (if (renamable) Document.FLAG_SUPPORTS_RENAME else 0)
        docs[id] = Doc(Fixtures.file(fixture), id, flags)
        return uriFor(id)
    }

    private fun doc(uri: Uri): Doc {
        val id = DocumentsContract.getDocumentId(uri)
        return docs[id] ?: throw FileNotFoundException(id)
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val doc = doc(uri)
        val columns = projection ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_FLAGS
        )
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> DocumentsContract.getDocumentId(uri)
                        Document.COLUMN_DISPLAY_NAME -> doc.name
                        Document.COLUMN_MIME_TYPE -> getType(uri)
                        Document.COLUMN_SIZE -> doc.file.length()
                        Document.COLUMN_FLAGS -> doc.flags
                        else -> null
                    }
                }.toTypedArray()
            )
        }
    }

    override fun getType(uri: Uri): String =
        documentMime(doc(uri).name.substringAfterLast('.', "").lowercase())

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(doc(uri).file, ParcelFileDescriptor.MODE_READ_ONLY)

    /** A rename, answered as DocumentsProvider answers one: the new URI, if there is one. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_RENAME || extras == null) return super.call(method, arg, extras)
        @Suppress("DEPRECATION")
        val uri = extras.getParcelable<Uri>(EXTRA_URI)!!
        val id = DocumentsContract.getDocumentId(uri)
        val name = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
        renames += id to name
        if (renames.size > refuseFrom) throw IllegalStateException("this provider will not rename $id")
        val doc = doc(uri)
        val taken = docs.values.map { it.name.lowercase() }
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val unique = generateSequence(0) { it + 1 }
            .map { n -> if (n == 0) name else name.substring(0, dot) + " ($n)" + name.substring(dot) }
            .first { it.lowercase() !in taken }
        doc.name = unique
        if (stableIds) return Bundle()
        docs.remove(id)
        docs[unique] = doc
        return Bundle().apply { putParcelable(EXTRA_URI, uriFor(unique)) }
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
