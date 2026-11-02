package com.pdfreader.app.ui.reader

import android.app.Activity
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import com.pdfreader.app.utils.PreferenceManager
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Edge-swipe brightness.
 *
 * What makes it easier to control than the old strip:
 *  • Perceptual curve — the finger moves a "level", the screen gets level². Equal finger
 *    travel now feels like equal change, instead of the low end jumping.
 *  • Calm sensitivity — ~70% of the screen height covers the whole range, and it's relative
 *    (starts from the current value, never jumps to where the finger landed).
 *  • Below minimum — dragging past 0% keeps dimming with a black overlay, for night reading.
 *  • Clear feedback — a centred bar with %, a tick every 10% and a bump at min/max.
 *  • Saves once when you lift the finger (the old code wrote prefs on every move → stutter).
 */
class BrightnessController(
    private val activity: Activity,
    private val prefs: PreferenceManager,
    private val dimOverlay: View,
    private val hud: View,
    private val hudFill: View,
    private val hudValue: TextView,
    private val hudIcon: TextView
) {
    /** −MAX_DIM..0 = extra dim overlay, 0..1 = screen brightness level. */
    private var level = 0.5f
    private var lastStep = -1
    private val hideHud = Runnable {
        hud.animate().alpha(0f).setDuration(250).withEndAction { hud.visibility = View.GONE }.start()
    }

    init { restore() }

    /** Apply the saved value when the reader opens. */
    fun restore() {
        val dim = prefs.readerDim
        val b   = prefs.screenBrightness
        level = when {
            dim > 0f -> -dim
            b >= 0f  -> sqrt(b)
            else     -> systemLevel()
        }
        if (dim > 0f || b >= 0f) apply(level)
    }

    fun onStart() {
        if (prefs.screenBrightness < 0f && prefs.readerDim <= 0f) level = systemLevel()
        lastStep = step(level)
        hud.removeCallbacks(hideHud)
        hud.animate().cancel()
        hud.visibility = View.VISIBLE
        hud.animate().alpha(1f).setDuration(120).start()
        render()
    }

    /** [fraction] = finger travel as a fraction of view height, positive = up (brighter). */
    fun onDelta(fraction: Float) {
        val old = level
        level = (level + fraction * SENSITIVITY).coerceIn(-MAX_DIM, 1f)
        apply(level)
        render()
        // Haptics: bump at the ends, light tick every 10 %
        if ((level == 1f || level == -MAX_DIM) && old != level) {
            hud.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else {
            val s = step(level)
            if (s != lastStep) { lastStep = s; hud.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        }
    }

    fun onEnd() {
        // Persist once
        if (level >= 0f) { prefs.screenBrightness = toScreen(level); prefs.readerDim = 0f }
        else             { prefs.screenBrightness = MIN_SCREEN;      prefs.readerDim = -level }
        hud.postDelayed(hideHud, 900)
    }

    /** Return control to the phone's own brightness. */
    fun followSystem() {
        prefs.screenBrightness = -1f
        prefs.readerDim = 0f
        val lp = activity.window.attributes
        lp.screenBrightness = -1f
        activity.window.attributes = lp
        dimOverlay.visibility = View.GONE
        level = systemLevel()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun apply(lv: Float) {
        val lp = activity.window.attributes
        if (lv >= 0f) {
            lp.screenBrightness = toScreen(lv)
            dimOverlay.visibility = View.GONE
        } else {
            lp.screenBrightness = MIN_SCREEN
            dimOverlay.alpha = (-lv / MAX_DIM) * 0.65f
            dimOverlay.visibility = View.VISIBLE
        }
        activity.window.attributes = lp
    }

    private fun render() {
        val track = (hudFill.parent as View).height.toFloat()
        hudFill.pivotY = track
        if (level >= 0f) {
            hudFill.scaleY = level.coerceAtLeast(0.02f)
            hudFill.setBackgroundColor(Color.parseColor("#F5A623"))
            hudValue.text = "${(level * 100).toInt()}%"
            hudIcon.text  = if (level < 0.35f) "🔅" else "🔆"
        } else {
            hudFill.scaleY = 0.02f + (-level / MAX_DIM) * 0.3f
            hudFill.setBackgroundColor(Color.parseColor("#7E7AE0"))
            hudValue.text = "Dim ${((-level / MAX_DIM) * 100).toInt()}%"
            hudIcon.text  = "🌙"
        }
    }

    private fun step(lv: Float) = (lv * 10).toInt()
    private fun toScreen(lv: Float) = lv.pow(2).coerceIn(MIN_SCREEN, 1f)

    private fun systemLevel(): Float = try {
        val sys = android.provider.Settings.System.getInt(
            activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        sqrt(sys.coerceIn(0f, 1f))
    } catch (e: Exception) { 0.5f }

    companion object {
        private const val SENSITIVITY = 1.4f   // ≈70 % of screen height = full range
        private const val MAX_DIM     = 0.35f
        private const val MIN_SCREEN  = 0.01f
    }
}
