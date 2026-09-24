package com.arjun.gander

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.hamcrest.Matchers.containsString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Find in page through the search bar, for a format whose page searches its own text with
 * find.js. What the page does is covered in tests/viewer/test_find.py; what only a device shows
 * is the channel ViewerActivity opens to that page and the count coming back to the bar.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class FindInPageDeviceTest {

    @get:Rule
    val retry = RetryRule()

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    /**
     * budget.xlsx has "sheet marker" on its second and third sheets and not on the first, the
     * one drawn. Chromium's own find, which a workbook was searched with before, counted none.
     */
    @Test
    fun aMatchOnASheetThatIsNotDrawnIsCountedInTheBar() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("budget.xlsx")).use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#sheet"))
                .check(webMatches(getText(), containsString("Surveying")))
            onView(withId(R.id.action_search)).perform(click())
            onView(withId(R.id.searchInput)).perform(typeText("sheet marker"))
            // The count comes back over the channel from the page, which Espresso does not wait
            // for, so it is asked for again until it arrives
            eventually { onView(withId(R.id.searchCount)).check(matches(withText("1/2"))) }
        }
    }

    private fun eventually(timeoutMs: Long = 15_000, check: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                check()
                return
            } catch (e: Throwable) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(250)
            }
        }
    }
}
