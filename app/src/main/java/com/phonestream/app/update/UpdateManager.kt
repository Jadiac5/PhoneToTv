package com.phonestream.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.phonestream.app.Prefs
import com.phonestream.app.send.Phase
import com.phonestream.app.send.StreamState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    /** The newer version (for AVAILABLE / DOWNLOADING / INSTALLING). */
    val info: ReleaseInfo? = null,
    /** 0..100 while DOWNLOADING. */
    val progress: Int = 0,
    /** Explains an ERROR, or a hint like "allow installs, then tap again". */
    val message: String = "",
)

/**
 * Looks for a newer release on GitHub, downloads it and hands it to Android's installer.
 * One process-wide state, so the Settings screen and the little "Update available" button on the start
 * screen always agree (and a download survives switching between them).
 *
 * The whole flow is the same button: "Check for updates" -> "Update to vX.Y.Z" -> "Downloading 40%" -> installer.
 */
object UpdateManager {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(UpdateState) -> Unit>()
    private val busy = AtomicBoolean(false)

    @Volatile
    var state = UpdateState()
        private set

    /** Set while we wait for the user to allow "install unknown apps" for us; the update continues when they return. */
    @Volatile
    private var resumeAfterPermission = false

    private const val AUTO_CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    private fun set(s: UpdateState) {
        state = s
        main.post { for (l in listeners) l(s) }
    }

    /** Call from the main thread. Delivers the current state at once. */
    fun addListener(ctx: Context, l: (UpdateState) -> Unit) {
        restoreFromCache(ctx)
        listeners += l
        l(state)
    }

    fun removeListener(l: (UpdateState) -> Unit) {
        listeners -= l
    }

    fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** First look at the state in this process: what the last check found, if that is still newer than what runs. */
    private fun restoreFromCache(ctx: Context) {
        if (state.phase != UpdatePhase.IDLE) return
        val cached = Prefs.cachedUpdate(ctx)
        if (cached != null && Version.isNewer(cached.version, installedVersion(ctx))) {
            state = UpdateState(UpdatePhase.AVAILABLE, cached)
        } else if (cached != null) {
            Prefs.setCheckResult(ctx, Prefs.lastCheckAt(ctx), null) // we ARE that version now
        }
    }

    /** Silent check at app start: only if enabled and the last one is a few hours old. Errors are ignored. */
    fun autoCheck(ctx: Context) {
        val app = ctx.applicationContext
        restoreFromCache(app)
        if (!Prefs.autoCheck(app)) return
        if (System.currentTimeMillis() - Prefs.lastCheckAt(app) < AUTO_CHECK_EVERY_MS) return
        check(app, silent = true)
    }

    /** The user pressed "Check for updates". */
    fun check(ctx: Context, silent: Boolean = false) {
        val app = ctx.applicationContext
        if (state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.DOWNLOADING || state.phase == UpdatePhase.INSTALLING) return
        if (!busy.compareAndSet(false, true)) return
        val before = state
        if (!silent) set(UpdateState(UpdatePhase.CHECKING))
        thread(name = "ps-update-check", isDaemon = true) {
            try {
                val found = fetchLatest()
                val now = System.currentTimeMillis()
                val current = installedVersion(app)
                val newer = found?.takeIf { Version.isNewer(it.version, current) }
                Prefs.setCheckResult(app, now, newer)
                set(
                    if (newer != null) UpdateState(UpdatePhase.AVAILABLE, newer)
                    else UpdateState(UpdatePhase.UP_TO_DATE, message = "PhoneStream $current is the latest version.")
                )
            } catch (e: Exception) {
                set(if (silent) before else UpdateState(UpdatePhase.ERROR, message = explain(e)))
            } finally {
                busy.set(false)
            }
        }
    }

    /** Downloads and installs the version found by [check]. [activity] is only used to open Android's "install apps" settings if needed. */
    fun startUpdate(activity: Activity) {
        val info = state.info ?: return
        if (state.phase != UpdatePhase.AVAILABLE && state.phase != UpdatePhase.ERROR) return
        if (StreamState.snapshot.phase != Phase.IDLE) {
            set(UpdateState(UpdatePhase.ERROR, info, message = "Stop streaming first, then update."))
            return
        }
        val app = activity.applicationContext
        if (!Installer.canInstall(app)) {
            resumeAfterPermission = true
            set(UpdateState(UpdatePhase.AVAILABLE, info, message = "Allow PhoneStream to install apps, then come back and tap again."))
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                )
            } catch (_: Exception) {
                resumeAfterPermission = false
                set(
                    UpdateState(
                        UpdatePhase.ERROR, info,
                        message = "Open Settings → Apps → Special access → Install unknown apps → PhoneStream, allow it, then try again."
                    )
                )
            }
            return
        }
        resumeAfterPermission = false
        download(app, info)
    }

    /** Call from onResume of a screen showing the button: carries on after the user allowed installs. */
    fun onResume(activity: Activity) {
        if (resumeAfterPermission && Installer.canInstall(activity)) {
            resumeAfterPermission = false
            startUpdate(activity)
        }
    }

    /** The system installer finished or failed (called from [UpdateResultReceiver]). */
    fun installerResult(error: String?) {
        val info = state.info
        if (error == null) set(UpdateState(UpdatePhase.INSTALLING, info, 100, "Installing…"))
        else set(UpdateState(UpdatePhase.ERROR, info, message = error))
    }

    // ---- network -------------------------------------------------------------------------------------

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "PhoneStream-Updater")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return c
    }

    /** The latest published release, or null if nothing (usable) has been published yet. */
    private fun fetchLatest(): ReleaseInfo? {
        val c = open(Updates.LATEST_URL, "application/vnd.github+json")
        try {
            when (val code = c.responseCode) {
                200 -> return ReleaseParser.parse(c.inputStream.bufferedReader().use { it.readText() })
                404 -> return null
                403, 429 -> throw IOException("GitHub is limiting requests from this network right now. Try again in a while.")
                else -> throw IOException("GitHub answered with error $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun download(app: Context, info: ReleaseInfo) {
        if (!busy.compareAndSet(false, true)) return
        set(UpdateState(UpdatePhase.DOWNLOADING, info, 0))
        thread(name = "ps-update-download", isDaemon = true) {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val part = File(dir, "PhoneStream-${info.version}.apk.part")
            val apk = File(dir, "PhoneStream-${info.version}.apk")
            try {
                if (!info.apkUrl.startsWith(Updates.DOWNLOAD_PREFIX)) throw IOException("Refusing to download from an unexpected address")
                val c = open(info.apkUrl, "application/octet-stream")
                val sha = MessageDigest.getInstance("SHA-256")
                try {
                    if (c.responseCode != 200) throw IOException("The download failed (error ${c.responseCode})")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
                    var done = 0L
                    var lastPct = -1
                    FileOutputStream(part).use { out ->
                        c.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                sha.update(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                                if (pct != lastPct) {
                                    lastPct = pct
                                    set(UpdateState(UpdatePhase.DOWNLOADING, info, pct))
                                }
                            }
                        }
                    }
                    if (total > 0 && done != total) throw IOException("The download was cut short. Try again.")
                } finally {
                    c.disconnect()
                }
                val actual = sha.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256 != null && !info.sha256.equals(actual, ignoreCase = true)) {
                    throw IOException("The downloaded file does not match its checksum, so it was thrown away.")
                }
                if (!part.renameTo(apk)) throw IOException("Could not save the update")
                set(UpdateState(UpdatePhase.INSTALLING, info, 100, "Handing over to Android…"))
                val err = Installer.install(app, apk)
                if (err != null) set(UpdateState(UpdatePhase.ERROR, info, message = err))
            } catch (e: Exception) {
                part.delete()
                set(UpdateState(UpdatePhase.ERROR, info, message = explain(e)))
            } finally {
                busy.set(false)
            }
        }
    }

    private fun explain(e: Exception): String = when (e) {
        is UnknownHostException -> "No internet connection."
        is SocketTimeoutException -> "GitHub did not answer in time."
        is IOException -> e.message ?: "Network error"
        else -> e.message ?: e.javaClass.simpleName
    }
}
