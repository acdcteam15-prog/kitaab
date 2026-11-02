package com.pdfreader.app.ui.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Real page curl that follows the finger, for both horizontal and vertical book mode.
 *
 * Why it doesn't break PDFView (the problem with the old custom animation):
 * it never replaces PDFView's touch handling. PDFView still receives taps, long-press,
 * double-tap and pinch. This layout only *intercepts* a single-finger drag along the
 * turning axis, and only when the page is not zoomed and both page bitmaps are ready.
 * Otherwise, everything falls through to PDFView unchanged.
 *
 * Geometry (classic 2-D page fold):
 *   C = grabbed corner on the free edge, T = where that corner is dragged to.
 *   Fold line = perpendicular bisector of C–T.
 *   The part of the page on C's side is lifted: it reveals the next page underneath,
 *   and is drawn reflected across the fold line as the back of the paper.
 */
class PageCurlLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Host {
        fun canCurl(): Boolean
        fun currentPage(): Int
        fun pageCount(): Int
        fun pageBitmap(page: Int): Bitmap?
        fun paperColor(): Int
        fun onCurlStart()
        fun onTurnCommitted(newPage: Int)
        fun onCurlCancelled()
    }

    var host: Host? = null

    /** Edge-swipe brightness. Implemented here so it works in every reading mode. */
    interface BrightnessListener {
        fun canAdjust(): Boolean
        fun onBrightnessStart()
        fun onBrightnessDelta(fraction: Float)   // + = up = brighter
        fun onBrightnessEnd()
    }
    var brightnessListener: BrightnessListener? = null
    /** Which screen edge controls brightness: Gravity.LEFT or Gravity.RIGHT. */
    var brightnessEdge = android.view.Gravity.LEFT
    private var brightnessDragging = false
    private var lastBrightY = 0f
    /** Vertical = pages flip upward like a wall calendar; horizontal = like a book. */
    var vertical = false
    /** Right-to-left books (Arabic): the page is turned from the LEFT edge toward the right. */
    var rtl = true

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    /** A page turn only starts after a deliberate drag (was: system slop ≈ 8 px). */
    private val turnSlop = 28f * density
    /** A flick must be genuinely fast to count as a turn. */
    private val flingVelocity = 1400f * density
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null

    private var active = false
    private var settling = false
    private var forward = true
    private var newPage = 0
    private var topBmp: Bitmap? = null     // the sheet that moves
    private var underBmp: Bitmap? = null   // the sheet revealed underneath
    private val corner = PointF()          // C
    private val touch = PointF()           // T
    private val start = PointF()           // T at gesture start
    private val turned = PointF()          // T when the sheet is fully turned
    private var animator: ValueAnimator? = null

    // Drawing helpers
    private val bmpPaint    = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val dst = RectF()
    private val reflect = Matrix()

    init { setWillNotDraw(false) }

    // ═════════════════════════════════════════════════════════════════════════
    // Gestures
    // ═════════════════════════════════════════════════════════════════════════

    private fun inBrightnessZone(x: Float): Boolean {
        val zone = width * 0.16f
        return if (brightnessEdge == android.view.Gravity.LEFT) x < zone else x > width - zone
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (active || brightnessDragging) return true   // swallow touches while a turn animates
        trackVelocity(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1) return false
                val dx = e.x - downX; val dy = e.y - downY
                // Brightness: a clearly VERTICAL drag that started on the brightness edge
                val bl = brightnessListener
                if (bl != null && inBrightnessZone(downX) && abs(dy) > slop * 1.5f &&
                    abs(dy) > abs(dx) * 1.8f && bl.canAdjust()) {
                    brightnessDragging = true
                    lastBrightY = e.y
                    bl.onBrightnessStart()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                // Vertical-ish drag on the brightness edge: never start a page turn from it
                if (bl != null && inBrightnessZone(downX) && abs(dy) >= abs(dx)) return false
                val h = host ?: return false
                if (!h.canCurl()) return false
                val along = if (vertical) dy else dx
                val cross = if (vertical) dx else dy
                if (abs(along) > turnSlop && abs(along) > abs(cross) * 1.5f) {
                    val fwd = when {
                        vertical -> dy < 0
                        rtl      -> dx > 0
                        else     -> dx < 0
                    }
                    if (beginCurl(fwd, downX, downY)) {
                        downX = e.x; downY = e.y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (brightnessDragging) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    brightnessListener?.onBrightnessDelta((lastBrightY - e.y) / height.coerceAtLeast(1))
                    lastBrightY = e.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    brightnessDragging = false
                    brightnessListener?.onBrightnessEnd()
                }
            }
            return true
        }
        if (!active || settling) return active
        trackVelocity(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                // Along the turning axis the corner travels 2× the finger (a full swipe
                // across the page = full turn); across it, 1×.
                if (vertical) setTouch(start.x + dx, start.y + dy * 2f)
                else          setTouch(start.x + dx * 2f, start.y + dy)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun trackVelocity(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { velocity?.recycle(); velocity = VelocityTracker.obtain() }
        velocity?.addMovement(e)
    }

    /** Programmatic turn (edge tap / volume key): same curl, played automatically. */
    fun animateTurn(fwd: Boolean): Boolean {
        if (active || host?.canCurl() != true) return false
        val w = width.toFloat(); val h = height.toFloat()
        val gx = if (vertical) w * 0.8f else if (rtl) w * 0.1f else w * 0.9f
        val gy = if (vertical) h * 0.9f else h * 0.8f
        if (!beginCurl(fwd, gx, gy)) return false
        settleTo(if (fwd) turned else corner, commit = true, duration = 480L)
        return true
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Curl state
    // ═════════════════════════════════════════════════════════════════════════

    private fun beginCurl(fwd: Boolean, x: Float, y: Float): Boolean {
        val h = host ?: return false
        val cur = h.currentPage()
        val target = if (fwd) cur + 1 else cur - 1
        if (target !in 0 until h.pageCount()) return false
        val curBmp = h.pageBitmap(cur) ?: return false
        val tgtBmp = h.pageBitmap(target) ?: return false

        forward  = fwd
        newPage  = target
        topBmp   = if (fwd) curBmp else tgtBmp
        underBmp = if (fwd) tgtBmp else curBmp

        val w = width.toFloat(); val hh = height.toFloat()
        if (vertical) {
            // Free edge = bottom, spine = top
            corner.set(if (x < w / 2) 0f else w, hh)
            turned.set(corner.x, -hh)
        } else {
            // Free edge = left for RTL books, right for LTR; spine opposite
            val freeX  = if (rtl) 0f else w
            val spineX = w - freeX
            corner.set(freeX, if (y < hh * 0.35f) 0f else hh)
            turned.set(2 * spineX - freeX, corner.y)
        }
        start.set(if (fwd) corner else turned)
        touch.set(start)
        active = true
        settling = false
        h.onCurlStart()
        invalidate()
        return true
    }

    private fun setTouch(x: Float, y: Float) {
        var tx = x; var ty = y
        val w = width.toFloat(); val h = height.toFloat()
        // Keep T between flat and fully turned along the turning axis
        if (vertical) ty = ty.coerceIn(min(corner.y, turned.y), max(corner.y, turned.y))
        else          tx = tx.coerceIn(min(corner.x, turned.x), max(corner.x, turned.x))
        // Paper can't stretch: the corner stays within reach of both spine corners
        val (s1x, s1y, s2x, s2y) = if (vertical)
            floatArrayOf(corner.x, 0f, w - corner.x, 0f)
        else {
            val spineX = if (rtl) w else 0f
            floatArrayOf(spineX, corner.y, spineX, h - corner.y)
        }
        val r1 = if (vertical) h else w
        val r2 = hypot(w, h)
        fun clampTo(cx: Float, cy: Float, r: Float) {
            val d = hypot(tx - cx, ty - cy)
            if (d > r) { tx = cx + (tx - cx) * r / d; ty = cy + (ty - cy) * r / d }
        }
        clampTo(s1x, s1y, r1)
        clampTo(s2x, s2y, r2)
        touch.set(tx, ty)
    }

    private fun release() {
        velocity?.computeCurrentVelocity(1000)
        val v = if (vertical) velocity?.yVelocity ?: 0f else velocity?.xVelocity ?: 0f
        val total = if (vertical) abs(turned.y - corner.y) else abs(turned.x - corner.x)
        val done  = if (vertical) abs(touch.y - corner.y) else abs(touch.x - corner.x)
        val progress = if (total > 0) done / total else 0f          // 0 = flat, 1 = fully turned
        // Velocity pointing toward the "turned" side?
        val towardTurned = if (vertical) (turned.y - corner.y) * v > 0 else (turned.x - corner.x) * v > 0

        // How far the sheet actually travelled in THIS gesture (forward starts flat, backward starts turned)
        val moved = if (forward) progress else 1f - progress
        val towardCommit = if (forward) towardTurned else !towardTurned
        // Fast flick: needs the right direction AND real movement (≥15 % of the page).
        // Slow drag: must pass the middle (≥45 %), otherwise the page falls back.
        val commit = if (abs(v) > flingVelocity) towardCommit && moved > 0.15f else moved > 0.45f
        val end = if (forward == commit) turned else corner
        settleTo(end, commit, 260L)
    }

    private fun settleTo(end: PointF, commit: Boolean, duration: Long) {
        settling = true
        animator?.cancel()
        val sx = touch.x; val sy = touch.y
        val ex = end.x;   val ey = end.y
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val f = it.animatedValue as Float
                touch.set(sx + (ex - sx) * f, sy + (ey - sy) * f)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (commit) host?.onTurnCommitted(newPage) else { host?.onCurlCancelled(); finishCurl() }
                }
            })
            start()
        }
    }

    /** Called by the host once PDFView shows the new page (keeps the hand-off flicker-free). */
    fun finishCurl() {
        active = false
        settling = false
        topBmp = null; underBmp = null
        invalidate()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Drawing
    // ═════════════════════════════════════════════════════════════════════════

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!active) return
        val top = topBmp ?: return
        val under = underBmp ?: return
        val w = width.toFloat(); val h = height.toFloat()
        dst.set(0f, 0f, w, h)

        val vx = touch.x - corner.x; val vy = touch.y - corner.y
        val len = hypot(vx, vy)
        if (len < 1f) { canvas.drawBitmap(top, null, dst, bmpPaint); return }

        val nx = vx / len; val ny = vy / len            // unit normal of the fold, pointing toward T
        val mx = (corner.x + touch.x) / 2f
        val my = (corner.y + touch.y) / 2f
        val side = { px: Float, py: Float -> (px - mx) * nx + (py - my) * ny }

        val page = listOf(PointF(0f, 0f), PointF(w, 0f), PointF(w, h), PointF(0f, h))
        val lifted = clip(page, side, keepNegative = true)     // part of the sheet that's turning
        val flat   = clip(page, side, keepNegative = false)    // part still lying flat

        // 1. The next sheet underneath
        canvas.drawBitmap(under, null, dst, bmpPaint)

        // 2. The flat part of the turning sheet
        if (flat.size >= 3) {
            canvas.save(); canvas.clipPath(path(flat))
            canvas.drawBitmap(top, null, dst, bmpPaint)
            canvas.restore()
        }
        if (lifted.size < 3) return

        val diag = hypot(w, h)
        // 3. Shadow cast on the revealed page, deepest right at the fold
        canvas.save(); canvas.clipPath(path(lifted))
        shadowPaint.shader = LinearGradient(
            mx, my, mx - nx * diag * 0.12f, my - ny * diag * 0.12f,
            0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(dst, shadowPaint)
        canvas.restore()

        // 4. The back of the lifted part, reflected across the fold
        val back = lifted.map { reflectPoint(it, nx, ny, mx, my) }
        val backPath = path(back)
        val d = mx * nx + my * ny
        reflect.setValues(floatArrayOf(
            1 - 2 * nx * nx, -2 * nx * ny,    2 * d * nx,
            -2 * nx * ny,    1 - 2 * ny * ny, 2 * d * ny,
            0f, 0f, 1f))

        // soft drop shadow of the flap onto the flat page
        canvas.save(); canvas.clipPath(path(flat))
        shadowPaint.shader = LinearGradient(
            mx, my, mx + nx * diag * 0.06f, my + ny * diag * 0.06f,
            0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(dst, shadowPaint)
        canvas.restore()

        canvas.save(); canvas.clipPath(backPath)
        canvas.save(); canvas.concat(reflect)
        canvas.drawBitmap(top, null, dst, bmpPaint)   // mirrored ink showing through the paper
        canvas.restore()
        backPaint.color = (host?.paperColor() ?: Color.WHITE) and 0x00FFFFFF or (0xDC shl 24)
        canvas.drawPath(backPath, backPaint)          // paper back
        // curvature shading: light at the fold, darker toward the flap's edge
        shadowPaint.shader = LinearGradient(
            mx, my, mx + nx * len * 0.5f, my + ny * len * 0.5f,
            intArrayOf(0x22FFFFFF, 0x00000000, 0x33000000), floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP)
        canvas.drawPath(backPath, shadowPaint)
        canvas.restore()

        edgePaint.color = 0x33000000
        canvas.drawPath(backPath, edgePaint)
        shadowPaint.shader = null
    }

    private fun reflectPoint(p: PointF, nx: Float, ny: Float, mx: Float, my: Float): PointF {
        val dist = (p.x - mx) * nx + (p.y - my) * ny
        return PointF(p.x - 2 * dist * nx, p.y - 2 * dist * ny)
    }

    /** Sutherland–Hodgman: clip a convex polygon by the half-plane side(p) ≤ 0 (or ≥ 0). */
    private fun clip(poly: List<PointF>, side: (Float, Float) -> Float, keepNegative: Boolean): List<PointF> {
        val out = ArrayList<PointF>(poly.size + 2)
        fun f(p: PointF) = side(p.x, p.y).let { if (keepNegative) -it else it }
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val fa = f(a); val fb = f(b)
            if (fa >= 0) out += a
            if ((fa >= 0) != (fb >= 0)) {
                val t = fa / (fa - fb)
                out += PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        return out
    }

    private fun path(pts: List<PointF>) = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        velocity?.recycle(); velocity = null
        super.onDetachedFromWindow()
    }
}
