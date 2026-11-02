package com.pdfreader.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Receives the result of the PackageInstaller session. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val prefs = UpdatePrefs(context)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android needs the user to confirm: show its window.
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        prefs.lastInstallError = "Android could not open the install window."
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The new version replaces this process; nothing else to do.
                prefs.lastInstallError = ""
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                prefs.lastInstallError = "The installation was cancelled."
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                prefs.lastInstallError = "Android could not install the update: $msg"
                Toast.makeText(context, prefs.lastInstallError, Toast.LENGTH_LONG).show()
            }
        }
    }
}
