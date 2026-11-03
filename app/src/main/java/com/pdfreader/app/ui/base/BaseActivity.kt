package com.pdfreader.app.ui.base

import android.os.Bundle
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity

/** Every screen extends this: it applies the chosen colour theme and follows a theme change made in Settings. */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedTheme = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Themes.apply(this)      // before super.onCreate, so the window picks it up
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // The theme was changed while this screen was waiting in the back stack
        if (appliedTheme != Themes.currentKey(this)) recreate()
    }

    /** One colour of the chosen theme, for example themeColor(R.attr.kAccent). */
    fun themeColor(@AttrRes attr: Int): Int = ThemeColors.get(this, attr)
}
