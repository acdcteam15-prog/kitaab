package com.pdfreader.app.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Full-screen overlay for fixing the footnote line by hand: a line with a grip that the
 * finger drags up/down; everything below it is dimmed (that is what will be folded away).
 * It swallows all touches while visible, so the page underneath never moves.
 */
class FootnoteLineEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val veil = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5A623"); strokeWidth = 3f * d
    }
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5A623") }
    private val gripMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1B2E"); strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND
    }
    private val r = RectF()
    private var grabDy = 0f

    var minY = 0f
    var maxY = 0f

    /** Screen y of the line. */
    var lineY = 0f
        set(v) { field = v.coerceIn(minY, maxOf(minY, maxY)); invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        c.drawRect(0f, lineY, w, height.toFloat(), veil)
        c.drawLine(0f, lineY, w, lineY, line)
        val gw = 64f * d; val gh = 26f * d
        r.set(w / 2f - gw / 2f, lineY - gh / 2f, w / 2f + gw / 2f, lineY + gh / 2f)
        c.drawRoundRect(r, gh / 2f, gh / 2f, grip)
        for (k in -1..1) {
            val yy = lineY + k * 5f * d
            c.drawLine(w / 2f - 10f * d, yy, w / 2f + 10f * d, yy, gripMark)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grabDy = if (abs(e.y - lineY) < 64f * d) lineY - e.y else 0f
                lineY = e.y + grabDy
            }
            MotionEvent.ACTION_MOVE -> lineY = e.y + grabDy
        }
        return true
    }
}
