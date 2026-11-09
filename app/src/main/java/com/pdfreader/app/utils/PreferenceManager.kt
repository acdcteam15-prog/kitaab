package com.pdfreader.app.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pdfreader_prefs", Context.MODE_PRIVATE)

    var currentUser: String
        get() = prefs.getString("current_user", "") ?: ""
        set(v) = prefs.edit().putString("current_user", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    var lockRotation: Boolean
        get() = prefs.getBoolean("lock_rotation", false)
        set(v) = prefs.edit().putBoolean("lock_rotation", v).apply()

    var readingTheme: String
        get() = prefs.getString("reading_theme", "WHITE") ?: "WHITE"
        set(v) = prefs.edit().putString("reading_theme", v).apply()

    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(v) = prefs.edit().putBoolean("auto_sync", v).apply()

    var pageScrollMode: String
        get() = prefs.getString("scroll_mode", "VERTICAL") ?: "VERTICAL"
        set(v) = prefs.edit().putString("scroll_mode", v).apply()

    /** Book mode: one page at a time, turned with a real page curl (both orientations). */
    var pageTurnAnimation: Boolean
        get() = prefs.getBoolean("page_turn_animation", true)
        set(v) = prefs.edit().putBoolean("page_turn_animation", v).apply()

    /** Right-to-left page order (Arabic books): next page is turned from the left edge. */
    var rtlPageOrder: Boolean
        get() = prefs.getBoolean("rtl_page_order", true)
        set(v) = prefs.edit().putBoolean("rtl_page_order", v).apply()

    /** Queue downloads until the phone is on Wi-Fi (unmetered). */
    /** Colour theme chosen in Settings (see ui/base/Themes.kt). */
    var colorTheme: String
        get() = prefs.getString("color_theme", "classic") ?: "classic"
        set(v) = prefs.edit().putString("color_theme", v).apply()

    /** Light / dark choice made in Settings: "system" (follow the phone), "light" or "dark". */
    var appearance: String
        get() = prefs.getString("appearance", "system") ?: "system"
        set(v) = prefs.edit().putString("appearance", v).apply()

    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean("download_wifi_only", false)
        set(v) = prefs.edit().putBoolean("download_wifi_only", v).apply()

    /** Auto-trim white page margins in book mode. */
    var cropMargins: Boolean
        get() = prefs.getBoolean("crop_margins", false)
        set(v) = prefs.edit().putBoolean("crop_margins", v).apply()

    /** Dark pages: flip scanned pages to light text on the reading-theme background (hue-safe). */
    var invertPages: Boolean
        get() = prefs.getBoolean("invert_pages", false)
        set(v) = prefs.edit().putBoolean("invert_pages", v).apply()

    /** Cached content box per book: "left,top,right,bottom" as page fractions. */
    fun getCropBox(bookId: String): FloatArray? =
        prefs.getString("crop_$bookId", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 4 }?.toFloatArray()

    fun setCropBox(bookId: String, box: FloatArray) =
        prefs.edit().putString("crop_$bookId", box.joinToString(",")).apply()

    /** Pin marker look: RIBBON, LINE or HIGHLIGHT. */
    var pinStyle: String
        get() = prefs.getString("pin_style", "RIBBON") ?: "RIBBON"
        set(v) = prefs.edit().putString("pin_style", v).apply()

    /** Longest reading streak ever seen on this device. */
    var bestStreak: Int
        get() = prefs.getInt("best_streak", 0)
        set(v) = prefs.edit().putInt("best_streak", v).apply()

    // ── Daily reading goal ────────────────────────────────────────────────────
    var dailyPageGoal: Int
        get() = prefs.getInt("daily_page_goal", 20)
        set(v) = prefs.edit().putInt("daily_page_goal", v).apply()

    /** Pages turned forward today (resets at midnight). */
    val pagesToday: Int
        get() = if (prefs.getLong("pages_today_day", -1) == java.time.LocalDate.now().toEpochDay())
            prefs.getInt("pages_today", 0) else 0

    fun addPagesToday(n: Int) {
        val today = java.time.LocalDate.now().toEpochDay()
        val base = if (prefs.getLong("pages_today_day", -1) == today) prefs.getInt("pages_today", 0) else 0
        prefs.edit().putLong("pages_today_day", today).putInt("pages_today", base + n).apply()
    }

    var goalReminderEnabled: Boolean
        get() = prefs.getBoolean("goal_reminder", false)
        set(v) = prefs.edit().putBoolean("goal_reminder", v).apply()

    /** Reminder time in minutes after midnight (default 21:00). */
    var goalReminderMinutes: Int
        get() = prefs.getInt("goal_reminder_min", 21 * 60)
        set(v) = prefs.edit().putInt("goal_reminder_min", v).apply()

    // ── Backup ────────────────────────────────────────────────────────────────
    var lastBackupAt: Long
        get() = prefs.getLong("last_backup_at", 0L)
        set(v) = prefs.edit().putLong("last_backup_at", v).apply()

    var showPageNumber: Boolean
        get() = prefs.getBoolean("show_page_number", true)
        set(v) = prefs.edit().putBoolean("show_page_number", v).apply()

    /** Extra dimming below the screen's minimum (0 = none, up to 0.35) — for reading in the dark. */
    var readerDim: Float
        get() = prefs.getFloat("reader_dim", 0f)
        set(v) = prefs.edit().putFloat("reader_dim", v).apply()

    var screenBrightness: Float
        get() = prefs.getFloat("brightness", -1f)
        set(v) = prefs.edit().putFloat("brightness", v).apply()

    var enableReadingStats: Boolean
        get() = prefs.getBoolean("reading_stats", true)
        set(v) = prefs.edit().putBoolean("reading_stats", v).apply()

    var lastOpenedBookId: String
        get() = prefs.getString("last_book_id", "") ?: ""
        set(v) = prefs.edit().putString("last_book_id", v).apply()

    var lastOpenedBookName: String
        get() = prefs.getString("last_book_name", "") ?: ""
        set(v) = prefs.edit().putString("last_book_name", v).apply()

    var autoNightMode: Boolean
        get() = prefs.getBoolean("auto_night_mode", false)
        set(v) = prefs.edit().putBoolean("auto_night_mode", v).apply()

    var nightModeStartHour: Int
        get() = prefs.getInt("night_start_hour", 21)
        set(v) = prefs.edit().putInt("night_start_hour", v).apply()

    var nightModeEndHour: Int
        get() = prefs.getInt("night_end_hour", 7)
        set(v) = prefs.edit().putInt("night_end_hour", v).apply()

    // ── Pinch-zoom memory ─────────────────────────────────────────────────────
    fun getBookZoom(bookId: String): Float =
        prefs.getFloat("zoom_$bookId", 0f)

    fun setBookZoom(bookId: String, zoom: Float) =
        prefs.edit().putFloat("zoom_$bookId", zoom).apply()

    // ── Reading streak ─────────────────────────────────────────────────────────
    var readingStreakDays: Int
        get() = prefs.getInt("streak_days", 0)
        set(v) = prefs.edit().putInt("streak_days", v).apply()

    var lastReadEpochDay: Long
        get() = prefs.getLong("last_read_day", -1L)
        set(v) = prefs.edit().putLong("last_read_day", v).apply()

    fun updateStreak(): Int {
        val today  = System.currentTimeMillis() / 86_400_000L
        val last   = lastReadEpochDay
        val streak = when {
            last == today     -> readingStreakDays
            last == today - 1 -> readingStreakDays + 1
            else              -> 1
        }
        readingStreakDays = streak
        lastReadEpochDay  = today
        return streak
    }

    // ── Weekly reading snapshot ───────────────────────────────────────────────
    // Stored as JSON: { "weekNumber": Int, "totalPagesBaseline": Int, "totalMsBaseline": Long,
    //                   "daysActiveThisWeek": [epochDay, ...] }
    // "weekNumber" = epoch-day / 7, resets snapshot when it changes.

    data class WeekSummary(
        val pagesThisWeek: Int,
        val minutesThisWeek: Long,
        val daysActive: Int
    )

    /** Call after every session with the current all-time totals for this user. */
    fun recordWeeklyActivity(totalPagesAllTime: Int, totalMsAllTime: Long) {
        val todayEpochDay = System.currentTimeMillis() / 86_400_000L
        val thisWeek      = (todayEpochDay / 7).toInt()

        val raw  = prefs.getString("weekly_snapshot_${currentUser}", null)
        val json = if (raw != null) runCatching { JSONObject(raw) }.getOrNull() else null
        val storedWeek = json?.optInt("weekNumber", -1) ?: -1

        if (json == null || storedWeek != thisWeek) {
            // New week — baseline = current totals, this week's delta starts at 0
            val newJson = JSONObject().apply {
                put("weekNumber",         thisWeek)
                put("totalPagesBaseline", totalPagesAllTime)
                put("totalMsBaseline",    totalMsAllTime)
                put("totalPagesCurrent",  totalPagesAllTime)  // stored for offline use
                put("totalMsCurrent",     totalMsAllTime)     // stored for offline use
                put("daysActive",         todayEpochDay.toString())
            }
            prefs.edit().putString("weekly_snapshot_${currentUser}", newJson.toString()).apply()
        } else {
            // Same week — update current totals and days active
            val days = json.optString("daysActive", "")
                .split(",").filter { it.isNotEmpty() }.toMutableSet()
            days.add(todayEpochDay.toString())
            json.put("totalPagesCurrent", totalPagesAllTime)
            json.put("totalMsCurrent",    totalMsAllTime)
            json.put("daysActive",        days.joinToString(","))
            prefs.edit().putString("weekly_snapshot_${currentUser}", json.toString()).apply()
        }
    }

    /** Clear the weekly snapshot for the current user (called from Settings). */
    fun clearWeeklySnapshot() {
        prefs.edit().remove("weekly_snapshot_${currentUser}").apply()
    }

    /** Return the raw JSON string for the current weekly snapshot, or null. */
    fun getRawWeeklySnapshot(): String? =
        prefs.getString("weekly_snapshot_${currentUser}", null)

    /**
     * Merge a snapshot JSON received from Drive into local storage.
     * Keeps whichever snapshot has more days active (i.e. more reading done).
     * If they are from different weeks, the current week wins.
     */
    fun mergeWeeklySnapshot(driveJson: String) {
        val todayEpochDay = System.currentTimeMillis() / 86_400_000L
        val thisWeek      = (todayEpochDay / 7).toInt()
        val driveObj = runCatching { org.json.JSONObject(driveJson) }.getOrNull() ?: return
        if (driveObj.optInt("weekNumber", -1) != thisWeek) return  // ignore stale Drive data
        val localRaw = prefs.getString("weekly_snapshot_${currentUser}", null)
        if (localRaw == null) {
            // No local snapshot — use Drive's
            prefs.edit().putString("weekly_snapshot_${currentUser}", driveJson).apply()
            return
        }
        val localObj = runCatching { org.json.JSONObject(localRaw) }.getOrNull() ?: return
        if (localObj.optInt("weekNumber", -1) != thisWeek) {
            // Local is stale, use Drive's
            prefs.edit().putString("weekly_snapshot_${currentUser}", driveJson).apply()
            return
        }
        // Both are current week — merge days active sets and keep lower baseline
        val localDays  = localObj.optString("daysActive", "").split(",").filter { it.isNotEmpty() }.toMutableSet()
        val driveDays  = driveObj.optString("daysActive", "").split(",").filter { it.isNotEmpty() }.toSet()
        localDays.addAll(driveDays)
        // Keep the LOWER baseline (more conservative = fewer pages counted as "this week")
        val localBase  = localObj.optInt("totalPagesBaseline", Int.MAX_VALUE)
        val driveBase  = driveObj.optInt("totalPagesBaseline", Int.MAX_VALUE)
        val localMsBase  = localObj.optLong("totalMsBaseline", Long.MAX_VALUE)
        val driveMsBase  = driveObj.optLong("totalMsBaseline", Long.MAX_VALUE)
        val merged = org.json.JSONObject().apply {
            put("weekNumber",         thisWeek)
            put("totalPagesBaseline", minOf(localBase, driveBase))
            put("totalMsBaseline",    minOf(localMsBase, driveMsBase))
            put("daysActive",         localDays.joinToString(","))
        }
        prefs.edit().putString("weekly_snapshot_${currentUser}", merged.toString()).apply()
    }

    fun getWeeklySummary(totalPagesAllTime: Int, totalMsAllTime: Long): WeekSummary {
        val todayEpochDay = System.currentTimeMillis() / 86_400_000L
        val thisWeek      = (todayEpochDay / 7).toInt()

        val raw  = prefs.getString("weekly_snapshot_${currentUser}", null)
        val json = if (raw != null) runCatching { JSONObject(raw) }.getOrNull() else null

        if (json == null || json.optInt("weekNumber", -1) != thisWeek) {
            return WeekSummary(0, 0, 0)
        }

        val basePages = json.optInt("totalPagesBaseline", 0)
        val baseMs    = json.optLong("totalMsBaseline",   0L)

        // Use Room totals if available (> 0), otherwise fall back to stored snapshot totals
        // This makes the summary work correctly even when Room is empty (offline / fresh install)
        val currentPages = if (totalPagesAllTime > 0) totalPagesAllTime
                           else json.optInt("totalPagesCurrent", 0)
        val currentMs    = if (totalMsAllTime > 0) totalMsAllTime
                           else json.optLong("totalMsCurrent",   0L)

        val daysCount = json.optString("daysActive", "")
            .split(",").count { it.isNotEmpty() }

        return WeekSummary(
            pagesThisWeek   = (currentPages - basePages).coerceAtLeast(0),
            minutesThisWeek = ((currentMs - baseMs) / 60_000L).coerceAtLeast(0),
            daysActive      = daysCount
        )
    }
}