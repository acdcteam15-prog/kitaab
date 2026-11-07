package com.pdfreader.app.ui.base

import android.annotation.SuppressLint
import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.pdfreader.app.R
import java.util.Collections
import java.util.WeakHashMap

/**
 * Small, shared animations. Nothing here touches the screens' own code: it finds views by id and only moves
 * alpha / translation / scale, which always end at their normal values.
 */
object Motion {

    private val decel = DecelerateInterpolator(1.6f)

    /** Called once from BaseActivity for every freshly opened screen. */
    fun enter(activity: Activity) {
        val d = activity.resources.displayMetrics.density

        // Home: the greeting block, search bar and goal ring settle in one after the other
        activity.findViewById<View>(R.id.tvGreeting)?.let { slideIn(it, fromX = -18 * d, delay = 60) }
        activity.findViewById<View>(R.id.tvHeaderSubtitle)?.let { slideIn(it, fromX = -18 * d, delay = 130) }
        activity.findViewById<View>(R.id.searchView)?.let { slideIn(it, fromY = 14 * d, delay = 190) }
        activity.findViewById<View>(R.id.goalRing)?.let { v ->
            v.scaleX = 0.5f; v.scaleY = 0.5f
            v.animate().scaleX(1f).scaleY(1f).setStartDelay(220).setDuration(420)
                .setInterpolator(OvershootInterpolator(2.2f)).start()
        }

        // Home: every block of the page rises in the moment it first appears (also after the books have loaded)
        val home = activity.findViewById<ViewGroup>(R.id.contentRoot)
        if (home != null) {
            revealOnFirstShow(home, d)
        } else {
            // Any other scrolling screen (Settings, Stats, ...): stagger the first blocks
            val scroller = activity.findViewById<View>(android.R.id.content)?.let { findScroller(it) }
            val column = (scroller as? ViewGroup)?.getChildAt(0) as? ViewGroup
            if (column != null) revealOnFirstShow(column, d)
        }

        // Rows on Home: slide in sideways on the first fill, and give every card a press effect
        for (id in intArrayOf(R.id.rvReading, R.id.rvFinishedBooks)) {
            activity.findViewById<RecyclerView>(id)?.let { slideInChildren(it, d); pressChildren(it) }
        }
        activity.findViewById<RecyclerView>(R.id.rvBooks)?.let { pressChildren(it) }
        activity.findViewById<View>(R.id.cardContinueReading)?.let { press(it) }
        activity.findViewById<View>(R.id.cardWeeklySummary)?.let { press(it) }
    }

    private fun slideIn(v: View, fromX: Float = 0f, fromY: Float = 0f, delay: Long = 0) {
        v.alpha = 0f; v.translationX = fromX; v.translationY = fromY
        v.animate().alpha(1f).translationX(0f).translationY(0f)
            .setStartDelay(delay).setDuration(380).setInterpolator(decel).start()
    }

    private fun findScroller(v: View): View? {
        if (v is ScrollView || v is NestedScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroller(v.getChildAt(i))?.let { return it }
        return null
    }

    /**
     * Animates each direct child of [container] the first time it is shown (visible and measured). Children that
     * start their own animation in the same moment are left alone so nothing plays twice.
     */
    private fun revealOnFirstShow(container: ViewGroup, d: Float) {
        val seen: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
        var budget = 14                      // only the first blocks; a long settings page does not need all of them
        container.viewTreeObserver.addOnGlobalLayoutListener {
            var order = 0
            for (i in 0 until container.childCount) {
                val c = container.getChildAt(i)
                if (c.visibility != View.VISIBLE || c.height == 0 || !seen.add(c)) continue
                if (budget <= 0 || c.animation != null) continue
                budget--
                c.alpha = 0f
                c.translationY = 22 * d
                c.animate().alpha(1f).translationY(0f)
                    .setStartDelay(order * 55L).setDuration(340).setInterpolator(decel).start()
                order++
            }
        }
    }

    /** The first items of a horizontal row slide in from the side, one after the other (first fill only). */
    private fun slideInChildren(rv: RecyclerView, d: Float) {
        var firstAt = 0L
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val now = SystemClock.uptimeMillis()
                if (firstAt == 0L) firstAt = now
                if (now - firstAt > 600) return
                val pos = rv.getChildAdapterPosition(view).coerceIn(0, 6)
                view.alpha = 0f
                view.translationX = 36 * d
                view.animate().alpha(1f).translationX(0f)
                    .setStartDelay(pos * 60L).setDuration(360).setInterpolator(decel).start()
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.animate().cancel()
                view.alpha = 1f
                view.translationX = 0f
            }
        })
    }

    private fun pressChildren(rv: RecyclerView) {
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = press(view)
            override fun onChildViewDetachedFromWindow(view: View) {
                view.scaleX = 1f; view.scaleY = 1f
            }
        })
    }

    /** Shrinks a little while pressed and springs back. Never consumes the touch, so clicks keep working. */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(110)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(260)
                        .setInterpolator(OvershootInterpolator(2.4f)).start()
            }
            false
        }
    }
}
