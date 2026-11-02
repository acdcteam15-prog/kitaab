package com.pdfreader.app.utils

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class WakeLockManager(private val activity: AppCompatActivity) {

    fun applyKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun applyBrightness(brightness: Float) {
        val params = activity.window.attributes
        params.screenBrightness = brightness
        activity.window.attributes = params
    }
}
