package com.phonestream.app.send

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.phonestream.app.R
import com.phonestream.app.core.AspectMode
import com.phonestream.app.core.Preset
import com.phonestream.app.core.resolutionLabel
import com.phonestream.app.net.Receiver

/**
 * Keeps the stream alive while the user is on the home screen or in another app: a foreground service of
 * type mediaProjection (+ microphone, which Android requires for background audio capture) owns the session.
 */
class StreamService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: SenderSession? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var finished = false

    private val stateListener: (StreamSnapshot) -> Unit = { s ->
        if (session != null && !finished && s.phase != Phase.IDLE) {
            val text = if (s.phase == Phase.STREAMING)
                "Streaming to ${s.receiverName} · ${resolutionLabel(s.width, s.height)} @ ${s.fps} fps"
            else s.status
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        StreamState.addListener(stateListener)
    }

    override fun onDestroy() {
        StreamState.removeListener(stateListener)
        session?.end("Streaming service stopped") // also gives the phone its volume back
        releaseLocks()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming(intent)
            ACTION_STOP -> {
                val s = session
                if (s != null) s.end(null) else shutDown()
            }
            ACTION_PRESET -> session?.setPreset(Preset.fromName(intent.getStringExtra(EXTRA_PRESET)))
            ACTION_ASPECT -> session?.setAspect(AspectMode.fromName(intent.getStringExtra(EXTRA_ASPECT)))
            ACTION_MUTE -> session?.setPhoneMute(intent.getBooleanExtra(EXTRA_MUTE, true))
            else -> if (session == null) shutDown()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(i: Intent) {
        // A new start replaces whatever was running.
        session?.end("Replaced by a new stream")
        session = null
        finished = false

        val name = i.getStringExtra(EXTRA_NAME) ?: "receiver"
        val host = i.getStringExtra(EXTRA_HOST)
        val port = i.getIntExtra(EXTRA_PORT, 0)
        val preset = Preset.fromName(i.getStringExtra(EXTRA_PRESET))
        val aspect = AspectMode.fromName(i.getStringExtra(EXTRA_ASPECT))
        val audio = i.getBooleanExtra(EXTRA_AUDIO, true)
        val mute = i.getBooleanExtra(EXTRA_MUTE, true)
        val resultCode = i.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val grant: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_GRANT, Intent::class.java)
        else i.getParcelableExtra(EXTRA_GRANT)

        // Must happen first: getMediaProjection() is only allowed once we are a mediaProjection foreground service.
        if (!goForeground("Starting…", audio)) return
        if (host == null || port == 0 || grant == null) {
            fail("Missing connection details")
            return
        }

        val proj = try {
            (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode, grant)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            fail("Could not start screen capture")
            return
        }
        projection = proj
        // Android 14 requires a callback to be registered before the VirtualDisplay is created.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                session?.end("Screen sharing was stopped")
            }
        }, main)

        acquireLocks()
        session = SenderSession(applicationContext, proj, Receiver(name, host, port), preset, aspect, audio, mute) { reason ->
            main.post { onSessionEnded(reason) }
        }.also { it.start() }
    }

    /** What survives a stream: the user's choices. Anything about the picture is stale (the phone may have rotated since). */
    private fun idleSnapshot(error: String?, old: StreamSnapshot) =
        StreamSnapshot(error = error, preset = old.preset, aspect = old.aspect, muteWanted = old.muteWanted)

    private fun onSessionEnded(reason: String?) {
        StreamState.update { idleSnapshot(reason, it) }
        shutDown()
    }

    private fun fail(msg: String) {
        StreamState.update { idleSnapshot(msg, it) }
        shutDown()
    }

    private fun shutDown() {
        if (finished) return
        finished = true
        session = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- foreground notification -------------------------------------------------------------------

    private fun goForeground(text: String, wantAudio: Boolean): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen streaming", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while your screen is being streamed to a receiver"
                    setShowBadge(false)
                }
            )
        }
        val micAllowed = wantAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notification = buildNotification(text)
        try {
            startForegroundCompat(notification, withMic = micAllowed)
            return true
        } catch (e: Exception) {
            if (micAllowed) {
                // Some systems refuse the microphone type from here; video still works without it.
                try {
                    startForegroundCompat(notification, withMic = false)
                    return true
                } catch (_: Exception) {
                }
            }
            fail("Android would not allow background streaming: ${e.message}")
            return false
        }
    }

    private fun startForegroundCompat(n: Notification, withMic: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMic && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle("PhoneStream")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    // ---- keep the CPU and Wi-Fi awake while the screen is off / app is in the background -------------

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneStream:stream").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS) // released on stop; the cap only protects against a leaked lock
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "PhoneStream:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val WAKE_LOCK_MAX_MS = 12L * 60 * 60 * 1000
        const val ACTION_START = "com.phonestream.app.START"
        const val ACTION_STOP = "com.phonestream.app.STOP"
        const val ACTION_PRESET = "com.phonestream.app.PRESET"
        const val ACTION_ASPECT = "com.phonestream.app.ASPECT"
        const val ACTION_MUTE = "com.phonestream.app.MUTE"

        const val EXTRA_NAME = "name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_ASPECT = "aspect"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_MUTE = "mute"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_GRANT = "grant"

        private const val CHANNEL = "stream"
        private const val NOTIF_ID = 1

        fun startIntent(
            ctx: Context, r: Receiver, preset: Preset, aspect: AspectMode, audio: Boolean, mutePhone: Boolean,
            resultCode: Int, grant: Intent,
        ) = Intent(ctx, StreamService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_NAME, r.name)
            .putExtra(EXTRA_HOST, r.host)
            .putExtra(EXTRA_PORT, r.port)
            .putExtra(EXTRA_PRESET, preset.name)
            .putExtra(EXTRA_ASPECT, aspect.name)
            .putExtra(EXTRA_AUDIO, audio)
            .putExtra(EXTRA_MUTE, mutePhone)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_GRANT, grant)

        fun presetIntent(ctx: Context, preset: Preset) =
            Intent(ctx, StreamService::class.java).setAction(ACTION_PRESET).putExtra(EXTRA_PRESET, preset.name)

        fun aspectIntent(ctx: Context, aspect: AspectMode) =
            Intent(ctx, StreamService::class.java).setAction(ACTION_ASPECT).putExtra(EXTRA_ASPECT, aspect.name)

        fun muteIntent(ctx: Context, on: Boolean) =
            Intent(ctx, StreamService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_MUTE, on)

        fun stopIntent(ctx: Context) = Intent(ctx, StreamService::class.java).setAction(ACTION_STOP)
    }
}
