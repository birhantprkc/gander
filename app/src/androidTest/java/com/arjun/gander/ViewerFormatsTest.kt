package com.arjun.gander

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One document of every format, opened end to end on a device.
 *
 * Deliberately shallow: each of these renders a real file with the real
 * library inside the real WebView, and asserts that something recognisable
 * from the document came out. What each renderer does in detail is covered
 * far more cheaply in tests/viewer.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ViewerFormatsTest {

    @get:Rule
    val retry = RetryRule()

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    private fun open(fixture: String) =
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent(fixture))

    private fun expect(fixture: String, selector: String, text: String) {
        open(fixture).use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, selector))
                .check(webMatches(getText(), containsString(text)))
        }
    }

    @Test
    fun aWordDocumentRenders() =
        expect("report.docx", "#container", "Field Survey")

    @Test
    fun aSpreadsheetRenders() =
        expect("budget.xlsx", "#sheet", "Surveying")

    @Test
    fun aCsvRendersAsASpreadsheetRatherThanText() =
        expect("budget.csv", "#sheet", "Surveying")

    @Test
    fun aSlideDeckRenders() =
        expect("deck.pptx", "#result", "Willowmere")

    @Test
    fun markdownRenders() =
        expect("notes.md", "#content", "Willowmere site notes")

    @Test
    fun plainTextRenders() =
        expect("plain.txt", "#content", "Plain text")

    /**
     * The space after the colon is only there if the formatter ran: the fixture
     * is written with no spacing at all. Worth a device test as well as the
     * browser ones for the same reason the sanitiser below is, that the WebView
     * is where it actually runs.
     */
    @Test
    fun aJsonFileIsLaidOutOnTheDevice() =
        expect("snapshot.json", "#content", "\"app\": \"com.example.reader\"")

    /**
     * Markdown is untrusted input and DOMPurify is what stands between it and
     * the DOM. Worth one device test as well as the browser one, because the
     * WebView is where it would actually matter.
     */
    @Test
    fun markdownIsSanitisedOnTheDevice() {
        open("notes.md").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), not(containsString("window.__xss"))))
        }
    }

    @Test
    fun anUnknownFormatOffersToShowItAsText() {
        open("unknown.xyz").use {
            onWebView()
                .withElement(findElement(Locator.ID, "asText"))
                .check(webMatches(getText(), containsString("View as text")))
        }
    }

    // The three formats read by Gander's own code rather than a library, on the
    // one page that asks the file's bytes which it is

    @Test
    fun aWord97DocumentRenders() =
        expect("legacy.doc", "#container", "Field Survey")

    @Test
    fun anOpenDocumentTextRenders() =
        expect("letter.odt", "#container", "Field Survey")

    @Test
    fun aRichTextFileRenders() =
        expect("memo.rtf", "#container", "Field Survey")
}
