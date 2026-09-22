package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The title bar and the phone's bars over a video, issue #33.
 *
 * The videos here are the tone under a video's name. Which screen a file gets is decided from its
 * name, and a sound plays under Robolectric where a picture would need a decoder it has not got.
 */
@RunWith(AndroidJUnit4::class)
class VideoChromeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
    }

    private fun open(uri: Uri): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
    }

    private fun video(): ActivityController<ViewerActivity> =
        open(FixtureProvider.uriNamed("tone.wav", "clip.mp4"))

    private val ViewerActivity.root get() = findViewById<ViewGroup>(R.id.root)
    private val ViewerActivity.toolbar get() = findViewById<MaterialToolbar>(R.id.toolbar)
    private val ViewerActivity.saveProgress get() = findViewById<View>(R.id.saveProgress)
    private val ViewerActivity.stage get() = findViewById<View>(R.id.container).parent as FrameLayout
    private val ViewerActivity.player
        get() = findViewById<FrameLayout>(R.id.container).children.filterIsInstance<PlayerView>().single()
    private val ViewerActivity.fullScreenButton
        get() = player.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)

    /** What holds the title bar over a video. */
    private val ViewerActivity.top get() = toolbar.parent as View

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    // ---------------------------------------------------------------
    // Where things are
    // ---------------------------------------------------------------

    @Test
    fun aVideosTitleBarFloatsOverThePictureWithTheSaveBarUnderIt() {
        val viewer = video().get()

        assertThat(viewer.top.parent).isSameInstanceAs(viewer.stage)
        assertThat(viewer.saveProgress.parent).isSameInstanceAs(viewer.top)
        // Its surface carried up behind the clock, on the strip rather than the bar
        assertThat(viewer.top.background).isNotNull()
        assertThat(viewer.toolbar.background).isNull()
    }

    @Test
    fun aSoundKeepsItsTitleBarAboveIt() {
        val viewer = open(FixtureProvider.uriFor("tone.wav")).get()
        assertThat(viewer.toolbar.parent).isSameInstanceAs(viewer.root)
        // Its transport is a layout of Gander's own, with no full screen button in it to show
        val button: View? = viewer.player.findViewById(androidx.media3.ui.R.id.exo_fullscreen)
        assertThat(button?.visibility ?: View.GONE).isNotEqualTo(View.VISIBLE)
    }

    private val statusBar = Insets.of(0, 63, 0, 0)
    private val navigationBar = Insets.of(0, 0, 0, 126)

    /** The phone's bars, shown or hidden: where they can be stays the same either way. */
    private fun bars(shown: Boolean): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), if (shown) statusBar else Insets.NONE)
        .setInsets(WindowInsetsCompat.Type.navigationBars(), if (shown) navigationBar else Insets.NONE)
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), statusBar)
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigationBar)
        .setVisible(WindowInsetsCompat.Type.systemBars(), shown)
        .build()

    private fun View.padding() = listOf(paddingLeft, paddingTop, paddingRight, paddingBottom)

    private fun ViewerActivity.paddings(): List<List<Int>> = listOf(
        root.padding(),
        top.padding(),
        player.findViewById<View>(androidx.media3.ui.R.id.exo_controller).padding(),
    )

    @Test
    fun thePictureFillsTheScreenAndNothingMovesWhenTheBarsGo() {
        val viewer = video().get()

        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars(shown = true))
        val shown = viewer.paddings()
        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars(shown = false))

        assertThat(shown).isEqualTo(listOf(
            listOf(0, 0, 0, 0),
            // The title bar's strip reaches behind the clock
            listOf(0, 63, 0, 0),
            // And the controls keep clear of both bars
            listOf(0, 63, 0, 126),
        ))
        assertThat(viewer.paddings()).isEqualTo(shown)
    }

    // ---------------------------------------------------------------
    // Coming and going
    // ---------------------------------------------------------------

    @Test
    fun theTitleBarGoesWithTheControlsAndComesBackWithThem() {
        val viewer = video().get()

        viewer.player.hideController()
        settle()
        assertThat(viewer.top.visibility).isEqualTo(View.INVISIBLE)
        assertThat(viewer.top.alpha).isEqualTo(0f)

        viewer.player.showController()
        // On its way in as they start in, rather than once they are all the way up
        assertThat(viewer.player.isControllerFullyVisible).isFalse()
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)
        settle()
        assertThat(viewer.top.alpha).isEqualTo(1f)
    }

    /**
     * The player hides its buttons after the timeout and its line of progress two seconds later,
     * and says it is hidden only then. The title bar goes with the buttons.
     */
    @Test
    fun theTitleBarGoesWithThePlayersButtonsNotItsLineOfProgress() {
        val viewer = video().get()
        // Timed out whatever state the tone's playback has reached here: shown to stay up, the
        // controls would wait for an ended or paused player to be touched
        viewer.player.controllerAutoShow = false
        viewer.player.showController()
        settle()
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500))
        // The line of progress is still there, and nothing else
        assertThat(viewer.findViewById<View>(androidx.media3.ui.R.id.exo_controller).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat(viewer.player.isControllerFullyVisible).isFalse()
        assertThat(viewer.top.visibility).isEqualTo(View.INVISIBLE)
    }

    /**
     * The player's own race, which would keep the title bar up with its controls: shown to stay
     * while the video was being prepared, and told to play before they were fully in, the controls
     * never counted down to hide. Here they are made to stay that way directly.
     */
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun controlsShownToStayWhileTheVideoPlaysStillGo() {
        val viewer = video().get()
        viewer.player.controllerAutoShow = false
        viewer.player.hideController()
        settle()

        val controls = viewer.player.findViewById<androidx.media3.ui.PlayerControlView>(
            androidx.media3.ui.R.id.exo_controller
        )
        controls.showTimeoutMs = 0
        controls.show()
        settle()
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500))
        assertThat(viewer.player.isControllerFullyVisible).isFalse()
        assertThat(viewer.top.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun underAScreenReaderTheTitleBarStays() {
        val viewer = video().get()
        shadowOf(viewer.getSystemService(AccessibilityManager::class.java))
            .setTouchExplorationEnabled(true)

        viewer.player.hideController()
        settle()

        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)
        assertThat(viewer.top.alpha).isEqualTo(1f)
    }

    // ---------------------------------------------------------------
    // Full screen
    // ---------------------------------------------------------------

    @Test
    fun fullScreenHoldsTheScreenTheVideosWayRoundAndGivesItBack() {
        val viewer = video().get()
        assertThat(viewer.fullScreenButton.visibility).isEqualTo(View.VISIBLE)

        viewer.fullScreenButton.performClick()
        // The tone has no picture and so no size, and a video of no known size goes landscape
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

        viewer.fullScreenButton.performClick()
        assertThat(viewer.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    @Test
    fun aVideoInFullScreenStaysSoWhenTheViewerIsMadeAgain() {
        val controller = video()
        controller.get().fullScreenButton.performClick()

        controller.recreate()

        assertThat(controller.get().requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        // And the button knows it, so the next press gives the screen back
        controller.get().fullScreenButton.performClick()
        assertThat(controller.get().requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    @Test
    fun aVideoGoesFullScreenTheWayRoundItWasFilmed() {
        val portrait = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        val landscape = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // A phone held upright
        assertThat(fullScreenOrientation(VideoSize(1080, 1920))).isEqualTo(portrait)
        assertThat(fullScreenOrientation(VideoSize(1920, 1080))).isEqualTo(landscape)
        // Stored narrow in pixels drawn twice as wide, so wide on screen
        assertThat(fullScreenOrientation(VideoSize(480, 640, 2f))).isEqualTo(landscape)
        assertThat(fullScreenOrientation(VideoSize(640, 640))).isEqualTo(landscape)
        assertThat(fullScreenOrientation(VideoSize.UNKNOWN)).isEqualTo(landscape)
    }

    // ---------------------------------------------------------------
    // A video that will not play
    // ---------------------------------------------------------------

    /** Text under a video's name, which the player cannot read and gives up on. */
    private fun unplayable(): ViewerActivity {
        val viewer = open(FixtureProvider.uriNamed("plain.txt", "broken.mp4")).get()
        // The player reads on a thread of its own and reports back on the main one
        repeat(100) {
            if (viewer.findViewById<FrameLayout>(R.id.container).children.any { it is WebView }) {
                return viewer
            }
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        error("the player never gave up on broken.mp4")
    }

    @Test
    fun aVideoThatWillNotPlayGetsTheViewerBackAsEveryOtherFileHasIt() {
        val sound = open(FixtureProvider.uriFor("tone.wav")).get()
        val viewer = unplayable()

        assertThat(viewer.toolbar.parent).isSameInstanceAs(viewer.root)
        assertThat(viewer.root.indexOfChild(viewer.toolbar)).isEqualTo(sound.root.indexOfChild(sound.toolbar))
        assertThat(viewer.root.indexOfChild(viewer.saveProgress))
            .isEqualTo(sound.root.indexOfChild(sound.saveProgress))
        assertThat(viewer.toolbar.background).isNotNull()
        assertThat(viewer.toolbar.elevation).isEqualTo(sound.toolbar.elevation)
        assertThat(viewer.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        // And it keeps clear of the bars again
        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars(shown = true))
        assertThat(viewer.root.padding()).isEqualTo(listOf(0, 63, 0, 126))
    }

    /** Below Android 15 the window paints the bars itself, which over a video it must not. */
    @Test
    @Config(sdk = [34])
    @Suppress("DEPRECATION")
    fun belowAndroid15TheBarsAreSeeThroughOverAVideoAndPutBackAfter() {
        val sound = open(FixtureProvider.uriFor("tone.wav")).get()
        assertThat(sound.window.statusBarColor).isNotEqualTo(Color.TRANSPARENT)

        assertThat(video().get().window.statusBarColor).isEqualTo(Color.TRANSPARENT)
        assertThat(unplayable().window.statusBarColor).isEqualTo(sound.window.statusBarColor)
    }
}
