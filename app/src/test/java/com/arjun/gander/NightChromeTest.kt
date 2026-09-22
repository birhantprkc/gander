package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The viewer around a PDF in night mode, on a phone set to light and on one set to dark.
 *
 * The WebView is Robolectric's shadow and cannot open a message channel, so the night mode item
 * stays hidden here and the switch is driven through [NightChrome] itself. Opening dark is tested
 * through the viewer, from the stored setting, the way a real open reads it.
 */
@RunWith(AndroidJUnit4::class)
class NightChromeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
        Settings.setNight(context, false)
    }

    @After
    fun tearDown() {
        Settings.setNight(context, false)
    }

    private fun open(fixture: String): ViewerActivity {
        val uri = FixtureProvider.uriFor(fixture)
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
    }

    /** The app's colours as a phone set to dark has them. */
    private val night: Context by lazy {
        val conf = Configuration(context.resources.configuration)
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_YES
        context.createConfigurationContext(conf)
    }

    private fun Context.color(id: Int) = ContextCompat.getColor(this, id)

    /**
     * Text as a phone set to dark draws it. Not gander_on_surface: Material's night theme names
     * its own grey for android:textColorPrimary rather than pointing it at the role, so that is
     * what the toolbar's title and the search box already show on a dark phone.
     */
    private val nightText: Int by lazy { textColour(ContextThemeWrapper(night, R.style.Theme_Gander)) }

    private fun textColour(themed: Context): Int {
        val values = themed.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        try {
            return values.getColorStateList(0)!!.defaultColor
        } finally {
            values.recycle()
        }
    }

    /** Each part NightChrome paints, as the colour a reader sees it in. */
    private data class Parts(
        val toolbar: Int?,
        val title: Int,
        val behindTheBars: Int?,
        val darkStatusIcons: Boolean,
        val searchBar: Int,
        val searchText: Int,
        val searchCount: Int,
        val menu: Int,
        val menuText: Int,
    )

    private fun ViewerActivity.parts(): Parts {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val title = toolbar.children.filterIsInstance<TextView>().first { it.text == toolbar.title }
        // What shows around the root's padding: its own background, or the window's through it
        val root = findViewById<View>(R.id.root)
        val behind = (root.background as? ColorDrawable)?.color
            ?: (window.decorView.background as? ColorDrawable)?.color
        return Parts(
            toolbar = (toolbar.background as? MaterialShapeDrawable)?.fillColor?.defaultColor,
            title = title.currentTextColor,
            behindTheBars = behind,
            darkStatusIcons = WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars,
            searchBar = (findViewById<View>(R.id.searchBar).background as ColorDrawable).color,
            searchText = findViewById<TextView>(R.id.searchInput).currentTextColor,
            searchCount = findViewById<TextView>(R.id.searchCount).currentTextColor,
            menu = MaterialColors.getColor(
                toolbar.context, com.google.android.material.R.attr.colorSurfaceContainer, "menu"
            ),
            menuText = textColour(toolbar.context),
        )
    }

    @Test
    fun aPdfOpenedInNightModeOpensWithTheViewerDark() {
        Settings.setNight(context, true)
        val parts = open("six-pages.pdf").parts()

        assertThat(parts.toolbar).isEqualTo(night.color(R.color.gander_surface))
        assertThat(parts.title).isEqualTo(nightText)
        assertThat(parts.behindTheBars).isEqualTo(night.color(R.color.gander_surface))
        assertThat(parts.darkStatusIcons).isFalse()
        assertThat(parts.searchBar).isEqualTo(night.color(R.color.gander_surface))
        assertThat(parts.searchText).isEqualTo(nightText)
        assertThat(parts.searchCount).isEqualTo(night.color(R.color.gander_on_surface_variant))
        // The menu's roles come from the five named night colours, so this also holds them
        // to the night palette they were copied out of
        assertThat(parts.menu).isEqualTo(night.color(R.color.gander_surface_container))
        assertThat(parts.menuText).isEqualTo(nightText)
    }

    @Test
    fun withNightModeOffAPdfOpensAsThePhoneHasIt() {
        val parts = open("six-pages.pdf").parts()

        assertThat(parts.toolbar).isEqualTo(context.color(R.color.gander_surface))
        assertThat(parts.behindTheBars).isEqualTo(context.color(R.color.gander_surface))
        assertThat(parts.darkStatusIcons).isTrue()
        assertThat(parts.searchBar).isEqualTo(context.color(R.color.gander_surface))
        assertThat(parts.menu).isEqualTo(context.color(R.color.gander_surface_container))
    }

    @Test
    fun nightModeLeavesTheOtherFormatsAsThePhoneHasThem() {
        Settings.setNight(context, true)
        for (fixture in listOf("plain.txt", "report.docx", "notes.md", "tiny.png")) {
            val parts = open(fixture).parts()
            assertThat(parts.behindTheBars).isEqualTo(context.color(R.color.gander_surface))
            assertThat(parts.searchBar).isEqualTo(context.color(R.color.gander_surface))
            assertThat(parts.menu).isEqualTo(context.color(R.color.gander_surface_container))
        }
    }

    @Test
    fun turningItOffPutsEveryPartBackAsItWas() {
        val viewer = open("six-pages.pdf")
        val asPhone = viewer.parts()
        val chrome = NightChrome(viewer)

        chrome.show(true)
        val atNight = viewer.parts()
        chrome.show(false)

        assertThat(atNight.searchBar).isNotEqualTo(asPhone.searchBar)
        assertThat(viewer.parts()).isEqualTo(asPhone)
    }

    @Test
    @Config(qualifiers = "night")
    fun onAPhoneSetToDarkNightModeChangesNothingAroundThePage() {
        val viewer = open("six-pages.pdf")
        val asPhone = viewer.parts()

        NightChrome(viewer).show(true)

        assertThat(viewer.parts()).isEqualTo(asPhone)
    }

    @Test
    fun dialogsOverAPageInNightModeAreBuiltOnTheNightTheme() {
        val viewer = open("six-pages.pdf")
        val chrome = NightChrome(viewer)
        assertThat(chrome.dialogs).isSameInstanceAs(viewer)

        chrome.show(true)

        val surface = MaterialColors.getColor(
            chrome.dialogs, com.google.android.material.R.attr.colorSurface, "dialog"
        )
        assertThat(surface).isEqualTo(night.color(R.color.gander_surface))
    }
}
