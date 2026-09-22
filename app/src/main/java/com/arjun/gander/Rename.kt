package com.arjun.gander

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
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
 * In practice that is a file picked from the phone's storage or its Downloads, then or later from
 * Recents, since the viewer keeps the write access along with the read for exactly this; a file in
 * a folder the reader added, whose write access the home screen keeps for the same reason; and a
 * file another app handed over with write access, which the system Files app does. Not one the
 * picker serves from its Recent, Images, Videos, Audio or Documents sections: those come from
 * Android's media provider, which renames nothing. Never a file inside a zip either, of which
 * nothing is written to the phone.
 */

/**
 * Whether the file at [uri] can be renamed from the viewer: a document, reached on its own or
 * through a folder the reader added, that its provider says it can rename, going by the [flags]
 * the provider reported for it, and that Gander [holdsWrite] access to.
 *
 * Never an added folder itself. The viewer only shows files, and renaming the folder would end
 * Android's grant on it, so it would drop off the home screen.
 */
internal fun canRename(uri: Uri, flags: Int, holdsWrite: Boolean): Boolean {
    if (uri.scheme != "content") return false
    val path = uri.pathSegments
    // content://<authority>/document/<id>, or tree/<folder id>/document/<id> for one in a folder
    val document = (path.size == 2 && path[0] == "document") ||
        (path.size == 4 && path[0] == "tree" && path[2] == "document" && path[3] != path[1])
    return document && flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0 && holdsWrite
}

/** What keeps a typed name from being given to a file. */
internal enum class NameProblem { EMPTY, BAD_CHARACTER, TOO_LONG }

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
    // Android's storage cuts a name at 255 bytes, which would read as the name being taken
    name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> NameProblem.TOO_LONG
    else -> null
}

/** The longest name, in UTF-8 bytes, that Android's storage keeps whole. */
private const val MAX_NAME_BYTES = 255

/**
 * Where [name] stops and its extension begins, which is how much of it the box selects to start
 * with, so that typing replaces the name and keeps the type. All of it for a name with no
 * extension, and for one whose only dot is its first character.
 */
internal fun baseNameEnd(name: String): Int =
    name.lastIndexOf('.').takeIf { it > 0 } ?: name.length

/**
 * Asks for a new name for the file at [uri], now called [name], and renames it on [worker].
 * [renamed] is handed where the file is afterwards and what it is called: a new URI wherever the
 * provider files documents by name, as the phone's own storage does.
 *
 * The box stays up while the provider is asked, so a problem is said where the name was typed,
 * in a line under the field rather than the bubble setError draws, which covers the buttons until
 * something is typed. A name left as it was closes the box and asks nothing. A new extension is
 * checked first, since the file may not open the same way after it, and a name another file has
 * is asked about before this one takes it with a number added.
 */
internal fun askForNewName(
    activity: AppCompatActivity,
    uri: Uri,
    name: String,
    worker: Executor,
    // What the box is built on: the activity, or the night theme over a PDF in night mode
    themed: Context = activity,
    renamed: (Uri, String) -> Unit,
): AlertDialog {
    val density = activity.resources.displayMetrics.density
    val field = EditText(themed).apply {
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
    val problem = TextView(themed).apply {
        id = R.id.rename_problem
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorError))
        setPadding(field.paddingLeft, (4 * density).toInt(), field.paddingRight, 0)
        isVisible = false
        // Read out as it appears, which is when somebody has just tapped Rename and is listening
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    // And kept on the field for a screen reader that comes back to it, as setError would have been
    ViewCompat.setAccessibilityDelegate(field, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            if (problem.isVisible) {
                info.isContentInvalid = true
                info.error = problem.text
            }
        }
    })
    // Gone as soon as the name is changed, the way setError's bubble goes
    field.doAfterTextChanged { problem.isVisible = false }
    val gutter = (24 * density).toInt()
    val holder = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(gutter, gutter / 3, gutter, 0)
        addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(problem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // Where the file is. Putting back a name that turned out to be taken can move it, on a
    // provider whose URIs change with every rename
    var current = uri
    // Closed without renaming, from a URI that moved: the viewer has to follow it
    val followIfMoved = { if (current != uri) renamed(current, name) }

    // The password box's insets, for its reason: see field_box_inset
    val inset = activity.resources.getDimensionPixelSize(R.dimen.field_box_inset)
    val box = DialogBuilder(themed)
        .setTitle(R.string.rename)
        .setView(holder)
        .setBackgroundInsetTop(inset)
        .setBackgroundInsetBottom(inset)
        .setPositiveButton(R.string.rename, null)
        .setNegativeButton(android.R.string.cancel) { _, _ -> followIfMoved() }
        .setOnCancelListener { followIfMoved() }
        .create()
    box.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    box.show()

    // Bound after show(), as Go to page's is: the builder's own listener closes the box before
    // it runs, and a name that cannot be used has to be able to say so with the box still up
    val ok = box.getButton(AlertDialog.BUTTON_POSITIVE)
    val main = Handler(Looper.getMainLooper())
    fun say(message: String) {
        problem.text = message
        problem.isVisible = true
    }

    val keyboard = box.window?.let { WindowCompat.getInsetsController(it, field) }

    /**
     * A question asked over the box. It has nothing to type into, so the keyboard goes while it
     * is up, and comes back when the answer is to go back to the name, with [select] choosing
     * which part of it to edit.
     */
    fun askOver(title: Int, message: String, yes: Int, no: Int, onYes: () -> Unit, select: () -> Unit) {
        keyboard?.hide(WindowInsetsCompat.Type.ime())
        val back = {
            select()
            field.requestFocus()
            keyboard?.show(WindowInsetsCompat.Type.ime())
        }
        DialogBuilder(themed)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton(no) { _, _ -> back() }
            .setOnCancelListener { back() }
            .show()
    }

    lateinit var attempt: (String, Boolean) -> Unit
    fun askToKeepBoth(wanted: String) = askOver(
        R.string.rename_taken_title, activity.getString(R.string.rename_taken_message, wanted),
        R.string.rename_keep_both, R.string.rename_change_name,
        onYes = { attempt(wanted, true) },
        // The name, as the box first offered it, since it is the name that needs changing
        select = { field.setSelection(0, baseNameEnd(field.text.toString())) },
    )
    attempt = { wanted, keepBoth ->
        ok.isEnabled = false
        worker.execute {
            // Off the main thread, since the app that keeps the file may be a cloud account
            val from = current
            val outcome = runCatching { renameAs(activity.contentResolver, from, name, wanted, keepBoth) }
                .getOrElse { Outcome.Refused(from) }
            main.post {
                if (activity.isDestroyed) return@post
                ok.isEnabled = true
                current = outcome.uri
                when (outcome) {
                    is Outcome.Renamed -> {
                        box.dismiss()
                        renamed(outcome.uri, outcome.called)
                    }
                    is Outcome.Taken -> askToKeepBoth(wanted)
                    is Outcome.Refused -> say(activity.getString(R.string.rename_failed))
                }
            }
        }
    }

    val submit = submit@{
        if (!ok.isEnabled) return@submit
        val wanted = field.text.toString().trim()
        if (wanted == name) {
            box.dismiss()
            followIfMoved()
            return@submit
        }
        when (nameProblem(wanted)) {
            NameProblem.EMPTY -> say(activity.getString(R.string.rename_empty))
            NameProblem.BAD_CHARACTER -> say(
                activity.getString(
                    R.string.rename_bad_character, FORBIDDEN_IN_NAMES.toList().joinToString(" ")
                )
            )
            NameProblem.TOO_LONG -> say(activity.getString(R.string.rename_too_long))
            null -> {
                val was = name.substring(baseNameEnd(name))
                val becomes = wanted.substring(baseNameEnd(wanted))
                if (was.equals(becomes, ignoreCase = true)) {
                    attempt(wanted, false)
                } else {
                    askOver(
                        R.string.rename_type_title,
                        activity.getString(R.string.rename_type_message, name, wanted),
                        R.string.rename_type_change, R.string.go_back,
                        onYes = { attempt(wanted, false) },
                        // The end of the name, where the extension that was asked about is
                        select = { field.setSelection(field.text.length) },
                    )
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

/** What asking for a name came to, and where the file is now. */
private sealed interface Outcome {
    val uri: Uri

    /** Renamed, and [called] what its provider has it as, not always what was typed. */
    class Renamed(override val uri: Uri, val called: String) : Outcome

    /** Another file has the name. This one keeps its own, and the reader is asked. */
    class Taken(override val uri: Uri) : Outcome

    /** The provider would not rename it. */
    class Refused(override val uri: Uri) : Outcome
}

/**
 * Renames the file at [uri] from [name] to [wanted], unless another file has that name, when the
 * file keeps its own and the reader is asked. [keepBoth] is their yes to that.
 *
 * Taken is found by trying. A file handed over on its own comes with no view of its folder, and
 * the phone's storage answers a name another file has by adding a number, so a name that comes
 * back numbered is put back and asked about. Renaming ends the grant on the URI renamed, the kept
 * one included, so the grant that comes with the name put back is kept in its place.
 */
private fun renameAs(
    resolver: ContentResolver,
    uri: Uri,
    name: String,
    wanted: String,
    keepBoth: Boolean,
): Outcome {
    if (wanted.equals(name, ignoreCase = true)) return changeCase(resolver, uri, name, wanted)
    val to = DocumentsContract.renameDocument(resolver, uri, wanted) ?: return Outcome.Refused(uri)
    val called = nameOf(resolver, to) ?: wanted
    if (keepBoth || called == wanted) return Outcome.Renamed(to, called)
    val back = runCatching { DocumentsContract.renameDocument(resolver, to, name) }.getOrNull()
        ?: return Outcome.Renamed(to, called)
    keep(resolver, back)
    return Outcome.Taken(back)
}

/**
 * A change of case alone, which goes by way of a name in between. The phone's storage ignores
 * case, so asked to call Photo.jpg photo.jpg it finds a file of that name already there, the file
 * itself, and calls it "photo (1).jpg" instead. Should the second step be refused, the name is
 * put back, and if that is refused too the file stays at the name in between, where it is.
 */
private fun changeCase(resolver: ContentResolver, uri: Uri, name: String, wanted: String): Outcome {
    val end = baseNameEnd(wanted)
    val between = wanted.substring(0, end) + " (renaming)" + wanted.substring(end)
    val through = DocumentsContract.renameDocument(resolver, uri, between) ?: return Outcome.Refused(uri)
    runCatching { DocumentsContract.renameDocument(resolver, through, wanted) }.getOrNull()
        ?.let { return Outcome.Renamed(it, nameOf(resolver, it) ?: wanted) }
    val back = runCatching { DocumentsContract.renameDocument(resolver, through, name) }.getOrNull()
        ?: return Outcome.Renamed(through, nameOf(resolver, through) ?: between)
    keep(resolver, back)
    return Outcome.Refused(back)
}

/** Keeps the grant a rename just issued, where the one it replaced was kept. */
private fun keep(resolver: ContentResolver, uri: Uri) {
    runCatching {
        resolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
}

/** What the provider calls the file at [uri], or null where it will not say. */
private fun nameOf(resolver: ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
