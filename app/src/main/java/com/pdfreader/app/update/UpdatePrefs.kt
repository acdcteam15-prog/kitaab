package com.pdfreader.app.update

import android.content.Context

/** Everything the updater remembers between runs. */
class UpdatePrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("kitaab_updates", Context.MODE_PRIVATE)

    /** Optional GitHub token (needed only when the repository is private). */
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Version code the user chose to skip. */
    var skippedCode: Long
        get() = sp.getLong("skipped_code", 0L)
        set(v) = sp.edit().putLong("skipped_code", v).apply()

    var lastCheckAt: Long
        get() = sp.getLong("last_check_at", 0L)
        set(v) = sp.edit().putLong("last_check_at", v).apply()

    /** The newest release JSON seen (so the Home banner needs no network). */
    var cachedReleaseJson: String
        get() = sp.getString("release_json", "") ?: ""
        set(v) = sp.edit().putString("release_json", v).apply()

    /** The build we already sent a notification for (one notification per version). */
    var notifiedCode: Long
        get() = sp.getLong("notified_code", 0L)
        set(v) = sp.edit().putLong("notified_code", v).apply()

    /** The build whose APK was downloaded ahead of time over Wi-Fi. */
    var predownloadedCode: Long
        get() = sp.getLong("predownloaded_code", 0L)
        set(v) = sp.edit().putLong("predownloaded_code", v).apply()

    /** The version code that last ran: used to say "Kitaab was updated to X" once. */
    var lastRunCode: Long
        get() = sp.getLong("last_run_code", 0L)
        set(v) = sp.edit().putLong("last_run_code", v).apply()

    var autoCheck: Boolean
        get() = sp.getBoolean("auto_check", true)
        set(v) = sp.edit().putBoolean("auto_check", v).apply()

    /** Message from the Android installer when the last install failed. */
    var lastInstallError: String
        get() = sp.getString("install_error", "") ?: ""
        set(v) = sp.edit().putString("install_error", v).apply()
}
