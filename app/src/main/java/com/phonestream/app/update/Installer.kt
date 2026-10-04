package com.phonestream.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/** Installs an APK through Android's PackageInstaller (works on phones and Android TV, no root). */
object Installer {
    const val ACTION_RESULT = "com.phonestream.app.INSTALL_RESULT"

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Starts the install. Returns null if it was handed over (the outcome arrives at [UpdateResultReceiver]), else why not. */
    fun install(ctx: Context, apk: File): String? {
        val installer = ctx.packageManager.packageInstaller
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("update.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(ctx, UpdateResultReceiver::class.java).setAction(ACTION_RESULT)
                // The system fills the result into this intent, so it has to be mutable (Android 12+).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            return null
        } catch (e: Exception) {
            if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            return "Could not start the installation: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Plain-language text for a [PackageInstaller] failure status. */
    fun explain(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "The installation was cancelled."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android refused the update because it was signed with a different key than the installed app. " +
                "Uninstall PhoneStream once and install the new version by hand."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free storage to install the update."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the installation (${message ?: "policy"})."
        else -> "The installation failed (${message ?: "error $status"})."
    }
}

/** Receives what the system installer has to say: asks the user to confirm, or reports the result. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    UpdateManager.installerResult("Android did not offer to install the update.")
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    UpdateManager.installerResult("Could not open Android's installer: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.installerResult(null) // the app is replaced and restarts
            else -> UpdateManager.installerResult(Installer.explain(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }
}
