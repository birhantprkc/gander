package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.RandomAccessFile
import java.util.Locale
import org.junit.Assert.assertThrows
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

    private fun file(path: String) = ArchiveEntry(path, false, 0, EntryLocation(0, 0, 0, 0, 0))

    /**
     * Bytes this thread has allocated. By reflection, since the tests compile against
     * android.jar, which has no java.lang.management, and run on a JVM that has it.
     */
    private fun allocated(): Long {
        val threads = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        return Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getCurrentThreadAllocatedBytes").invoke(threads) as Long
    }

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

    /**
     * One name 30,000 folders deep, which is as deep as a zip's 64 KB name goes. Found by
     * scripts/zip-fuzz: kept as paths, every folder on the way down held a path of its own,
     * the square of the name's length, and listing this asked for two gigabytes.
     */
    @Test
    fun aNameThirtyThousandFoldersDeepListsWithoutTheSquareOfItsLength() {
        val folder = "a/".repeat(29_999) + "a"
        val before = allocated()
        val tree = ArchiveTree(listOf(file("$folder/f.txt")))
        assertThat(allocated() - before).isLessThan(64L * 1024 * 1024)
        assertThat(tree.has(folder)).isTrue()
        assertThat(tree.files(folder)).containsExactly("f.txt")
        assertThat(tree.folders("a/a")).containsExactly("a")
    }

    /**
     * A thousand names, each starting a chain of 201 folders of its own: 201,000 folders, one
     * past a limit no real archive comes near, and far short of what the same 32 MB index can
     * make, which is millions. Refused as too large to list rather than filling the heap.
     */
    @Test
    fun moreFoldersThanTheLimitIsTooLargeToList() {
        val chain = "a/".repeat(200)
        assertThrows(ZipReader.TooLarge::class.java) {
            ArchiveTree((0 until 1000).map { file("d$it/${chain}f") })
        }
        // And one short of the limit still lists
        val tree = ArchiveTree((0 until 995).map { file("d$it/${chain}f") })
        assertThat(tree.files("d994/$chain".dropLast(1))).containsExactly("f")
    }
}
