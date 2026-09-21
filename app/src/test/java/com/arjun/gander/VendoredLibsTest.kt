package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * The vendored viewer libraries, and the three places that describe them.
 *
 * Gander ships every renderer inside the APK and fetches nothing, so the
 * licence notice it displays and the provenance file in the repo are the whole
 * of its answer to "what is in this binary". Those two and the fetch script
 * are maintained by hand and have already drifted once, which is what this
 * catches.
 *
 * Unit tests run with the module directory as their working directory, so the
 * repository root is one level up.
 */
class VendoredLibsTest {

    private companion object {
        val REPO = File("..")
        val LIB = File(REPO, "app/src/main/assets/viewer/lib")
        val CMAPS = File(LIB, "cmaps")

        val FETCH_SCRIPT = File(REPO, "scripts/fetch-viewer-libs.sh").readText()
        val VENDORED_MD = File(REPO, "docs/VENDORED.md").readText()
        val LICENCES_MD = File(REPO, "app/src/main/assets/licences.md").readText()

        /** The pdf.js release the script pins, read out of the script itself. */
        val PDFJS_VER: String =
            Regex("""PDFJS_VER="([^"]+)"""").find(FETCH_SCRIPT)!!.groupValues[1]

        /**
         * The files the Word and Markdown floors in WebViewFloor.kt were measured
         * on, as `shasum -a 256 <file>` prints each from the lib directory. Neither
         * docx-preview nor marked publishes a minimum, so those floors are
         * measurements, and a measurement is of one file.
         */
        val FLOORS_MEASURED_ON = mapOf(
            "docx-preview.min.js" to "051ef503f2677d53159a388b7384e950eda41ea4e47a103e5e36f124d7faea40",
            "jszip3.min.js" to "acc7e41455a80765b5fd9c7ee1b8078a6d160bbbca455aeae854de65c947d59e",
            "marked.min.js" to "3e7e7d7feb3e5d58cb6c804f68ab5c24cc7e5eb6270fd6e5cbb9124739217d0c",
            "purify.min.js" to "c45ba939765574f96cbf35ee9b6d89f73756a17921814425e74b82f7c54603ce",
        )
    }

    @Test
    fun theFetchScriptPinsAPdfJsVersion() {
        assertThat(PDFJS_VER).matches("""\d+\.\d+\.\d+""")
    }

    /**
     * The three records of the pdf.js version have to agree. The floor in
     * WebViewFloor.kt is derived from this release's own minimum, so a bump
     * that updates one and not the others leaves the app refusing PDFs on
     * engines that would have run them, or accepting ones that will not.
     */
    @Test
    fun everyRecordOfThePdfJsVersionAgrees() {
        assertThat(VENDORED_MD).contains(PDFJS_VER)
        assertThat(LICENCES_MD).contains(PDFJS_VER)
    }

    @Test
    fun theVendoredLibrariesAreAllPresent() {
        val expected = listOf(
            "pdf.min.mjs", "pdf.worker.min.mjs", "jszip3.min.js",
            "docx-preview.min.js", "xlsx.full.min.js", "marked.min.js",
            "purify.min.js",
            "pptx/pptxjs.js", "pptx/divs2slides.js", "pptx/filereader.js",
            "pptx/jquery.min.js", "pptx/jszip2.min.js", "pptx/d3.min.js",
            "pptx/nv.d3.min.js", "pptx/pptxjs.css", "pptx/nv.d3.min.css",
        )
        expected.forEach { name ->
            val f = File(LIB, name)
            assertThat("$name exists=${f.exists()} bytes=${f.length() > 0}")
                .isEqualTo("$name exists=true bytes=true")
        }
    }

    /**
     * Every shipped library is named in both the provenance file and the
     * licence notice the app displays. Shipping a library the notice does not
     * mention is the licence problem; naming one that is not there is the
     * smaller half of the same drift.
     */
    @Test
    fun everyShippedLibraryIsNamedInBothRecords() {
        val shipped = LIB.walkTopDown()
            .filter { it.isFile && (it.extension == "js" || it.extension == "mjs") }
            .map { it.name }
            .toList()
        assertThat(shipped).isNotEmpty()
        shipped.forEach { name ->
            assertThat("$name in VENDORED.md: ${VENDORED_MD.contains(name)}")
                .isEqualTo("$name in VENDORED.md: true")
        }
    }

    // ---------------------------------------------------------------
    // The files the Word and Markdown floors were measured on
    // ---------------------------------------------------------------

    /**
     * docx.html loads docx-preview and JSZip, md.html marked and DOMPurify, and
     * the floors in WebViewFloor.kt were measured on exactly these four files.
     * The fetch script takes the newest marked 15 and DOMPurify 3, so a refetch
     * can hand over a library that needs a newer engine than its floor says, and
     * a reader on that engine is back to an error naming a missing part instead
     * of the card that says why: issue #31.
     */
    @Test
    fun theWordAndMarkdownFloorsWereMeasuredOnTheseFiles() {
        FLOORS_MEASURED_ON.forEach { (name, measured) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(File(LIB, name).readBytes())
            val fingerprint = digest.joinToString("") { "%02x".format(it) }
            assertWithMessage(
                "$name is not the file the floors in WebViewFloor.kt were measured on. Measure it " +
                    "as docs/VENDORED.md describes, set the floor from what that finds, then put " +
                    "the new fingerprint in FLOORS_MEASURED_ON",
            ).that(fingerprint).isEqualTo(measured)
        }
    }

    // ---------------------------------------------------------------
    // The CMap tables, which fail silently when they are missing
    // ---------------------------------------------------------------

    /**
     * A PDF naming a CJK encoding without embedding the font renders blank
     * paragraphs without throwing when these are absent: no exception, no
     * fallback boxes, just missing text. They were left untracked once
     * already. tests/viewer/test_pdf_render.py proves they work; this proves
     * they ship.
     */
    @Test
    fun theCjkCmapTablesShip() {
        assertThat(CMAPS.isDirectory).isTrue()
        val bcmaps = CMAPS.listFiles { f -> f.extension == "bcmap" }.orEmpty()
        assertThat(bcmaps.size).isAtLeast(150)
        assertThat(bcmaps.all { it.length() > 0 }).isTrue()
    }

    /** The encodings the four CJK scripts actually use. */
    @Test
    fun theTablesForEachCjkScriptArePresent() {
        listOf(
            "UniGB-UCS2-H",    // Simplified Chinese
            "UniCNS-UCS2-H",   // Traditional Chinese
            "UniJIS-UCS2-H",   // Japanese
            "UniKS-UCS2-H",    // Korean
        ).forEach { name ->
            val f = File(CMAPS, "$name.bcmap")
            assertThat("$name.bcmap exists=${f.exists()}").isEqualTo("$name.bcmap exists=true")
        }
    }

    /** Adobe's tables carry their own licence, and it travels with them. */
    @Test
    fun theCmapLicenceTravelsWithTheTables() {
        assertThat(File(CMAPS, "LICENSE").exists()).isTrue()
        assertThat(VENDORED_MD).contains("cmaps/")
        assertThat(LICENCES_MD).contains("CMap")
    }

    // ---------------------------------------------------------------

    /**
     * Nothing in the shipped viewer may reach the network. The app has no
     * INTERNET permission, so a stray CDN reference fails silently rather than
     * loudly, and the page renders wrong with nothing to say why.
     *
     * The lib directory is upstream code and is not searched: a minified
     * bundle mentions its own homepage in a banner comment. This covers the
     * hand-written pages, which are the ones a change could add a tag to.
     */
    @Test
    fun noHandWrittenViewerPageReferencesARemoteResource() {
        val viewer = File(REPO, "app/src/main/assets/viewer")
        val ours = viewer.listFiles { f -> f.isFile }.orEmpty()
        assertThat(ours).isNotEmpty()
        val remote = Regex("""(src|href)\s*=\s*["']https?://""", RegexOption.IGNORE_CASE)
        ours.forEach { page ->
            val hit = remote.find(page.readText())
            assertThat("${page.name}: ${hit?.value ?: "no remote reference"}")
                .isEqualTo("${page.name}: no remote reference")
        }
    }
}
