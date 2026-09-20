package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.RandomAccessFile
import java.util.Locale
import org.junit.Test

/**
 * A zip's flat list of paths, turned into folders to walk through. Issue #30.
 *
 * The ordering and the hiding are orderChildren's, the function the folder browser uses, so
 * these pin what reaches it: every folder on the way to a file, whether or not the archive
 * lists it, and nothing macOS added.
 */
class ArchiveTreeTest {

    private fun tree(fixture: String): ArchiveTree {
        val raf = RandomAccessFile(Fixtures.file(fixture), "r")
        return ZipSource(raf.channel, 0, raf.length(), raf).use {
            ArchiveTree(ZipReader.entries(it, Locale.US))
        }
    }

    private fun ArchiveTree.folders(path: String) = list(path).first

    private fun ArchiveTree.files(path: String) = list(path).second.map { it.name }

    @Test
    fun theTopHoldsFoldersFirstThenFiles() {
        val tree = tree("archive.zip")
        assertThat(tree.folders("")).containsExactly("nested", "photos", "private", "reports").inOrder()
        assertThat(tree.files("")).containsExactly("plain.txt")
    }

    /** macOS's resource forks are neither a folder anybody made nor a file anybody wants. */
    @Test
    fun whatMacOsAddsIsLeftOut() {
        val tree = tree("archive.zip")
        assertThat(tree.folders("")).doesNotContain("__MACOSX")
        assertThat(tree.has("__MACOSX")).isFalse()
    }

    @Test
    fun dotfilesAreLeftOutAsTheyAreFromAFolderOnThePhone() {
        assertThat(tree("archive.zip").files("photos")).containsExactly("tiny.png")
    }

    /** photos/ has no entry of its own in the archive, only a file inside it. */
    @Test
    fun aFolderTheArchiveNeverListsIsStillThere() {
        val tree = tree("archive.zip")
        assertThat(tree.has("photos")).isTrue()
        assertThat(tree.has("reports")).isTrue()
    }

    @Test
    fun filesAreSortedByNameWithoutRegardToCase() {
        assertThat(tree("archive.zip").files("reports"))
            .containsExactly("notes.md", "six-pages.pdf").inOrder()
    }

    @Test
    fun foldersNestAsDeepAsTheirPaths() {
        val tree = tree("odd-names.zip")
        assertThat(tree.folders("")).containsExactly("a", "absolute", "docs").inOrder()
        assertThat(tree.folders("a")).containsExactly("b")
        assertThat(tree.files("a/b")).containsExactly("c.txt")
        assertThat(tree.files("docs")).containsExactly("readme.txt")
    }

    /** Two entries with one name are two files, and both are shown. */
    @Test
    fun aNameTheArchiveHoldsTwiceIsListedTwice() {
        assertThat(tree("odd-names.zip").files("").filter { it == "dup.txt" }).hasSize(2)
    }

    @Test
    fun aFolderThatIsNotThereListsNothing() {
        val tree = tree("archive.zip")
        assertThat(tree.has("nowhere")).isFalse()
        assertThat(tree.folders("nowhere")).isEmpty()
        assertThat(tree.files("nowhere")).isEmpty()
    }
}
