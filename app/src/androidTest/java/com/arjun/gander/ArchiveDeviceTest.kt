package com.arjun.gander

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A .zip on a device, issue #30: the list, and files out of it in every surface.
 *
 * The part that cannot be tested anywhere else is the pipe. A compressed file reaches a viewer
 * through one, inflated on a thread as it is read, and Robolectric imitates pipes with a file.
 * So the Markdown and the PDF here are both compressed in the archive, and the photo is stored,
 * which is the other road: a window onto the archive's own bytes.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ArchiveDeviceTest {

    @get:Rule
    val retry = RetryRule()

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    /** The URI Gander's own list would open [path] under. */
    private fun entryUri(archive: String, path: String): Uri {
        val archiveUri = DeviceFixtures.uriFor(archive)
        val file = File(File(target.cacheDir, "fixtures"), archive)
        val raf = RandomAccessFile(file, "r")
        val entry = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, Locale.US).single { e -> e.path == path }
        }
        return ArchiveProvider.uriFor(target, archiveUri, entry)
    }

    /** What tapping [path] in the list starts. */
    private fun openEntry(archive: String, path: String): ActivityScenario<ViewerActivity> =
        ActivityScenario.launch(
            Intent()
                .setComponent(ComponentName(target, ViewerActivity.ENTRY_VIEWER))
                .setData(entryUri(archive, path))
        )

    private fun <T : Any> waitFor(what: String, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            probe()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    /** The list's rows, as their titles or their message, once there are any. */
    private fun rows(scenario: ActivityScenario<ViewerActivity>): List<String> = waitFor("rows") {
        var titles: List<String>? = null
        scenario.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(R.id.container)
            val rv = (0 until container.childCount).map { container.getChildAt(it) }
                .filterIsInstance<RecyclerView>().firstOrNull() ?: return@onActivity
            val adapter = rv.adapter ?: return@onActivity
            titles = (0 until adapter.itemCount).map { position ->
                val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
                adapter.bindViewHolder(holder, position)
                val row = holder.itemView
                (row.findViewById<TextView>(R.id.title) ?: row.findViewById(R.id.hintText))
                    .text.toString()
            }.takeIf { it.isNotEmpty() }
        }
        titles
    }

    @Test
    fun aZipOpensAsAListOfWhatIsInIt() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("archive.zip")).use {
            assertThat(rows(it))
                .containsExactly("nested", "photos", "private", "reports", "plain.txt").inOrder()
        }
    }

    /** Tapped through the list, to a file that is compressed, so it arrives through the pipe. */
    @Test
    fun aCompressedFileOpensFromTheList() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("archive.zip")).use {
            rows(it)
            onView(allOf(withId(R.id.title), withText("reports"))).perform(click())
            onView(allOf(withId(R.id.title), withText("notes.md"))).perform(click())
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), containsString("Willowmere site notes")))
        }
    }

    /** pdf.js reading a compressed PDF out of the pipe, every page of it. */
    @Test
    fun aCompressedPdfRendersEveryPage() {
        openEntry("archive.zip", "reports/six-pages.pdf").use { scenario ->
            val pages = waitFor("six pages") {
                WebViewProbe.text(scenario, "document.querySelectorAll('#pages .pg').length")
                    .takeIf { it == "6" }
            }
            assertThat(pages).isEqualTo("6")
        }
    }

    /** A stored photo is a window onto the archive, and the tiling view reads it from there. */
    @Test
    fun aStoredPhotoOpensInTheTilingView() {
        openEntry("archive.zip", "photos/tiny.png").use { scenario ->
            val ready = waitFor("the photo") {
                var ready: Boolean? = null
                scenario.onActivity { activity ->
                    val container = activity.findViewById<FrameLayout>(R.id.container)
                    val view = (0 until container.childCount).map { container.getChildAt(it) }
                        .filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
                    ready = view?.isReady?.takeIf { it }
                }
                ready
            }
            assertThat(ready).isTrue()
        }
    }

    /** A zip stored whole inside another is a window too, and so can be looked inside. */
    @Test
    fun aZipStoredInsideAZipOpensAsAList() {
        openEntry("archive.zip", "nested/stored.zip").use {
            assertThat(rows(it)).containsExactly("inside.txt")
        }
    }

    /**
     * And a file inside that one, whose URI names an archive that is itself one of these URIs:
     * the provider reads its own window to find the inner index, then the file.
     */
    @Test
    fun aFileInsideAZipInsideAZipOpens() {
        openEntry("archive.zip", "nested/stored.zip").use {
            rows(it)
            onView(allOf(withId(R.id.title), withText("inside.txt"))).perform(click())
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), containsString("A file inside a zip inside a zip")))
        }
    }

    /** A compressed one arrives as a pipe, which cannot be read out of order, and says so. */
    @Test
    fun aZipCompressedInsideAZipSaysHowToOpenIt() {
        openEntry("archive.zip", "nested/packed.zip").use {
            assertThat(rows(it))
                .containsExactly(target.getString(R.string.archive_not_seekable))
        }
    }

    /**
     * The code pages are Android's, built on ICU, and not the desktop JDK's the unit tests use,
     * so the names are read once more where they will be read for real.
     */
    @Test
    fun namesFromWindowsAndMacsReadAsNamesOnTheDevice() {
        mapOf(
            "names-gbk.zip" to listOf("季度报告", "照片"),
            "names-cp866.zip" to listOf("Документы", "Фото"),
            "names-sjis.zip" to listOf("資料"),
            "names-korean.zip" to listOf("문서", "사진"),
            "names-mac.zip" to listOf("Отчёт"),
        ).forEach { (fixture, folders) ->
            ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent(fixture)).use {
                assertThat(rows(it)).containsExactlyElementsIn(folders).inOrder()
            }
        }
    }

    /** Deflate64, which zlib and so Android cannot read, read by Gander's own inflater on ART. */
    @Test
    fun aDeflate64FileOpens() {
        openEntry("deflate64.zip", "short.txt").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), containsString("Plain text, opened by the text viewer")))
        }
    }

    /** Only Gander's own list can open one of these. See ViewerActivity.ENTRY_VIEWER. */
    @Test
    fun aFileInsideAZipIsRefusedFromOutsideTheList() {
        val intent = Intent(target, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(entryUri("archive.zip", "plain.txt"))
        ActivityScenario.launch<ViewerActivity>(intent).use {
            assertThat(it.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }
}
