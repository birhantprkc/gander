package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

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
    private lateinit var provider: RenamingProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        provider = RenamingProvider.install()
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

    // ---------------------------------------------------------------
    // Dialogs, opened one after another as a reader opens them
    // ---------------------------------------------------------------

    private fun open(uri: Uri): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
    }

    /** A dialog as a reader sees it: the box, its title, and the words in its field if it has one. */
    private data class Seen(val box: Int?, val title: Int, val field: Int?)

    /** A dialog as a phone set to dark draws it. Read before any dialog is opened. */
    private fun asDarkPhone() = Seen(
        box = night.color(R.color.gander_surface_container_high),
        title = night.color(R.color.gander_on_surface),
        field = nightText,
    )

    private fun ViewerActivity.openRename(): AlertDialog {
        renameWorker = Executor { it.run() }
        findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_rename, 0)
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun AlertDialog.field(): EditText? {
        fun find(v: View): EditText? = when (v) {
            is EditText -> v
            is ViewGroup -> v.children.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        return find(window!!.decorView)
    }

    private fun AlertDialog.seen(): Seen {
        // Material draws the box as a shape inside an inset, and sets that as the window's background
        val box = (window!!.decorView.background as? InsetDrawable)?.drawable as? MaterialShapeDrawable
        return Seen(
            box = box?.fillColor?.defaultColor,
            title = findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.currentTextColor,
            field = field()?.currentTextColor,
        )
    }

    private fun AlertDialog.close() {
        cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** What the tests below hold night mode to. */
    @Test
    @Config(qualifiers = "night")
    fun onAPhoneSetToDarkTheRenameBoxIsDark() {
        val dark = asDarkPhone()
        assertThat(open(provider.add("six-pages.pdf")).openRename().seen()).isEqualTo(dark)
    }

    @Test
    fun withNightModeOffTheRenameBoxIsAsThePhoneHasIt() {
        val light = Seen(
            box = context.color(R.color.gander_surface_container_high),
            title = context.color(R.color.gander_on_surface),
            field = textColour(ContextThemeWrapper(context, R.style.Theme_Gander)),
        )
        assertThat(open(provider.add("six-pages.pdf")).openRename().seen()).isEqualTo(light)
    }

    /**
     * The first came out dark under a dim title and every one after it light, the field light on
     * light, because making a dialog put the night theme's resources back to day.
     */
    @Test
    fun everyDialogOverAPageInNightModeIsDarkNotOnlyTheFirst() {
        Settings.setNight(context, true)
        val dark = asDarkPhone()
        val viewer = open(provider.add("six-pages.pdf"))

        repeat(3) {
            val box = viewer.openRename()
            assertThat(box.seen()).isEqualTo(dark)
            box.close()
        }
        // And none of it reached the viewer's own resources, which stay the phone's
        assertThat(viewer.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .isEqualTo(Configuration.UI_MODE_NIGHT_NO)
    }

    /** A question asked over the box is made and shown in one call, and is dark as well. */
    @Test
    fun aQuestionAskedOverTheBoxIsDarkToo() {
        Settings.setNight(context, true)
        val dark = asDarkPhone()
        val box = open(provider.add("six-pages.pdf")).openRename()
        box.field()!!.setText("six-pages.txt")
        box.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val question = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(question).isNotSameInstanceAs(box)
        assertThat(question.seen()).isEqualTo(dark.copy(field = null))
    }

    /** The night theme's resources are shared across the process, so a viewer opened later read them too. */
    @Test
    fun aDialogAtNightLeavesTheNextViewerDarkToo() {
        Settings.setNight(context, true)
        val before = open("six-pages.pdf").parts()
        val first = open(provider.add("six-pages.pdf"))
        first.openRename().close()

        assertThat(open("six-pages.pdf").parts()).isEqualTo(before)
        // Still open, as it was on the phone, holding the resources it shares with the one above
        assertThat(first.isDestroyed).isFalse()
    }
}
