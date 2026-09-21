package com.arjun.gander

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executor

/**
 * Renaming the open file.
 *
 * No permission is involved, any more than in Save a copy. The system picker hands Gander read
 * and write access to the one file picked and to nothing around it, and a rename is asked of the
 * app that keeps the file, through that grant: DocumentsContract.renameDocument. So Rename is
 * offered where the file came from a document provider, the provider says it can be renamed,
 * and Gander holds write access to it.
 *
 * In practice that is a file opened through the picker, then or later from Recents, since the
 * viewer keeps the write access along with the read for exactly this, and a file another app
 * handed over with write access, which the system Files app does. Never a file browsed in a
 * granted folder: folders are read-only, and the write access Android also hands over when one
 * is added, which lasts only while Gander stays open, is left unused so that Rename does not come
 * and go with it. Never a file inside a zip either, of which nothing is written to the phone.
 */

/**
 * Whether the file at [uri] can be renamed from the viewer: a document reached on its own rather
 * than through a folder, that its provider says it can rename, going by the [flags] the provider
 * reported for it, and that Gander [holdsWrite] access to.
 */
internal fun canRename(uri: Uri, flags: Int, holdsWrite: Boolean): Boolean =
    uri.scheme == "content" &&
        // content://<authority>/document/<id>; a folder's files are tree/<id>/document/<id>
        uri.pathSegments.size == 2 && uri.pathSegments[0] == "document" &&
        flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0 &&
        holdsWrite

/** What keeps a typed name from being given to a file. */
internal enum class NameProblem { EMPTY, BAD_CHARACTER }

/**
 * The characters no name may have, besides control characters: the ones Android's own storage
 * swaps for an underscore (FileUtils.buildValidFatFilename). Refused rather than let through, so
 * that a file does not come out named something other than what was typed. The slash would
 * read as a folder to anything else.
 */
internal const val FORBIDDEN_IN_NAMES = "\"*/:<>?\\|"

/**
 * Why [name], already trimmed, cannot be a file's new name, or null when it can. A lone dot or
 * two name folders rather than files, and Android's storage turns either into "(invalid)", so
 * they count as no name at all.
 */
internal fun nameProblem(name: String): NameProblem? = when {
    name.isEmpty() || name == "." || name == ".." -> NameProblem.EMPTY
    name.any { it.code < 0x20 || it.code == 0x7F || it in FORBIDDEN_IN_NAMES } ->
        NameProblem.BAD_CHARACTER
    else -> null
}

/**
 * Where [name] stops and its extension begins, which is how much of it the box selects to start
 * with, so that typing replaces the name and keeps the type. All of it for a name with no
 * extension, and for one whose only dot is its first character.
 */
internal fun baseNameEnd(name: String): Int =
    name.lastIndexOf('.').takeIf { it > 0 } ?: name.length

/**
 * Asks for a new name for the file at [uri], now called [name], and renames it on [worker].
 * [renamed] is handed the file's URI afterwards, which is a new one wherever the provider files
 * documents by name, as the phone's own storage does, and the name it ended up with.
 *
 * The box stays up while the provider is asked, so a refusal says so where the name was typed,
 * the way a wrong password does. A name left as it was closes the box and asks nothing: the
 * phone's storage would find a file of that name already there and call this one "name (1)".
 */
internal fun askForNewName(
    activity: AppCompatActivity,
    uri: Uri,
    name: String,
    worker: Executor,
    renamed: (Uri, String) -> Unit,
): AlertDialog {
    val density = activity.resources.displayMetrics.density
    val field = EditText(activity).apply {
        // Plain text without suggestions: a keyboard's corrections turn a file name into words
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        hint = activity.getString(R.string.file_name)
        // A plain field comes out at 43dp, under the 48 Android asks of anything tapped
        minHeight = (48 * density).toInt()
        setText(name)
        setSelection(0, baseNameEnd(name))
        requestFocus()
    }
    val gutter = (24 * density).toInt()
    val holder = FrameLayout(activity).apply {
        setPadding(gutter, gutter / 3, gutter, 0)
        addView(
            field,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }
    // The password box's insets, for its reason: see field_box_inset
    val inset = activity.resources.getDimensionPixelSize(R.dimen.field_box_inset)
    val box = MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.rename)
        .setView(holder)
        .setBackgroundInsetTop(inset)
        .setBackgroundInsetBottom(inset)
        .setPositiveButton(R.string.rename, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    box.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    box.show()

    // Bound after show(), as Go to page's is: the builder's own listener closes the box before
    // it runs, and a name that cannot be used has to be able to say so with the box still up
    val ok = box.getButton(AlertDialog.BUTTON_POSITIVE)
    val main = Handler(Looper.getMainLooper())
    val submit = submit@{
        if (!ok.isEnabled) return@submit
        val wanted = field.text.toString().trim()
        if (wanted == name) {
            box.dismiss()
            return@submit
        }
        when (nameProblem(wanted)) {
            NameProblem.EMPTY -> field.error = activity.getString(R.string.rename_empty)
            NameProblem.BAD_CHARACTER -> field.error = activity.getString(
                R.string.rename_bad_character, FORBIDDEN_IN_NAMES.toList().joinToString(" ")
            )
            null -> {
                ok.isEnabled = false
                worker.execute {
                    // Off the main thread, since the app that keeps the file may be a cloud
                    // account. A refusal comes back as null or, from most providers, a throw
                    val resolver = activity.contentResolver
                    val to = runCatching { rename(resolver, uri, name, wanted) }.getOrNull()
                    // Not always what was typed: the phone's storage calls the file "name (1)"
                    // rather than replace one already called that
                    val called = to?.let { nameOf(resolver, it) } ?: wanted
                    main.post {
                        if (activity.isDestroyed) return@post
                        ok.isEnabled = true
                        if (to == null) {
                            field.error = activity.getString(R.string.rename_failed)
                        } else {
                            box.dismiss()
                            renamed(to, called)
                        }
                    }
                }
            }
        }
    }
    ok.setOnClickListener { submit() }
    field.setOnEditorActionListener { _, _, _ ->
        submit()
        true
    }
    return box
}

/**
 * Renames the file at [uri] from [name] to [wanted] and answers its URI afterwards, or null where
 * the provider refused.
 *
 * A change of case alone goes by way of a name in between. The phone's storage ignores case, so
 * asked to call Photo.jpg photo.jpg it finds a file of that name already there, the file itself,
 * and calls it "photo (1).jpg" instead. Should the second step be refused, the file keeps the name
 * in between, and that is the URI answered: the first step has ended the one it started from.
 */
private fun rename(resolver: ContentResolver, uri: Uri, name: String, wanted: String): Uri? {
    if (!wanted.equals(name, ignoreCase = true)) {
        return DocumentsContract.renameDocument(resolver, uri, wanted)
    }
    val end = baseNameEnd(wanted)
    val between = wanted.substring(0, end) + " (renaming)" + wanted.substring(end)
    val through = DocumentsContract.renameDocument(resolver, uri, between) ?: return null
    return runCatching { DocumentsContract.renameDocument(resolver, through, wanted) }.getOrNull()
        ?: through
}

/** What the provider calls the file at [uri], or null where it will not say. */
private fun nameOf(resolver: ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
