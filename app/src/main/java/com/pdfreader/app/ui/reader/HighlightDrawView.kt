package com.pdfreader.app.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Highlighter mode: drag a finger across the lines you want to mark.
 * A short horizontal drag highlights one line (a minimum band height is enforced);
 * a diagonal drag covers a block of lines. Only visible while the mode is on.
 */
class HighlightDrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var color: Int = 0xFFFFC928.toInt()
    var onRectDrawn: ((RectF) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val minBand = 26 * d
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private var sx = 0f; private var sy = 0f
    private var rect: RectF? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; rect = band(e.x, e.y); invalidate() }
            MotionEvent.ACTION_MOVE -> { rect = band(e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP -> {
                val r = rect; rect = null; invalidate()
                if (r != null && r.width() > 24 * d) {
                    // CONFIRM exists only on Android 11+; older phones get the classic tick
                    performHapticFeedback(
                        if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                        else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onRectDrawn?.invoke(r)
                }
            }
            MotionEvent.ACTION_CANCEL -> { rect = null; invalidate() }
        }
        return true
    }

    private fun band(x: Float, y: Float): RectF {
        var top = min(sy, y); var bottom = max(sy, y)
        if (bottom - top < minBand) { val c = (top + bottom) / 2; top = c - minBand / 2; bottom = c + minBand / 2 }
        return RectF(min(sx, x), top, max(sx, x), bottom)
    }

    override fun onDraw(c: Canvas) {
        val r = rect ?: return
        paint.color = (color and 0x00FFFFFF) or 0x66000000
        edge.color = color
        c.drawRoundRect(r, 6 * d, 6 * d, paint)
        c.drawRoundRect(r, 6 * d, 6 * d, edge)
    }

    init { visibility = GONE }
}
