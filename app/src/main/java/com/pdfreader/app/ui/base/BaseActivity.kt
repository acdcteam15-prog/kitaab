package com.pdfreader.app.ui.base

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity

/** Every screen extends this: it applies the chosen colour theme and text style, and follows changes made in Settings. */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedTheme = ""
    private var appliedText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Themes.apply(this)      // before super.onCreate, so the window picks it up
        appliedText = TextStyle.currentKey(this)
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        TextStyle.apply(window.decorView, this)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        TextStyle.apply(view, this)
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        TextStyle.apply(view, this)
    }

    /** Toolbar titles are created after setContentView, so style them once the screen is built. */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        TextStyle.apply(window.decorView, this)
    }

    override fun onResume() {
        super.onResume()
        // The theme or text style was changed while this screen was waiting in the back stack
        if (appliedTheme != Themes.currentKey(this) || appliedText != TextStyle.currentKey(this)) recreate()
    }

    /** One colour of the chosen theme, for example themeColor(R.attr.kAccent). */
    fun themeColor(@AttrRes attr: Int): Int = ThemeColors.get(this, attr)
}
