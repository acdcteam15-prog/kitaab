package com.pdfreader.app.ui.base

import android.app.Activity
import android.content.Context
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.pdfreader.app.R
import com.pdfreader.app.utils.PreferenceManager

/**
 * The colour themes that can be chosen in Settings. "Classic" is the base theme (no overlay); the others are
 * overlays that only replace the palette attributes (see themes_palettes.xml, which has a light and a dark
 * variant of every theme).
 */
object Themes {
    data class Entry(val key: String, val name: String, @StyleRes val overlay: Int)

    val all: List<Entry> = listOf(
        Entry("classic", "Classic", 0),
        Entry("paper", "Paper", R.style.ThemeOverlay_Kitaab_Paper),
        Entry("sky", "Sky", R.style.ThemeOverlay_Kitaab_Sky),
        Entry("mint", "Mint", R.style.ThemeOverlay_Kitaab_Mint),
        Entry("ocean", "Ocean", R.style.ThemeOverlay_Kitaab_Ocean),
        Entry("forest", "Forest", R.style.ThemeOverlay_Kitaab_Forest),
        Entry("rose", "Rose", R.style.ThemeOverlay_Kitaab_Rose),
        Entry("graphite", "Graphite", R.style.ThemeOverlay_Kitaab_Graphite),
        Entry("black", "Black", R.style.ThemeOverlay_Kitaab_Black)
    )

    fun entry(key: String): Entry = all.firstOrNull { it.key == key } ?: all[0]

    /** The key of the theme chosen in Settings. */
    fun currentKey(context: Context): String = entry(PreferenceManager(context).colorTheme).key

    /** Light / dark choice from Settings ("system", "light", "dark"). Changing it recreates the open screens. */
    fun applyAppearance(context: Context) {
        val mode = when (PreferenceManager(context).appearance) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Puts the chosen theme on the activity. Must run BEFORE super.onCreate. Returns the key that was applied. */
    fun apply(activity: Activity): String {
        val e = entry(PreferenceManager(activity).colorTheme)
        if (e.overlay != 0) activity.theme.applyStyle(e.overlay, true)
        return e.key
    }
}
