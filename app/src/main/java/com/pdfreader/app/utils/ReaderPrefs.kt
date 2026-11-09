package com.pdfreader.app.utils

import android.content.Context

/** Reading-goal, info-line and text-style settings (same preference file as [PreferenceManager]). */
class ReaderPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("pdfreader_prefs", Context.MODE_PRIVATE)

    /** Minutes of reading wanted per day; 0 = no time goal. */
    var dailyMinuteGoal: Int
        get() = prefs.getInt("daily_minute_goal", 0)
        set(v) = prefs.edit().putInt("daily_minute_goal", v).apply()

    /** Milliseconds read today (resets at midnight). */
    val msToday: Long
        get() = if (prefs.getLong("ms_today_day", -1) == java.time.LocalDate.now().toEpochDay())
            prefs.getLong("ms_today", 0L) else 0L

    fun addMsToday(ms: Long) {
        if (ms <= 0) return
        val today = java.time.LocalDate.now().toEpochDay()
        val base = if (prefs.getLong("ms_today_day", -1) == today) prefs.getLong("ms_today", 0L) else 0L
        prefs.edit().putLong("ms_today_day", today).putLong("ms_today", base + ms).apply()
    }

    /** Reader info line: 0 page + %, 1 pages/time left, 2 timer + goal. */
    var readerInfoMode: Int
        get() = prefs.getInt("reader_info_mode", 0)
        set(v) = prefs.edit().putInt("reader_info_mode", v).apply()

    var fontFamily: String
        get() = prefs.getString("font_family", "DEFAULT") ?: "DEFAULT"
        set(v) = prefs.edit().putString("font_family", v).apply()

    var lineSpacing: Float
        get() = prefs.getFloat("line_spacing", 1f)
        set(v) = prefs.edit().putFloat("line_spacing", v).apply()
}
