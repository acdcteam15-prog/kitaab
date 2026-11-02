package com.pdfreader.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import com.pdfreader.app.R
import com.pdfreader.app.data.local.AppDatabase
import com.pdfreader.app.data.model.prettyTitle
import com.pdfreader.app.ui.home.HomeActivity
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home-screen widget: last book's cover, title, progress, today's pages vs goal and streak.
 * Tap → opens the book directly. Refreshed when you leave the reader and on Home resume.
 */
class ReadingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Call after reading so the widget shows the new page. Safe if no widget exists. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ReadingWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { render(app, manager, ids) }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = PreferenceManager(context)
            val owner = prefs.currentUser
            val bookId = prefs.lastOpenedBookId
            val progress = if (owner.isNotEmpty() && bookId.isNotEmpty())
                AppDatabase.getInstance(context).progressDao().getProgress(bookId, owner) else null

            val views = RemoteViews(context.packageName, R.layout.widget_reading)
            if (progress != null && !progress.isFinished) {
                views.setTextViewText(R.id.wTitle, prettyTitle(progress.bookName.ifEmpty { "Your book" }))
                views.setProgressBar(R.id.wProgress, 100, progress.percent, false)
                views.setTextViewText(R.id.wPage, "Page ${progress.currentPage + 1} / ${progress.totalPages}  •  ${progress.percent}%")
                views.setViewVisibility(R.id.wProgress, View.VISIBLE)
                val thumb = File(File(context.getExternalFilesDir(null), "thumbs"), "$bookId.png")
                if (thumb.exists()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }   // keep under widget memory limits
                    BitmapFactory.decodeFile(thumb.absolutePath, opts)?.let { views.setImageViewBitmap(R.id.wCover, it) }
                }
            } else {
                views.setTextViewText(R.id.wTitle, "Pick your next book")
                views.setTextViewText(R.id.wPage, "")
                views.setViewVisibility(R.id.wProgress, View.GONE)
            }
            val today = prefs.pagesToday; val goal = prefs.dailyPageGoal
            val streak = prefs.readingStreakDays
            views.setTextViewText(R.id.wToday, buildString {
                append(if (today >= goal) "✓ Goal done ($today pages)" else "Today: $today / $goal pages")
                if (streak >= 2) append("   🔥 $streak")
            })

            val intent = Intent(context, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_OPEN_LAST, progress != null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(
                context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            manager.updateAppWidget(ids, views)
        }
    }
}
