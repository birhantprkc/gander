package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * Renaming the open file.
 *
 * Which files are offered it is decided from three facts: the shape of the URI, the flags its
 * provider reports, and whether Gander holds write access. The last cannot be varied here, since
 * Robolectric answers every such check yes, so it is pinned through [canRename] directly and the
 * viewer is driven for the rest. The rename itself goes through a real DocumentsProvider.
 */
@RunWith(AndroidJUnit4::class)
class RenameTest {

    private lateinit var context: Context
    private lateinit var provider: RenamingProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        provider = RenamingProvider.install()
        Thumbs.resetForTests()
        context.getSharedPreferences("recents", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        ArchivePasswords.forgetAll()
    }

    // ---------------------------------------------------------------
    // Which files are offered it
    // ---------------------------------------------------------------

    private val renamable = DocumentsContract.Document.FLAG_SUPPORTS_RENAME

    /** What the picker hands over for a file in Download on the phone's own storage. */
    private val picked = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Download/report.pdf"
    )

    @Test
    fun aPickedFileItsProviderCanRenameIsOfferedIt() {
        assertThat(canRename(picked, renamable, holdsWrite = true)).isTrue()
    }

    /** A file opened from another app with read access alone, which is most of them. */
    @Test
    fun notWithoutWriteAccess() {
        assertThat(canRename(picked, renamable, holdsWrite = false)).isFalse()
    }

    @Test
    fun notWhereTheProviderSaysItCannot() {
        val writable = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        assertThat(canRename(picked, writable, holdsWrite = true)).isFalse()
    }

    private val addedFolder = DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents", "primary:Download"
    )

    /** A file in a folder the reader added, whose write access the home screen keeps. */
    @Test
    fun aFileInAnAddedFolderIsOfferedItToo() {
        val inFolder = DocumentsContract.buildDocumentUriUsingTree(addedFolder, "primary:Download/report.pdf")
        assertThat(canRename(inFolder, renamable, holdsWrite = true)).isTrue()
    }

    /** Never the folder itself: renaming it would end Android's grant on it. */
    @Test
    fun neverTheAddedFolderItself() {
        val folder = DocumentsContract.buildDocumentUriUsingTree(addedFolder, "primary:Download")
        assertThat(canRename(folder, renamable, holdsWrite = true)).isFalse()
    }

    @Test
    fun notAFileThatDidNotComeFromADocumentProvider() {
        listOf(
            FixtureProvider.uriFor("six-pages.pdf"),
            Uri.parse("file:///data/user/0/com.arjun.gander/cache/shared-text.txt"),
            ArchiveProvider.uriFor(
                context, picked, ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0))
            ),
        ).forEach { uri ->
            assertThat(canRename(uri, renamable, holdsWrite = true)).isFalse()
        }
    }

    // ---------------------------------------------------------------
    // What a name may be
    // ---------------------------------------------------------------

    @Test
    fun anOrdinaryNameIsFine() {
        listOf("Survey.pdf", "Größe 報告 (2).pdf", ".hidden", "no extension", "...").forEach {
            assertThat(nameProblem(it)).isNull()
        }
    }

    @Test
    fun noNameAtAllIsNotOne() {
        listOf("", ".", "..").forEach {
            assertThat(nameProblem(it)).isEqualTo(NameProblem.EMPTY)
        }
    }

    /** The characters Android's storage would swap for an underscore, each on its own. */
    @Test
    fun aNameWithACharacterAndroidWouldReplaceIsRefused() {
        (FORBIDDEN_IN_NAMES.toList() + Char(0x09) + Char(0x7F)).forEach { c ->
            assertThat(nameProblem("a${c}b.pdf")).isEqualTo(NameProblem.BAD_CHARACTER)
        }
    }

    /** Android's storage cuts a name at 255 bytes, which Chinese or Japanese reach in about 85 characters. */
    @Test
    fun aNameAndroidWouldCutShortIsRefused() {
        assertThat(nameProblem("a".repeat(251) + ".pdf")).isNull()
        assertThat(nameProblem("a".repeat(252) + ".pdf")).isEqualTo(NameProblem.TOO_LONG)
        assertThat(nameProblem("報".repeat(83) + ".pdf")).isNull()
        assertThat(nameProblem("報".repeat(84) + ".pdf")).isEqualTo(NameProblem.TOO_LONG)
    }

    /** The box starts with the name selected and the extension not, so typing keeps the type. */
    @Test
    fun theSelectionStopsAtTheExtension() {
        assertThat(baseNameEnd("report.pdf")).isEqualTo(6)
        assertThat(baseNameEnd("backup.tar.gz")).isEqualTo(10)
        assertThat(baseNameEnd("README")).isEqualTo(6)
        assertThat(baseNameEnd(".bashrc")).isEqualTo(7)
    }

    // ---------------------------------------------------------------
    // In the viewer
    // ---------------------------------------------------------------

    private fun view(uri: Uri): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
    }

    private fun ActivityController<ViewerActivity>.toolbar(): MaterialToolbar =
        get().findViewById(R.id.toolbar)

    private fun ActivityController<ViewerActivity>.renameOffered(): Boolean =
        toolbar().menu.findItem(R.id.action_rename).isVisible

    private fun ActivityController<ViewerActivity>.title(): String = toolbar().title.toString()

    private fun ActivityController<ViewerActivity>.views(): List<View> {
        val container = get().findViewById<FrameLayout>(R.id.container)
        return (0 until container.childCount).map { container.getChildAt(it) }
    }

    private fun ActivityController<ViewerActivity>.loadedUrl(): String =
        shadowOf(views().filterIsInstance<WebView>().single()).lastLoadedUrl

    /** Opens the Rename box, with the rename run inline, and answers it and its field. */
    private fun ActivityController<ViewerActivity>.askToRename(): Pair<AlertDialog, EditText> {
        get().renameWorker = Executor { it.run() }
        toolbar().menu.performIdentifierAction(R.id.action_rename, 0)
        val box = ShadowDialog.getLatestDialog() as AlertDialog
        fun find(v: View): EditText? = when (v) {
            is EditText -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return box to find(box.window!!.decorView)!!
    }

    private fun Pair<AlertDialog, EditText>.submit(name: String) {
        second.setText(name)
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** What the line under the field says, or null while it is hidden. */
    private fun Pair<AlertDialog, EditText>.problem(): String? =
        first.findViewById<TextView>(R.id.rename_problem)
            ?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    /** The box asked on top of the Rename box, and its message. */
    private fun prompt(): Pair<AlertDialog, String> {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        return dialog to dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
    }

    private fun AlertDialog.press(which: Int) {
        getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun grant(uri: Uri) =
        context.contentResolver.persistedUriPermissions.singleOrNull { it.uri == uri }

    @Test
    fun aFileThatCanBeRenamedHasRenameInTheMenu() {
        assertThat(view(provider.add("six-pages.pdf")).renameOffered()).isTrue()
    }

    @Test
    fun oneItsProviderCannotRenameDoesNot() {
        val uri = provider.add("six-pages.pdf", renamable = false)
        assertThat(view(uri).renameOffered()).isFalse()
    }

    @Test
    fun oneOpenedFromAFolderHasItToo() {
        provider.add("six-pages.pdf")
        assertThat(view(RenamingProvider.inFolder("six-pages.pdf")).renameOffered()).isTrue()
    }

    @Test
    fun aFileInAFolderIsRenamedWhereItIs() {
        provider.add("six-pages.pdf")
        val controller = view(RenamingProvider.inFolder("six-pages.pdf"))
        controller.askToRename().submit("Survey.pdf")

        assertThat(provider.renames).containsExactly("six-pages.pdf" to "Survey.pdf")
        assertThat(controller.title()).isEqualTo("Survey.pdf")
        assertThat(controller.loadedUrl()).contains("name=Survey.pdf")
    }

    @Test
    fun oneFromAProviderThatIsNotADocumentProviderDoesNot() {
        assertThat(view(FixtureProvider.uriFor("six-pages.pdf")).renameOffered()).isFalse()
    }

    /** Write access is what Rename needs once the picker's own grant has lapsed. */
    @Test
    fun aPickedFileThatCanBeRenamedKeepsWriteAccess() {
        val uri = provider.add("six-pages.pdf")
        view(uri)
        assertThat(grant(uri)?.isWritePermission).isTrue()
        assertThat(grant(uri)?.isReadPermission).isTrue()
    }

    /** And nothing else is kept writable. */
    @Test
    fun oneThatCannotBeRenamedKeepsReadAlone() {
        val uri = provider.add("six-pages.pdf", renamable = false)
        view(uri)
        assertThat(grant(uri)?.isWritePermission).isFalse()
        assertThat(grant(uri)?.isReadPermission).isTrue()
    }

    @Test
    fun theBoxOffersTheNameWithItsExtensionUnselected() {
        val (box, field) = view(provider.add("six-pages.pdf")).askToRename()
        assertThat(box.isShowing).isTrue()
        assertThat(field.text.toString()).isEqualTo("six-pages.pdf")
        assertThat(field.selectionStart).isEqualTo(0)
        assertThat(field.selectionEnd).isEqualTo(9)
    }

    @Test
    fun renamingReopensTheFileUnderItsNewName() {
        val controller = view(provider.add("six-pages.pdf"))
        val before = controller.get()
        controller.askToRename().submit("Survey.pdf")

        assertThat(provider.renames).containsExactly("six-pages.pdf" to "Survey.pdf")
        assertThat(controller.get()).isNotSameInstanceAs(before)
        assertThat(controller.title()).isEqualTo("Survey.pdf")
        assertThat(controller.loadedUrl()).contains("name=Survey.pdf")
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Renamed to Survey.pdf")
        assertThat(ShadowDialog.getLatestDialog().isShowing).isFalse()
    }

    /** A new extension is asked about first, since the file may not open the same way after it. */
    @Test
    fun aNewExtensionIsAskedAboutFirst() {
        val controller = view(provider.add("notes.md"))
        val asked = controller.askToRename()
        asked.submit("notes.txt")

        val (prompt, message) = prompt()
        assertThat(prompt).isNotSameInstanceAs(asked.first)
        assertThat(message).startsWith("notes.md would become notes.txt.")
        assertThat(provider.renames).isEmpty()

        prompt.press(AlertDialog.BUTTON_NEGATIVE)
        assertThat(provider.renames).isEmpty()
        assertThat(asked.first.isShowing).isTrue()
    }

    /** Changed anyway, it is a new type, and the viewer is chosen again for it. */
    @Test
    fun aNewExtensionChoosesTheViewerAgain() {
        val controller = view(provider.add("notes.md"))
        assertThat(controller.loadedUrl()).contains("viewer/md.html")
        controller.askToRename().submit("notes.txt")
        prompt().first.press(AlertDialog.BUTTON_POSITIVE)
        assertThat(controller.loadedUrl()).contains("viewer/text.html")
    }

    /** One that only changes case is the same type, and nothing is asked. */
    @Test
    fun anExtensionInAnotherCaseIsNotAskedAbout() {
        val controller = view(provider.add("notes.md"))
        val asked = controller.askToRename()
        asked.submit("Notes.MD")
        assertThat(provider.renames).isNotEmpty()
        assertThat(controller.title()).isEqualTo("Notes.MD")
    }

    /**
     * The phone's storage ends the old URI in a rename and issues a new one, and Recents follows
     * it there, taking both halves of the grant with it.
     */
    @Test
    fun theRenamedFileKeepsItsPlaceInRecentsUnderItsNewUri() {
        val old = provider.add("six-pages.pdf")
        val controller = view(old)
        assertThat(Recents.all(context).map { it.uri }).containsExactly(old.toString())

        controller.askToRename().submit("Survey.pdf")

        val new = RenamingProvider.uriFor("Survey.pdf")
        assertThat(Recents.all(context).map { it.uri to it.name })
            .containsExactly(new.toString() to "Survey.pdf")
        assertThat(grant(old)).isNull()
        assertThat(grant(new)?.isWritePermission).isTrue()
    }

    /** A cloud account's provider keeps the URI and changes only the name. */
    @Test
    fun aProviderThatKeepsTheUriKeepsIt() {
        provider.stableIds = true
        val uri = provider.add("six-pages.pdf")
        val controller = view(uri)
        controller.askToRename().submit("Survey.pdf")

        assertThat(controller.title()).isEqualTo("Survey.pdf")
        assertThat(Recents.all(context).map { it.uri to it.name })
            .containsExactly(uri.toString() to "Survey.pdf")
        assertThat(grant(uri)?.isWritePermission).isTrue()
    }

    /** The phone's storage would call it "six-pages (1).pdf", finding one there already. */
    @Test
    fun aNameLeftAsItWasRenamesNothing() {
        val controller = view(provider.add("six-pages.pdf"))
        val before = controller.get()
        val asked = controller.askToRename()
        asked.submit("six-pages.pdf ")
        assertThat(provider.renames).isEmpty()
        assertThat(asked.first.isShowing).isFalse()
        assertThat(controller.get()).isSameInstanceAs(before)
    }

    @Test
    fun noNameSaysSoAndAsksNothing() {
        val asked = view(provider.add("six-pages.pdf")).askToRename()
        asked.submit("   ")
        assertThat(asked.first.isShowing).isTrue()
        assertThat(asked.problem()).isEqualTo(context.getString(R.string.rename_empty))
        assertThat(provider.renames).isEmpty()
    }

    /** The line under the field goes as soon as the name is changed. */
    @Test
    fun aProblemGoesOnceTheNameIsChanged() {
        val asked = view(provider.add("six-pages.pdf")).askToRename()
        asked.submit("   ")
        assertThat(asked.problem()).isNotNull()
        asked.second.setText("Survey.pdf")
        assertThat(asked.problem()).isNull()
    }

    @Test
    fun aSlashSaysWhichCharactersCannotBeUsed() {
        val asked = view(provider.add("six-pages.pdf")).askToRename()
        asked.submit("2026/09 survey.pdf")
        assertThat(asked.first.isShowing).isTrue()
        assertThat(asked.problem()).isEqualTo(
            context.getString(R.string.rename_bad_character, "\" * / : < > ? \\ |")
        )
        assertThat(provider.renames).isEmpty()
    }

    /** Said where the name was typed, with the box left up and ready for another go. */
    @Test
    fun aRefusalSaysSoInTheBox() {
        provider.refuse = { true }
        val controller = view(provider.add("six-pages.pdf"))
        val before = controller.get()
        val asked = controller.askToRename()
        asked.submit("Survey.pdf")

        assertThat(provider.renames).hasSize(1)
        assertThat(asked.first.isShowing).isTrue()
        assertThat(asked.problem()).isEqualTo(context.getString(R.string.rename_failed))
        assertThat(asked.first.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled).isTrue()
        assertThat(controller.get()).isSameInstanceAs(before)
        assertThat(controller.title()).isEqualTo("six-pages.pdf")
    }

    /**
     * The phone's storage does not replace a file that has the name already: it numbers this one.
     * That is asked about first, the file keeping its own name meanwhile.
     */
    @Test
    fun aTakenNameIsAskedAboutBeforeItIsNumbered() {
        provider.add("plain.txt")
        val controller = view(provider.add("notes.txt", fixture = "plain.txt"))
        val asked = controller.askToRename()
        asked.submit("plain.txt")

        val (prompt, message) = prompt()
        assertThat(prompt).isNotSameInstanceAs(asked.first)
        assertThat(message).contains("There's already a file called plain.txt here.")
        assertThat(provider.has("notes.txt")).isTrue()
        assertThat(provider.has("plain (1).txt")).isFalse()
        assertThat(controller.title()).isEqualTo("notes.txt")
    }

    /** Keep both lets the storage number this one, and the toast says what it was called. */
    @Test
    fun keepingBothNumbersThisOne() {
        provider.add("plain.txt")
        val controller = view(provider.add("notes.txt", fixture = "plain.txt"))
        controller.askToRename().submit("plain.txt")
        prompt().first.press(AlertDialog.BUTTON_POSITIVE)

        assertThat(controller.title()).isEqualTo("plain (1).txt")
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Renamed to plain (1).txt")
    }

    @Test
    fun changingTheNameGoesBackToTheBox() {
        provider.add("plain.txt")
        val controller = view(provider.add("notes.txt", fixture = "plain.txt"))
        val asked = controller.askToRename()
        asked.submit("plain.txt")
        val renamesSoFar = provider.renames.size
        prompt().first.press(AlertDialog.BUTTON_NEGATIVE)

        assertThat(asked.first.isShowing).isTrue()
        assertThat(provider.renames).hasSize(renamesSoFar)
        assertThat(controller.title()).isEqualTo("notes.txt")
    }

    /**
     * Alone, a change of case would come out as "photo (1).jpg", the storage finding the file
     * itself under the name it is asked for. By way of a name in between it comes out as asked.
     */
    @Test
    fun aChangeOfCaseAloneComesOutAsTyped() {
        val controller = view(provider.add("Photo.jpg", fixture = "exif-1.jpg"))
        controller.askToRename().submit("photo.jpg")

        assertThat(controller.title()).isEqualTo("photo.jpg")
        assertThat(provider.renames).containsExactly(
            "Photo.jpg" to "photo (renaming).jpg",
            "photo (renaming).jpg" to "photo.jpg",
        ).inOrder()
    }

    /** The first step has ended the URI the file had, so the viewer follows it to the name between. */
    @Test
    fun aChangeOfCaseRefusedHalfwayLeavesTheFileWhereItIs() {
        provider.refuse = { it >= 2 }
        val controller = view(provider.add("Photo.jpg", fixture = "exif-1.jpg"))
        controller.askToRename().submit("photo.jpg")

        assertThat(controller.title()).isEqualTo("photo (renaming).jpg")
        assertThat(Recents.all(context).map { it.name }).containsExactly("photo (renaming).jpg")
    }

    /** Refused halfway but put back: the file keeps its name, and the box says it could not. */
    @Test
    fun aChangeOfCaseRefusedHalfwayPutsTheNameBack() {
        provider.refuse = { it == 2 }
        val controller = view(provider.add("Photo.jpg", fixture = "exif-1.jpg"))
        val asked = controller.askToRename()
        asked.submit("photo.jpg")

        assertThat(provider.has("Photo.jpg")).isTrue()
        assertThat(asked.problem()).isEqualTo(context.getString(R.string.rename_failed))
        assertThat(controller.title()).isEqualTo("Photo.jpg")
    }

    /** The password a zip was opened with is held under its URI, so it moves to the new one. */
    @Test
    fun aZipKeepsItsPasswordUnderItsNewName() {
        val old = provider.add("locked.zip")
        val controller = view(old)
        ArchivePasswords.remember(old, "gander")

        controller.askToRename().submit("Private.zip")

        assertThat(ArchivePasswords.get(RenamingProvider.uriFor("Private.zip"))).isEqualTo("gander")
        assertThat(ArchivePasswords.get(old)).isNull()
    }

    /**
     * A track rebuilt around its new name carries on from where it had got to, kept in the saved
     * state the rebuild passes on. Looked at in two halves, because under Robolectric the player
     * plays the whole second of the fixture the moment the main thread is let run.
     */
    @Test
    fun aTrackKeepsItsPlaceWhenTheViewerIsRebuilt() {
        val uri = provider.add("tone.wav")
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/wav")
        val first = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        first.views().filterIsInstance<PlayerView>().single().player!!.apply {
            pause()
            seekTo(700)
        }
        val state = android.os.Bundle()
        first.saveInstanceState(state)

        val second = Robolectric.buildActivity(ViewerActivity::class.java, intent).create(state)
        val player = second.views().filterIsInstance<PlayerView>().single().player!!
        assertThat(player.currentPosition).isEqualTo(700)
    }
}
