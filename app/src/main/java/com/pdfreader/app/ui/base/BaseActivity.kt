package com.pdfreader.app.ui.base

import android.content.Intent
import android.os.Bundle
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import com.pdfreader.app.R

/**
 * Every screen extends this: it applies the chosen light/dark mode and colour theme, follows a theme change
 * made in Settings, and gives every screen the same motion (slide between screens, staggered entrance).
 */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedTheme = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyAppearance(this)           // light / dark / system, before the window is built
        appliedTheme = Themes.apply(this)      // before super.onCreate, so the window picks it up
        super.onCreate(savedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Only on a fresh open, not when the screen is rebuilt for a theme change or rotation
        if (savedInstanceState == null) Motion.enter(this)
    }

    override fun onResume() {
        super.onResume()
        // The theme was changed while this screen was waiting in the back stack
        if (appliedTheme != Themes.currentKey(this)) recreate()
    }

    /** Next screen slides in from the right. Calls that bring their own transition (the book cover zoom) keep it. */
    override fun startActivity(intent: Intent?, options: Bundle?) {
        super.startActivity(intent, options)
        if (options == null) transition(R.anim.k_slide_in_right, R.anim.k_hold_fade)
    }

    override fun finish() {
        super.finish()
        transition(R.anim.k_hold_fade_in, R.anim.k_slide_out_right)
    }

    @Suppress("DEPRECATION")
    private fun transition(enter: Int, exit: Int) = overridePendingTransition(enter, exit)

    /** One colour of the chosen theme, for example themeColor(R.attr.kAccent). */
    fun themeColor(@AttrRes attr: Int): Int = ThemeColors.get(this, attr)
}
