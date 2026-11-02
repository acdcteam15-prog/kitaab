package com.pdfreader.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.pdfreader.app.R
import com.pdfreader.app.ui.home.HomeActivity
import com.pdfreader.app.utils.PreferenceManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Daily gentle reminder at the chosen time — only if today's goal isn't met yet.
 * Tapping it continues the last book.
 */
class GoalReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferenceManager(applicationContext)
        if (!prefs.goalReminderEnabled) return Result.success()
        val left = prefs.dailyPageGoal - prefs.pagesToday
        if (left > 0) notify(left, prefs.readingStreakDays)
        return Result.success()
    }

    private fun notify(pagesLeft: Int, streak: Int) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_OPEN_LAST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (streak >= 2) "$pagesLeft pages to go — keep your $streak-day streak 🔥"
                   else "$pagesLeft pages to reach today's goal"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bookmark_filled)
            .setContentTitle("📖 Time to read")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL = "reading_goal"
        private const val NOTIF_ID = 4101
        private const val WORK = "goal_reminder"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reading goal", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Daily reminder when today's reading goal isn't reached" })
        }

        /** (Re)schedule for the chosen time every day, or cancel if disabled. */
        fun schedule(ctx: Context) {
            val prefs = PreferenceManager(ctx)
            val wm = WorkManager.getInstance(ctx)
            if (!prefs.goalReminderEnabled) { wm.cancelUniqueWork(WORK); return }
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atStartOfDay().plusMinutes(prefs.goalReminderMinutes.toLong())
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes()
            val req = PeriodicWorkRequestBuilder<GoalReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, req)
        }
    }
}
