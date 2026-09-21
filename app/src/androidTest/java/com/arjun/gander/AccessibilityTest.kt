package com.arjun.gander

import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.any
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Google's accessibility checks, run over the screens as they are drawn.
 *
 * This is the same framework behind Play's pre-launch accessibility report,
 * which is where the badge contrast problems in 1.13 were found. Catching one
 * here costs a nightly run; catching one there costs a release.
 *
 * The checks hook Espresso's view actions rather than running on demand, so
 * this class exists to perform real actions on the native views. The other
 * device tests read the WebView, which the framework does not see into, so
 * enabling it there would look like coverage and be none.
 *
 * An assertion is not an action. Until 2026-09-19 every test here ended in
 * check(matches(isDisplayed())), which never runs the checks, and all four
 * went on passing with a label stripped off each of their screens. So each
 * test now ends in [checkFrom], and a failure here means something again.
 *
 * They cover the views Gander draws. The contents of a viewer page are the
 * page's business, and are checked in tests/viewer.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AccessibilityTest {

    @get:Rule
    val retry = RetryRule()

    companion object {
        @BeforeClass
        @JvmStatic
        fun enableChecks() {
            // Nothing suppressed. An earlier version excused touch target
            // sizes on the assumption that library-drawn controls would fail
            // it; they do not, and a suppression for a check that passes only
            // hides the day it stops.
            AccessibilityChecks.enable().setRunChecksFromRootView(true)
        }
    }

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    /**
     * Runs the checks over the whole window [view] is in.
     *
     * An action that does nothing, wrapped the way Espresso wraps its own, which
     * is what runs the checks before it. Every real action changes something, and
     * the nearest to nothing, closing the keyboard, times out in a dialog on
     * Android 16. From the root, so it is the window being checked, not one view.
     */
    private fun checkFrom(view: ViewInteraction) {
        view.perform(ViewActions.actionWithAssertions(object : ViewAction {
            override fun getConstraints(): Matcher<View> = any(View::class.java)
            override fun getDescription() = "run the accessibility checks"
            override fun perform(uiController: UiController, view: View) = Unit
        }))
    }

    /**
     * Opening a document and using the toolbar runs every check over the
     * viewer's own views: the title, the menu items, the page indicator.
     */
    @Test
    fun theViewerToolbarPassesTheAccessibilityChecks() {
        ActivityScenario.launch<ViewerActivity>(
            DeviceFixtures.viewIntent("six-pages.pdf")
        ).use {
            checkFrom(onView(withId(R.id.toolbar)))
        }
    }

    /** The screen with the find bar open, which is a row of controls of its own. */
    @Test
    fun theSearchBarPassesTheAccessibilityChecks() {
        ActivityScenario.launch<ViewerActivity>(
            DeviceFixtures.viewIntent("plain.txt")
        ).use {
            Thread.sleep(1500)
            onView(withId(R.id.action_search)).perform(click())
            checkFrom(onView(withId(R.id.searchInput)))
        }
    }

    /**
     * The first-run screen, which is where the badge colours live. Every tile
     * is a white label on a coloured ground, and four of them failed this
     * check before 1.13; the fixes are recorded as measured ratios in the
     * KDoc on the palette, and pinned arithmetically in ListingTest.
     *
     * This is the home screen a test can reach. Listing real files needs a
     * persisted URI grant, and only the system file picker can hand one out:
     * a grant the app makes to itself through its own FileProvider is not
     * persistable, so nothing a test opens ever lands in Recents. The rows it
     * would list are checked anyway, in the list of a zip, which draws them.
     */
    @Test
    fun theFirstRunScreenPassesTheAccessibilityChecks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500)
            checkFrom(onView(withId(R.id.formatGrid)))
        }
    }

    /** And the About dialog, which is the app's only other native surface. */
    @Test
    fun theAboutDialogPassesTheAccessibilityChecks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1000)
            var opened = false
            scenario.onActivity { activity ->
                opened = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                    .menu.performIdentifierAction(R.id.action_about, 0)
            }
            assertThat(opened).isTrue()
            Thread.sleep(1000)
            checkFrom(onView(withId(R.id.aboutVersion)).inRoot(isDialog()))
        }
    }

    /**
     * The list of what is inside a zip, issue #30. It is drawn with the home
     * screen's rows, so this is also the only check those rows get: badge,
     * name, size and date, and the note on a file that will not open.
     */
    @Test
    fun theZipListPassesTheAccessibilityChecks() {
        ActivityScenario.launch<ViewerActivity>(
            DeviceFixtures.viewIntent("archive.zip")
        ).use { scenario ->
            awaitRows(scenario)
            // Checked once at the top, as the tap runs, and again inside a folder
            // whose files are the ones that say why they will not open
            onView(allOf(withId(R.id.title), withText("private"))).perform(click())
            checkFrom(onView(allOf(withId(R.id.title), withText("locked.txt"))))
        }
    }

    /** The box that asks for the password of a file in a zip, a dialog of Gander's own. */
    @Test
    fun theZipPasswordBoxPassesTheAccessibilityChecks() {
        ActivityScenario.launch<ViewerActivity>(
            DeviceFixtures.viewIntent("locked.zip")
        ).use { scenario ->
            awaitRows(scenario)
            onView(allOf(withId(R.id.title), withText("zipcrypto.txt"))).perform(click())
            checkFrom(onView(withText(R.string.password_title)).inRoot(isDialog()))
        }
    }

    /** And the list of code pages a zip's names can be read in, which is long enough to scroll. */
    @Test
    fun theNameEncodingListPassesTheAccessibilityChecks() {
        ActivityScenario.launch<ViewerActivity>(
            DeviceFixtures.viewIntent("names-gbk.zip")
        ).use { scenario ->
            awaitRows(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                    .menu.performIdentifierAction(R.id.action_name_encoding, 0)
            }
            checkFrom(onView(withText(R.string.name_encoding)).inRoot(isDialog()))
        }
    }

    /**
     * The box that asks for a file's new name, which comes up with the keyboard. Rename is only
     * offered on a file a document provider serves, and a test is handed none, so the item is
     * asked for directly: the box is the same whatever file it is for.
     */
    @Test
    fun theRenameBoxPassesTheAccessibilityChecks() {
        ActivityScenario.launch<ViewerActivity>(
            DeviceFixtures.viewIntent("plain.txt")
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                    .menu.performIdentifierAction(R.id.action_rename, 0)
            }
            checkFrom(onView(withHint(R.string.file_name)).inRoot(isDialog()))
        }
    }

    /** Waits for the list to be drawn: the index is read off the main thread. */
    private fun awaitRows(scenario: ActivityScenario<ViewerActivity>) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var drawn = false
            scenario.onActivity { activity ->
                val container = activity.findViewById<FrameLayout>(R.id.container)
                drawn = (0 until container.childCount).map<Int, View> { container.getChildAt(it) }
                    .filterIsInstance<RecyclerView>()
                    .any { (it.adapter?.itemCount ?: 0) > 0 && it.childCount > 0 }
            }
            if (drawn) return
            Thread.sleep(50)
        }
        throw AssertionError("the zip's list never appeared")
    }
}
