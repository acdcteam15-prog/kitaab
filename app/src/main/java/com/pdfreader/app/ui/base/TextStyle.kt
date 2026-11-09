package com.pdfreader.app.ui.base

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pdfreader.app.utils.ReaderPrefs
import java.util.Collections
import java.util.WeakHashMap

/**
 * App-wide font and line spacing. Uses the phone's built-in font families, so nothing has to be
 * downloaded. (PDF pages are pictures of text — their own letters cannot be restyled; this
 * changes every label, title, note and sheet of the app.)
 */
object TextStyle {

    class Font(val key: String, val label: String, val family: String?)
    class Spacing(val value: Float, val label: String)

    val fonts = listOf(
        Font("DEFAULT", "Default", null),
        Font("SERIF", "Serif (book style)", "serif"),
        Font("MEDIUM", "Clean sans", "sans-serif-medium"),
        Font("CONDENSED", "Compact", "sans-serif-condensed"),
        Font("MONO", "Monospace", "monospace"),
        Font("CASUAL", "Casual", "casual")
    )

    val spacings = listOf(
        Spacing(1.0f, "Normal"),
        Spacing(1.15f, "Comfortable"),
        Spacing(1.3f, "Relaxed"),
        Spacing(1.5f, "Airy")
    )

    fun fontLabel(key: String) = fonts.firstOrNull { it.key == key }?.label ?: "Default"
    fun spacingLabel(v: Float) = spacings.minByOrNull { kotlin.math.abs(it.value - v) }?.label ?: "Normal"

    /** Changes when the user picks another style — screens compare it to know when to redraw. */
    fun currentKey(ctx: Context): String {
        val p = ReaderPrefs(ctx)
        return p.fontFamily + "|" + p.lineSpacing
    }

    private val hooked: MutableSet<RecyclerView> = Collections.newSetFromMap(WeakHashMap())

    /** Applies the chosen style to [root] and everything below it (also list rows as they appear). */
    fun apply(root: View?, ctx: Context) {
        if (root == null) return
        val p = ReaderPrefs(ctx)
        val family = fonts.firstOrNull { it.key == p.fontFamily }?.family
        val spacing = p.lineSpacing
        if (family == null && spacing == 1f) return
        walk(root, family, spacing)
    }

    private fun walk(v: View, family: String?, spacing: Float) {
        if (v is TextView) {
            if (family != null) v.typeface = Typeface.create(family, v.typeface?.style ?: Typeface.NORMAL)
            if (spacing != 1f && v !is EditText && v.maxLines > 1) v.setLineSpacing(0f, spacing)
        }
        if (v is RecyclerView && hooked.add(v)) {
            v.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) = walk(view, family, spacing)
                override fun onChildViewDetachedFromWindow(view: View) {}
            })
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), family, spacing)
    }
}
