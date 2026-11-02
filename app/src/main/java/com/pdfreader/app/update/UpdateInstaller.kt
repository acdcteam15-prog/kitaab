package com.pdfreader.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Installs the verified APK with a PackageInstaller session; falls back to the system installer screen. */
object UpdateInstaller {

    const val ACTION_RESULT = "com.pdfreader.app.update.INSTALL_RESULT"

    /** Android 8+: the user must allow "install unknown apps" for this app once. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Allow installing from this app" page for Kitaab. */
    fun allowInstallIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Starts the install. Android shows its own confirm window (or none on Android 12+ for a self update)
     * and restarts the app when it is done. Returns true when the session was started.
     */
    fun install(context: Context, apk: File): Boolean {
        return try {
            val installer = context.packageManager.packageInstaller
            // Old unfinished sessions of ours would block a new one.
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            params.setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("kitaab-update.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_RESULT)
                    .setPackage(context.packageName)
                // The installer fills in extra data, so this PendingIntent must be mutable on Android 12+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Plain "open this APK" screen: used when the session install could not start. */
    fun fallbackIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
