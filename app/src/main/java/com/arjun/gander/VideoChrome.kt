package com.arjun.gander

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * The title bar and the phone's bars over a video: they float over the picture and come and go
 * with the player's own controls. Issue #33.
 *
 * Everywhere else the title bar sits above the document and the viewer keeps clear of the phone's
 * bars, which suits a page and not a film. In landscape the three of them took about a third of
 * the screen's height, and hiding any of them where they stood would have resized the picture
 * every time the controls came and went. So for a video the picture fills the screen, the title
 * bar is lifted over its top edge with its own colour carried up behind the clock, the controls
 * keep clear of wherever the phone's bars can be, and nothing moves when the bars do. One tap
 * brings everything back, and it all goes again with the controls.
 *
 * Under a screen reader nothing goes. A view that has faded out cannot be reached by swiping, and
 * the title bar holds Back and the menu; the page readout stays up there for the same reason.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoChrome(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val screenReader: () -> Boolean,
    /** How every other viewer keeps clear of the phone's bars, put back by [land]. */
    private val keepClear: (View) -> Unit,
) {
    private val root: ViewGroup = activity.findViewById(R.id.root)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.toolbar)
    private val saveProgress: View = activity.findViewById(R.id.saveProgress)
    // The frame the picture is in, with the things that float over a document
    private val stage = activity.findViewById<View>(R.id.container).parent as FrameLayout
    private val controls: View? = playerView.findViewById(androidx.media3.ui.R.id.exo_controller)

    /** The title bar and the save bar under it, over the top of the picture. */
    private val top = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private val window = activity.window
    private val bars = WindowCompat.getInsetsController(window, window.decorView)

    // As float() found them, for land()
    private val toolbarAt = root.indexOfChild(toolbar)
    private val saveProgressAt = root.indexOfChild(saveProgress)
    private val toolbarBackground = toolbar.background
    private val toolbarElevation = toolbar.elevation
    private val darkStatusIcons = bars.isAppearanceLightStatusBars
    private val darkNavigationIcons = bars.isAppearanceLightNavigationBars
    private val barsBehaviour = bars.systemBarsBehavior
    @Suppress("DEPRECATION")
    private val statusBarColour = window.statusBarColor
    @Suppress("DEPRECATION")
    private val navigationBarColour = window.navigationBarColor

    /** How the screen is held for full screen, or null while the phone turns it. */
    var held: Int? = null
        private set

    fun float() {
        root.removeView(toolbar)
        root.removeView(saveProgress)
        // The toolbar's own surface moves out to the strip holding it, so it reaches up behind the
        // clock, and the shadow goes with it to that strip's lower edge. Let go of first, since a
        // view letting go of a drawable clears whatever the drawable has been given to since.
        toolbar.background = null
        toolbar.elevation = 0f
        top.background = toolbarBackground
        top.elevation = toolbarElevation
        top.addView(toolbar)
        top.addView(saveProgress)
        stage.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        // Below Android 15 the window keeps clear of the bars itself unless told not to, and
        // paints them colours of its own over whatever is behind
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }
        // Pale over a picture, whatever the phone's theme would draw them in
        bars.isAppearanceLightNavigationBars = false
        // Hidden, they come back for a swipe in from the edge and go again by themselves, and a
        // tap still reaches the player
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // Where the bars can be rather than where they are, so their coming and going moves
            // nothing
            val clear = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.setPadding(0, 0, 0, 0)
            top.setPadding(clear.left, clear.top, clear.right, 0)
            controls?.setPadding(clear.left, clear.top, clear.right, clear.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> follow(visibility == View.VISIBLE) }
        )
        playerView.setFullscreenButtonClickListener { on ->
            hold(if (on) fullScreenOrientation(playerView.player?.videoSize ?: VideoSize.UNKNOWN) else null)
        }
    }

    /**
     * Holds the screen at [orientation] for full screen, or gives it back to the phone for null.
     * Called with what [held] was when the viewer is made again, so a video in full screen stays so.
     */
    fun hold(orientation: Int?) {
        held = orientation
        playerView.setFullscreenButtonState(orientation != null)
        activity.requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private var controlsWereGone = false

    /**
     * Along with the player's controls, which go in two steps: the buttons after the timeout, and
     * two seconds later the line of progress left at the bottom. They say they are hidden only at
     * the second, but they report every step on the way and are fully up only before the first.
     * So everything goes as soon as they stop being fully up, and comes back the moment they
     * reappear from nothing. From the line of progress, a tap brings the title bar back once the
     * controls are fully up, since on the way in and on the way out look alike from here.
     */
    private fun follow(controlsVisible: Boolean) {
        val fullyUp = playerView.isControllerFullyVisible
        if (fullyUp) {
            // The player leaves its controls up for good if it was told to play while they were
            // still coming in: prepared first, they came up to stay until playback began, and it
            // counts the time to hide them only if they are fully up when it begins. Asked again
            // now that they are, it counts from here, or keeps them up for a paused or finished
            // video, whichever the player is by now.
            playerView.showController()
        }
        show(
            when {
                !controlsVisible -> false
                fullyUp -> true
                else -> controlsWereGone
            }
        )
        controlsWereGone = !controlsVisible
    }

    // Up when the video opens, as the controls are
    private var shown = true

    /**
     * The picture is dark, so a bar that comes back over it by a swipe has pale icons until the
     * title bar is behind them again.
     */
    private fun show(visible: Boolean) {
        val up = visible || screenReader()
        // Asked at every step the controls take, and a fade begun again would never finish
        if (up == shown) return
        shown = up
        top.animate().cancel()
        if (up) {
            top.visibility = View.VISIBLE
            top.animate().alpha(1f).setDuration(FADE_MS).start()
            bars.isAppearanceLightStatusBars = darkStatusIcons
            bars.show(WindowInsetsCompat.Type.systemBars())
        } else {
            top.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction { top.visibility = View.INVISIBLE }.start()
            bars.isAppearanceLightStatusBars = false
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The viewer as every other file has it, for the card that stands in for a video that will not play. */
    fun land() {
        top.animate().cancel()
        stage.removeView(top)
        top.removeAllViews()
        top.background = null
        toolbar.background = toolbarBackground
        toolbar.elevation = toolbarElevation
        root.addView(toolbar, toolbarAt)
        root.addView(saveProgress, saveProgressAt)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            @Suppress("DEPRECATION")
            window.statusBarColor = statusBarColour
            @Suppress("DEPRECATION")
            window.navigationBarColor = navigationBarColour
        }
        bars.show(WindowInsetsCompat.Type.systemBars())
        bars.isAppearanceLightStatusBars = darkStatusIcons
        bars.isAppearanceLightNavigationBars = darkNavigationIcons
        bars.systemBarsBehavior = barsBehaviour
        keepClear(root)
        ViewCompat.requestApplyInsets(root)
        held = null
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private companion object {
        /** The player's own, for its controls. */
        const val FADE_MS = 250L
    }
}

/**
 * The way round a video fills the screen: landscape, unless it is taller than it is wide, as a
 * phone held upright films. Landscape too while the player does not yet know its size.
 */
internal fun fullScreenOrientation(size: VideoSize): Int =
    if (size.height > size.width * size.pixelWidthHeightRatio) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
