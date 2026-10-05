package com.phonestream.app.receive

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phonestream.app.Prefs
import com.phonestream.app.core.PlayoutClock
import com.phonestream.app.core.resolutionLabel
import com.phonestream.app.net.DeviceInfo
import com.phonestream.app.net.ReceiverAdvertiser
import com.phonestream.app.ui.Ui

/**
 * Receive mode (the TV): advertises this device by name on the LAN, waits for a sender and shows its screen
 * fullscreen. While a sender is connected the device withdraws its announcement, so other phones don't see it.
 */
class ReceiveActivity : Activity(), ReceiverServer.Listener {
    private val main = Handler(Looper.getMainLooper())

    private lateinit var surfaceView: SurfaceView
    private lateinit var aspect: AspectLayout
    private lateinit var idlePanel: View
    private lateinit var idleStatus: TextView
    private lateinit var idleNote: TextView
    private lateinit var ipText: TextView
    private lateinit var stopButton: TextView
    private lateinit var overlay: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayDetail: TextView
    private lateinit var overlayHint: TextView
    private lateinit var disconnectButton: TextView

    private val clock = PlayoutClock()
    private val audio = AudioPlayer(clock)
    private lateinit var video: VideoPlayer
    private lateinit var server: ReceiverServer
    private lateinit var advertiser: ReceiverAdvertiser
    private lateinit var deviceName: String

    private var streaming = false
    private var senderName = ""
    private var videoW = 0
    private var videoH = 0
    private var lastIps: List<String> = emptyList()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var visible = false
    private var serverErrorShown = false

    @Volatile
    private var pendingNote: String? = null

    private val hideOverlay = Runnable { setOverlayVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.setLastMode(this, Prefs.MODE_RECEIVE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        deviceName = DeviceInfo.deviceName(this)
        video = VideoPlayer(
            clock = clock,
            requestKeyFrame = { server.requestKeyFrame() },
            onFatal = { msg -> onPlaybackFailed(msg) },
        )
        server = ReceiverServer(deviceName, video, audio, this)
        advertiser = ReceiverAdvertiser(this, deviceName)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                video.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                video.setSurface(null)
            }
        })
        aspect = AspectLayout(this).apply { addView(surfaceView) }
        root.addView(aspect, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        idlePanel = buildIdlePanel()
        root.addView(idlePanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        overlay = buildOverlay()
        root.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                val m = Ui.dp(this@ReceiveActivity, 24)
                setMargins(m, m, m, m)
            }
        )
        setContentView(root)
        showIdle()
    }

    override fun onStart() {
        super.onStart()
        clock.syncOffsetMs = Prefs.syncOffsetMs(this)
        lastIps = DeviceInfo.localIpv4()
        updateIpText()
        visible = true
        // The advertisement starts in onListening(): a TV that shows up in the phone's list must really be reachable.
        server.start()
        watchNetwork()
    }

    override fun onStop() {
        visible = false
        unwatchNetwork()
        server.stop()
        advertiser.stop()
        main.removeCallbacks(hideOverlay)
        super.onStop()
    }

    override fun onDestroy() {
        server.stop()
        advertiser.stop()
        video.release()
        audio.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ---- UI -----------------------------------------------------------------------------------------

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Ui.dp(this@ReceiveActivity, top) }

    private fun buildIdlePanel(): View {
        // A phone held sideways has little height: tighter spacing so everything fits without scrolling.
        val short = Ui.short(this)
        val gap = if (short) 8 else 16
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = Ui.dp(this@ReceiveActivity, if (short) 16 else 32)
            setPadding(p, p, p, p)
        }
        col.addView(Ui.label(this, "RECEIVE", 13f, Ui.MUTED, bold = true))
        col.addView(Ui.label(this, deviceName, if (short) 26f else 34f, bold = true).apply { gravity = Gravity.CENTER }, lp(top = 4))
        idleStatus = Ui.label(this, "Starting…", 18f, Ui.OK, bold = true).apply { gravity = Gravity.CENTER }
        col.addView(idleStatus, lp(top = gap))
        col.addView(
            Ui.label(
                this,
                "On your phone: open PhoneStream, choose Send, then pick “$deviceName”.",
                15f, Ui.MUTED
            ).apply { gravity = Gravity.CENTER },
            lp(top = if (short) 6 else 10)
        )
        ipText = Ui.label(this, "", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(ipText, lp(top = gap))
        idleNote = Ui.label(this, "", 15f, Ui.WARN).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        col.addView(idleNote, lp(top = gap))
        stopButton = Ui.button(this, "Stop receiving") { finish() }
        col.addView(stopButton, lp(top = if (short) 14 else 28))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
        }
        scroll.addView(
            col,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        return scroll
    }

    private fun buildOverlay(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(this@ReceiveActivity, 0xCC0D1117.toInt(), Ui.BORDER, 1, 16)
            val p = Ui.dp(this@ReceiveActivity, 18)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        overlayTitle = Ui.label(this, "", 20f, bold = true)
        overlayDetail = Ui.label(this, "", 14f, Ui.MUTED)
        overlayHint = Ui.label(this, "", 13f, Ui.MUTED)
        disconnectButton = Ui.button(this, "Disconnect", danger = true) { disconnect() }
        box.addView(overlayTitle)
        box.addView(overlayDetail, lp(top = 2))
        box.addView(overlayHint, lp(top = 6))
        box.addView(disconnectButton, lp(top = 12))
        return box
    }

    private fun showIdle() {
        idlePanel.visibility = View.VISIBLE
        setOverlayVisible(false)
        if (!stopButton.isFocused) stopButton.requestFocus()
    }

    private fun updateIpText() {
        ipText.text = if (lastIps.isEmpty()) {
            "Not connected to a network — join the same Wi-Fi as your phone."
        } else {
            "This device's address: " + lastIps.joinToString("  ·  ") + "  (for manual connection)"
        }
    }

    private fun setOverlayVisible(show: Boolean, hint: String = "") {
        main.removeCallbacks(hideOverlay)
        if (!show || !streaming) {
            overlay.visibility = View.GONE
            return
        }
        overlayTitle.text = "Receiving from $senderName"
        overlayDetail.text = if (videoW > 0) resolutionLabel(videoW, videoH) else "Waiting for video…"
        overlayHint.text = hint.ifEmpty { "Press Back to leave, or choose Disconnect." }
        overlay.visibility = View.VISIBLE
        disconnectButton.requestFocus()
        main.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun disconnect() {
        server.disconnect()
    }

    // ---- input ---------------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (streaming) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                    return super.onKeyDown(keyCode, event)
                KeyEvent.KEYCODE_BACK -> {
                    if (overlay.visibility == View.VISIBLE) disconnect()
                    else setOverlayVisible(true, "Press Back again to disconnect.")
                    return true
                }
            }
            if (overlay.visibility != View.VISIBLE) {
                setOverlayVisible(true)
                return true
            }
            // Overlay is up: keep it up while the user navigates.
            main.removeCallbacks(hideOverlay)
            main.postDelayed(hideOverlay, OVERLAY_MS)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (streaming && event.action == MotionEvent.ACTION_DOWN && overlay.visibility != View.VISIBLE) {
            setOverlayVisible(true)
            return true
        }
        return super.onTouchEvent(event)
    }

    // ---- ReceiverServer.Listener (background threads) --------------------------------------------------

    override fun onListening() {
        runOnUiThread {
            if (isDestroyed || !visible) return@runOnUiThread
            advertiser.start()
            if (serverErrorShown) {
                serverErrorShown = false
                showNote(null)
            }
            if (!streaming) idleStatus.text = "Waiting for a sender…"
        }
    }

    override fun onServerError(message: String) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            serverErrorShown = true
            idleStatus.text = "Not ready"
            showNote(message)
        }
    }

    override fun onSessionStarted(senderName: String) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            advertiser.setBusy(true)
            streaming = true
            this.senderName = senderName
            videoW = 0
            videoH = 0
            pendingNote = null
            showNote(null)
            idlePanel.visibility = View.GONE
            setOverlayVisible(true, "Connected.")
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            videoW = width
            videoH = height
            aspect.setAspect(width, height)
            if (overlay.visibility == View.VISIBLE) overlayDetail.text = resolutionLabel(width, height)
        }
    }

    override fun onSessionEnded(reason: String?) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            advertiser.setBusy(false)
            streaming = false
            videoW = 0
            videoH = 0
            val note = reason ?: pendingNote
            pendingNote = null
            idleStatus.text = "Waiting for a sender…"
            showNote(note)
            showIdle()
        }
    }

    private fun onPlaybackFailed(message: String) {
        pendingNote = message
        server.abort(message)
    }

    private fun showNote(text: String?) {
        if (text.isNullOrEmpty()) {
            idleNote.visibility = View.GONE
        } else {
            idleNote.text = text
            idleNote.visibility = View.VISIBLE
        }
    }

    // ---- network changes ----------------------------------------------------------------------------

    private fun checkIps() {
        if (isDestroyed) return
        val ips = DeviceInfo.localIpv4()
        if (ips == lastIps) return
        lastIps = ips
        updateIpText()
        advertiser.refresh()
    }

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { checkIps() }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                main.post { checkIps() }
            }

            override fun onLost(network: Network) {
                main.post { checkIps() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    /** Centres its child at the stream's aspect ratio as large as possible (letterbox / pillarbox). */
    private class AspectLayout(context: Context) : FrameLayout(context) {
        private var aw = 16
        private var ah = 9

        fun setAspect(w: Int, h: Int) {
            if (w > 0 && h > 0 && (w != aw || h != ah)) {
                aw = w
                ah = h
                requestLayout()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            var cw = w
            var ch = (w.toLong() * ah / aw).toInt()
            if (ch > h) {
                ch = h
                cw = (h.toLong() * aw / ah).toInt()
            }
            for (i in 0 until childCount) {
                getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
                )
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val w = right - left
            val h = bottom - top
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val l = (w - c.measuredWidth) / 2
                val t = (h - c.measuredHeight) / 2
                c.layout(l, t, l + c.measuredWidth, t + c.measuredHeight)
            }
        }
    }

    companion object {
        private const val OVERLAY_MS = 5000L
    }
}
