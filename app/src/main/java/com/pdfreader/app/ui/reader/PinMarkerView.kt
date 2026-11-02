package com.pdfreader.app.ui.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * "Where I stopped" marker, drawn over the page. Never takes touches.
 *
 * Styles
 *  • RIBBON    – a silk bookmark ribbon hanging from the top of the page; its forked tail
 *                points at the saved line, with a dashed guide across the page.
 *  • LINE      – a glowing reading line with a dot at the edge (minimal).
 *  • HIGHLIGHT – a soft amber band over the saved lines, like a highlighter pass.
 *
 * Animations
 *  • drop()   – new pin: ribbon unrolls from the top with a little bounce and sway,
 *               the guide sweeps across the page and settles to a faint trace.
 *  • appear() – page with a pin comes into view: quick fade + slide.
 *  • pulse()  – after "Jump to saved position": a ripple at the marker so the eye finds it.
 *  • retract()– removing: ribbon rolls back up, guide fades out.
 */
class PinMarkerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Style { RIBBON, LINE, HIGHLIGHT }

    var style = Style.RIBBON
        set(v) { field = v; invalidate() }
    /** Ribbon hangs on the right edge (Arabic books) or left edge. */
    var onRightEdge = true
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private var targetY = 0f
    private var shown = false

    // animated state
    private var grow = 0f        // 0..1 ribbon length / band width
    private var sway = 0f        // degrees
    private var guideAlpha = 0f  // 0..1
    private var sweep = 1f       // 0..1 guide sweep progress
    private var pulse = -1f      // 0..1 ripple progress, <0 = off
    private var alphaAll = 1f
    private var anim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null

    private val accent     = Color.parseColor("#F5A623")
    private val accentDark = Color.parseColor("#C47F0E")
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val stitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1.2f * d
        pathEffect = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 2f * d
        pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API
    // ═════════════════════════════════════════════════════════════════════════

    /** New pin placed at [y]: full unroll animation. */
    fun drop(y: Float) {
        targetY = y; shown = true; visibility = VISIBLE; alphaAll = 1f
        run(900L, OvershootInterpolator(1.1f)) { f, t ->
            grow = f
            // damped sway after the ribbon lands
            sway = if (t > 0.35f) (6f * sin((t - 0.35f) * 4f * PI).toFloat() * exp(-(t - 0.35f) * 5f).toFloat()) else 0f
            sweep = ((t - 0.25f) / 0.45f).coerceIn(0f, 1f)
            guideAlpha = if (t < 0.7f) 0.9f else 0.9f - (t - 0.7f) / 0.3f * 0.6f   // settles to 0.3
        }
    }

    /** The pinned page scrolled into view: quiet entrance. */
    fun appear(y: Float) {
        targetY = y
        if (shown && visibility == VISIBLE) { invalidate(); return }
        shown = true; visibility = VISIBLE
        sway = 0f; sweep = 1f
        run(260L, DecelerateInterpolator()) { f, _ -> grow = f; alphaAll = f; guideAlpha = 0.3f * f }
    }

    /** Draw attention after jumping to the saved position. */
    fun pulse() {
        if (!shown) return
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            addUpdateListener { pulse = it.animatedValue as Float; guideAlpha = 0.3f + 0.6f * (1 - pulse); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { pulse = -1f; invalidate() }
            })
            start()
        }
    }

    /** Pin removed: roll back up. */
    fun retract() {
        if (!shown) { visibility = GONE; return }
        shown = false
        val g0 = grow; val a0 = guideAlpha
        run(320L, DecelerateInterpolator()) { f, _ ->
            grow = g0 * (1 - f); guideAlpha = a0 * (1 - f); alphaAll = 1 - f * 0.5f
        }.also { it.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { if (!shown) visibility = GONE }
        }) }
    }

    /** Page without a pin / page turning: hide immediately. */
    fun hideNow() {
        anim?.cancel(); pulseAnim?.cancel()
        shown = false; visibility = GONE
    }

    private fun run(duration: Long, interp: android.animation.TimeInterpolator,
                    step: (f: Float, t: Float) -> Unit): ValueAnimator {
        anim?.cancel()
        return ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener {
                step(it.animatedValue as Float, it.animatedFraction)
                invalidate()
            }
            start()
        }.also { anim = it }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Drawing
    // ═════════════════════════════════════════════════════════════════════════

    override fun onDraw(c: Canvas) {
        if (!shown && grow <= 0f) return
        val layer = if (alphaAll < 1f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alphaAll * 255).toInt()) else -1
        when (style) {
            Style.RIBBON    -> drawRibbon(c)
            Style.LINE      -> drawLine(c)
            Style.HIGHLIGHT -> drawHighlight(c)
        }
        if (layer >= 0) c.restoreToCount(layer)
    }

    private fun edgeX(): Float = if (onRightEdge) width - 30 * d else 30 * d

    private fun drawGuide(c: Canvas, y: Float) {
        if (guideAlpha <= 0f) return
        guidePaint.alpha = (guideAlpha * 255).toInt()
        val len = width * sweep
        if (onRightEdge) c.drawLine(width.toFloat(), y, width - len, y, guidePaint)
        else             c.drawLine(0f, y, len, y, guidePaint)
    }

    private fun drawRipple(c: Canvas, x: Float, y: Float) {
        if (pulse < 0f) return
        glowPaint.color = accent
        glowPaint.strokeWidth = 3 * d * (1 - pulse)
        glowPaint.alpha = ((1 - pulse) * 200).toInt()
        c.drawCircle(x, y, 10 * d + 40 * d * pulse, glowPaint)
    }

    private fun drawRibbon(c: Canvas) {
        val w = 22 * d
        val tail = 14 * d
        val cx = edgeX()
        val tip = targetY * grow                      // tail tip follows the unroll
        val bottom = (tip - tail).coerceAtLeast(0f)
        val l = cx - w / 2; val r = cx + w / 2

        drawGuide(c, targetY)

        c.save()
        c.rotate(sway, cx, 0f)
        // ribbon body + forked tail
        path.reset()
        path.moveTo(l, 0f); path.lineTo(r, 0f)
        path.lineTo(r, bottom + tail); path.lineTo(cx, bottom + tail * 0.45f)
        path.lineTo(l, bottom + tail); path.close()

        c.save(); c.translate(2 * d, 2 * d); c.drawPath(path, shadowPaint); c.restore()
        ribbonPaint.shader = LinearGradient(l, 0f, r, 0f,
            intArrayOf(accentDark, accent, accentDark), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, ribbonPaint)
        // stitching
        if (bottom > 6 * d) {
            c.drawLine(l + 4 * d, 0f, l + 4 * d, bottom + tail * 0.6f, stitchPaint)
            c.drawLine(r - 4 * d, 0f, r - 4 * d, bottom + tail * 0.6f, stitchPaint)
        }
        c.restore()
        drawRipple(c, cx, targetY)
    }

    private fun drawLine(c: Canvas) {
        val y = targetY
        val len = width * grow
        val x0 = if (onRightEdge) width.toFloat() else 0f
        val x1 = if (onRightEdge) width - len else len
        // glow
        glowPaint.color = accent; glowPaint.alpha = 60; glowPaint.strokeWidth = 10 * d
        c.drawLine(x0, y, x1, y, glowPaint)
        glowPaint.alpha = 255; glowPaint.strokeWidth = 2.5f * d
        c.drawLine(x0, y, x1, y, glowPaint)
        // edge dot
        fillPaint.color = accent
        c.drawCircle(edgeX(), y, 7 * d * grow, fillPaint)
        fillPaint.color = Color.WHITE
        c.drawCircle(edgeX(), y, 2.5f * d * grow, fillPaint)
        drawRipple(c, edgeX(), y)
    }

    private fun drawHighlight(c: Canvas) {
        val h = 46 * d
        val top = targetY - h / 2
        val len = (width - 24 * d) * grow
        val left = if (onRightEdge) width - 12 * d - len else 12 * d
        fillPaint.color = accent; fillPaint.alpha = (0.20f * 255 * (0.6f + 0.4f * guideAlpha)).toInt()
        c.drawRoundRect(left, top, left + len, top + h, 10 * d, 10 * d, fillPaint)
        // edge bar
        fillPaint.alpha = 255
        val barX = if (onRightEdge) width - 12 * d - 4 * d else 12 * d
        c.drawRoundRect(barX, top, barX + 4 * d, top + h * grow, 2 * d, 2 * d, fillPaint)
        drawRipple(c, if (onRightEdge) width - 14 * d else 14 * d, targetY)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); pulseAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
