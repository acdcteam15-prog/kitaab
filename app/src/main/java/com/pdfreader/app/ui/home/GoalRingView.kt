package com.pdfreader.app.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Circular daily-goal progress: pages today / goal, with the count in the middle. */
class GoalRingView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * d; color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#F5A623")
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 17 * d; typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 9 * d
    }
    private val oval = RectF()
    private var shown = 0f      // animated fraction
    private var pages = 0
    private var goal = 20

    fun set(pagesToday: Int, dailyGoal: Int, animate: Boolean = true) {
        pages = pagesToday; goal = dailyGoal.coerceAtLeast(1)
        val target = (pages.toFloat() / goal).coerceIn(0f, 1f)
        contentDescription = "$pages of $goal pages today"
        if (!animate) { shown = target; invalidate(); return }
        ValueAnimator.ofFloat(shown, target).apply {
            duration = 700; interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val pad = arc.strokeWidth / 2 + d
        oval.set(pad, pad, width - pad, height - pad)
        c.drawOval(oval, track)
        arc.color = if (pages >= goal) Color.parseColor("#5BCB8A") else Color.parseColor("#F5A623")
        c.drawArc(oval, -90f, 360f * shown, false, arc)
        val cy = height / 2f
        c.drawText(if (pages >= goal) "✓" else "$pages", width / 2f, cy + 2 * d, big)
        c.drawText("/ $goal", width / 2f, cy + 13 * d, small)
    }
}
