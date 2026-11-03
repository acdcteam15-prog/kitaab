package com.pdfreader.app.ui.base

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes

/** Reads one colour of the chosen colour theme (the k... attributes, see attrs_theme.xml). */
object ThemeColors {
    fun get(context: Context, @AttrRes attr: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else Color.MAGENTA
    }
}
