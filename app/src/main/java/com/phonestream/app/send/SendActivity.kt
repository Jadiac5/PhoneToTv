package com.phonestream.app.send

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.phonestream.app.Prefs
import com.phonestream.app.core.AspectMode
import com.phonestream.app.core.Planner
import com.phonestream.app.core.Preset
import com.phonestream.app.core.Proto
import com.phonestream.app.core.resolutionLabel
import com.phonestream.app.net.Receiver
import com.phonestream.app.net.SenderDiscovery
import com.phonestream.app.ui.Ui
import kotlin.math.max

/** Send mode: pick a receiver on the LAN, choose the quality, start streaming the screen. */
class SendActivity : Activity() {
    private lateinit var errorBanner: TextView
    private lateinit var streamPanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var audioText: TextView
    private lateinit var notesText: TextView
    private lateinit var congestionText: TextView
    private lateinit var audioCard: LinearLayout
    private lateinit var audioSwitch: Switch
    private lateinit var muteCard: LinearLayout
    private lateinit var muteSwitch: Switch
    private lateinit var muteHint: TextView
    private lateinit var aspectHint: TextView
    private lateinit var receiversSection: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var scanHint: TextView
    private val presetCards = LinkedHashMap<Preset, Pair<LinearLayout, TextView>>()
    private val aspectCards = LinkedHashMap<AspectMode, LinearLayout>()

    private lateinit var discovery: SenderDiscovery
    private var receivers: List<Receiver> = emptyList()
    private var preset = Preset.NATIVE
    private var aspect = AspectMode.ORIGINAL
    private var shownPreset: Preset? = null
    private var shownAspect: AspectMode? = null
    private var pending: Receiver? = null
    private var syncingSwitch = false

    private val listener: (StreamSnapshot) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.setLastMode(this, Prefs.MODE_SEND)
        preset = Prefs.preset(this)
        aspect = Prefs.aspect(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        buildUi()
        discovery = SenderDiscovery(this) { list -> onReceivers(list) }
    }

    override fun onStart() {
        super.onStart()
        StreamState.addListener(listener)
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        StreamState.removeListener(listener)
        super.onStop()
    }

    /** The phone was turned: build the screen again for the new shape (this activity handles the change itself). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        renderReceivers()
        render(StreamState.snapshot)
    }

    // ---- UI construction -----------------------------------------------------------------------------

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Ui.dp(this@SendActivity, top) }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** A scrolling page holding [col]: full width on a phone, a centred readable column on wide screens. */
    private fun scrolling(col: LinearLayout, maxContentDp: Int, short: Boolean): ScrollView =
        ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                val side = max(Ui.dp(this@SendActivity, 20), ((r - l) - Ui.dp(this@SendActivity, maxContentDp)) / 2)
                val top = Ui.dp(this@SendActivity, if (short) 12 else 24)
                if (col.paddingLeft != side || col.paddingTop != top) col.setPadding(side, top, side, Ui.dp(this@SendActivity, 32))
            }
        }

    private fun buildUi() {
        val wide = Ui.wide(this)
        val short = Ui.short(this)
        presetCards.clear()
        aspectCards.clear()
        shownPreset = null
        shownAspect = null

        val head = column().also { buildHeader(it) }
        buildStreamPanel()
        val quality = column().also { buildQualitySection(it, top = if (wide) 0 else 24) }
        val aspectSection = column().also { buildAspectSection(it, top = 20) }
        val switches = column().also { buildSwitches(it) }
        buildReceiversSection(top = if (wide) 20 else 24)

        if (wide) {
            // Two panes: what is running + options on the left, what to choose on the right.
            val left = column().apply {
                addView(head)
                addView(streamPanel, lp(top = 16))
                addView(switches)
            }
            val right = column().apply {
                addView(quality)
                addView(aspectSection)
                addView(receiversSection)
            }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Ui.BG)
                addView(scrolling(left, 560, short), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(scrolling(right, 560, short), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            setContentView(root)
        } else {
            val col = column().apply {
                addView(head)
                addView(streamPanel, lp(top = 16))
                addView(quality)
                addView(aspectSection)
                addView(switches)
                addView(receiversSection)
            }
            setContentView(scrolling(col, 640, short))
        }
    }

    private fun buildHeader(col: LinearLayout) {
        col.addView(Ui.label(this, "Send screen", 28f, bold = true))
        col.addView(
            Ui.label(this, "Pick a device that is in Receive mode on the same Wi-Fi. You can then leave this app — the stream keeps running.", 14f, Ui.MUTED),
            lp(top = 4)
        )
        errorBanner = Ui.label(this, "", 14f, Ui.DANGER).apply { visibility = View.GONE }
        col.addView(errorBanner, lp(top = 16))
    }

    private fun buildStreamPanel() {
        streamPanel = Ui.card(this, selected = true).apply { visibility = View.GONE }
        statusText = Ui.label(this, "", 20f, bold = true)
        detailText = Ui.label(this, "", 14f, Ui.MUTED)
        audioText = Ui.label(this, "", 14f, Ui.MUTED)
        notesText = Ui.label(this, "", 14f, Ui.WARN).apply { visibility = View.GONE }
        congestionText = Ui.label(
            this, "The network can't keep up — the picture is being slowed down to match. Choose a lower resolution if it stays choppy.", 14f, Ui.WARN
        ).apply { visibility = View.GONE }
        streamPanel.addView(statusText)
        streamPanel.addView(detailText, lp(top = 4))
        streamPanel.addView(audioText, lp(top = 2))
        streamPanel.addView(notesText, lp(top = 6))
        streamPanel.addView(congestionText, lp(top = 8))
        streamPanel.addView(
            Ui.label(this, "Press Home to leave this app — streaming continues in the background.", 13f, Ui.MUTED),
            lp(top = 8)
        )
        streamPanel.addView(
            Ui.button(this, "Stop streaming", danger = true) { startService(StreamService.stopIntent(this)) },
            lp(top = 12)
        )
    }

    private fun buildQualitySection(col: LinearLayout, top: Int) {
        col.addView(Ui.label(this, "Resolution", 18f, bold = true), lp(top = top))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in Preset.values()) {
            val name = Ui.label(this, p.label, 16f, bold = true).apply { gravity = Gravity.CENTER }
            val size = Ui.label(this, "", 12f, Ui.MUTED).apply { gravity = Gravity.CENTER }
            val card = Ui.card(this, onClick = { choosePreset(p) }).apply {
                gravity = Gravity.CENTER
                val pad = Ui.dp(this@SendActivity, 6)
                setPadding(pad, Ui.dp(this@SendActivity, 12), pad, Ui.dp(this@SendActivity, 12))
                addView(name)
                addView(size)
            }
            presetCards[p] = card to size
            row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                val m = Ui.dp(this@SendActivity, 3)
                setMargins(m, 0, m, 0)
            })
        }
        col.addView(row, lp(top = 8))
        col.addView(
            Ui.label(
                this,
                "Native sends your screen at its full pixel size. If the picture stutters, step down. " +
                    "Sizes are shown long side × short side.",
                13f, Ui.MUTED
            ),
            lp(top = 8)
        )
    }

    private fun buildAspectSection(col: LinearLayout, top: Int) {
        col.addView(Ui.label(this, "Picture shape", 18f, bold = true), lp(top = top))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (a in AspectMode.values()) {
            val card = Ui.card(this, onClick = { chooseAspect(a) }).apply {
                gravity = Gravity.CENTER
                val pad = Ui.dp(this@SendActivity, 6)
                setPadding(pad, Ui.dp(this@SendActivity, 12), pad, Ui.dp(this@SendActivity, 12))
                addView(Ui.label(this@SendActivity, a.label, 14f, bold = true).apply { gravity = Gravity.CENTER })
            }
            aspectCards[a] = card
            row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                val m = Ui.dp(this@SendActivity, 3)
                setMargins(m, 0, m, 0)
            })
        }
        col.addView(row, lp(top = 8))
        aspectHint = Ui.label(this, "", 13f, Ui.MUTED)
        col.addView(aspectHint, lp(top = 8))
    }

    private fun buildSwitches(col: LinearLayout) {
        audioSwitch = Switch(this).apply {
            text = "Send sound too (uncompressed)"
            textSize = 16f
            setTextColor(Ui.TEXT)
            isChecked = Prefs.audio(this@SendActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAudio(this@SendActivity, on) }
        }
        audioCard = Ui.card(this).apply { addView(audioSwitch) }
        col.addView(audioCard, lp(top = 12))

        muteSwitch = Switch(this).apply {
            text = "Silence this phone while streaming"
            textSize = 16f
            setTextColor(Ui.TEXT)
            isChecked = Prefs.mutePhone(this@SendActivity)
            setOnCheckedChangeListener { _, on ->
                if (syncingSwitch) return@setOnCheckedChangeListener
                Prefs.setMutePhone(this@SendActivity, on)
                if (StreamState.snapshot.phase != Phase.IDLE) startService(StreamService.muteIntent(this@SendActivity, on))
            }
        }
        muteHint = Ui.label(this, "", 13f, Ui.MUTED)
        muteCard = Ui.card(this).apply {
            addView(muteSwitch)
            addView(muteHint, lp(top = 4))
        }
        col.addView(muteCard, lp(top = 12))
    }

    private fun buildReceiversSection(top: Int) {
        receiversSection = column()
        receiversSection.addView(Ui.label(this, "Receivers on this network", 18f, bold = true), lp(top = top))
        scanHint = Ui.label(this, "Searching… open PhoneStream on your TV and choose Receive.", 14f, Ui.MUTED)
        receiversSection.addView(scanHint, lp(top = 8))
        listBox = column()
        receiversSection.addView(listBox, lp(top = 8))
        receiversSection.addView(
            Ui.button(this, "Enter IP address manually") { askForIp() },
            lp(top = 12)
        )
    }

    // ---- state rendering -----------------------------------------------------------------------------

    private fun render(s: StreamSnapshot) {
        val active = s.phase != Phase.IDLE
        streamPanel.visibility = if (active) View.VISIBLE else View.GONE
        receiversSection.visibility = if (active) View.GONE else View.VISIBLE
        audioCard.visibility = if (active) View.GONE else View.VISIBLE
        // Silencing only means something while sound is being sent to the TV.
        muteCard.visibility = if (!active || s.audioOn) View.VISIBLE else View.GONE

        if (!active && s.error != null) {
            errorBanner.text = s.error
            errorBanner.visibility = View.VISIBLE
        } else {
            errorBanner.visibility = View.GONE
        }

        if (active) {
            statusText.text = if (s.phase == Phase.STREAMING) "Streaming to ${s.receiverName}" else s.status
            detailText.text = if (s.width > 0)
                "${resolutionLabel(s.width, s.height)} @ ${s.fps} fps · ${s.codec} · ${"%.1f".format(s.mbps)} Mbit/s"
            else ""
            detailText.visibility = if (s.width > 0) View.VISIBLE else View.GONE
            audioText.text = when {
                s.audioOn -> if (s.phoneMuted) "Sound: on the TV · this phone is silent" else "Sound: on"
                s.audioNote.isNotEmpty() -> s.audioNote
                else -> ""
            }
            audioText.visibility = if (audioText.text.isEmpty()) View.GONE else View.VISIBLE
            val notes = listOf(s.aspectNote, s.muteNote).filter { it.isNotEmpty() }.joinToString("\n")
            notesText.text = notes
            notesText.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
            congestionText.visibility = if (s.congested) View.VISIBLE else View.GONE

            if (muteSwitch.isChecked != s.muteWanted) {
                syncingSwitch = true
                muteSwitch.isChecked = s.muteWanted
                syncingSwitch = false
            }
        }
        muteHint.text = if (Prefs.muteSupport(this) == 2)
            "This phone can't be silenced without also silencing the stream, so it stays audible."
        else "The TV plays the sound instead. The volume is put back when you stop."

        val (nw, nh) = if (s.nativeW > 0) s.nativeW to s.nativeH else realSize()
        val selected = if (active) s.preset else preset
        val selectedAspect = if (active) s.aspect else aspect
        for ((p, views) in presetCards) {
            val (w, h) = Planner.outputSize(nw, nh, p, selectedAspect)
            views.second.text = resolutionLabel(w, h)
        }
        if (shownPreset != selected) {
            shownPreset = selected
            for ((p, views) in presetCards) Ui.setSelected(this, views.first, p == selected)
        }
        if (shownAspect != selectedAspect) {
            shownAspect = selectedAspect
            for ((a, card) in aspectCards) Ui.setSelected(this, card, a == selectedAspect)
        }
        aspectHint.text = aspectHintText(selectedAspect, nw, nh)
    }

    private fun aspectHintText(mode: AspectMode, nw: Int, nh: Int): String = when {
        mode == AspectMode.ORIGINAL -> mode.hint
        nw <= nh -> "Your phone is upright, so the picture keeps its shape. ${mode.label} applies when you hold it sideways."
        !Planner.reshapes(nw, nh, mode) -> "This screen is already 16:9, so nothing changes."
        else -> mode.hint
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val m = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun choosePreset(p: Preset) {
        preset = p
        Prefs.setPreset(this, p)
        if (StreamState.snapshot.phase != Phase.IDLE) startService(StreamService.presetIntent(this, p))
        render(StreamState.snapshot)
    }

    private fun chooseAspect(a: AspectMode) {
        aspect = a
        Prefs.setAspect(this, a)
        if (StreamState.snapshot.phase != Phase.IDLE) startService(StreamService.aspectIntent(this, a))
        render(StreamState.snapshot)
    }

    // ---- receivers ------------------------------------------------------------------------------------

    private fun onReceivers(list: List<Receiver>) {
        if (list == receivers) return // don't rebuild (and steal D-pad focus) when nothing changed
        receivers = list
        renderReceivers()
    }

    private fun renderReceivers() {
        val focusedTag = currentFocus?.tag as? String
        listBox.removeAllViews()
        scanHint.visibility = if (receivers.isEmpty()) View.VISIBLE else View.GONE
        for (r in receivers) {
            val item = Ui.card(this, onClick = { startStreamTo(r) }).apply { tag = r.key }
            item.addView(Ui.label(this, r.name, 18f, bold = true))
            item.addView(Ui.label(this, r.host, 13f, Ui.MUTED))
            listBox.addView(item, lp(top = 8))
        }
        if (focusedTag != null) listBox.findViewWithTag<View>(focusedTag)?.requestFocus()
    }

    private fun askForIp() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.1.50"
            setText(Prefs.lastIp(this@SendActivity))
            setSingleLine()
            val p = Ui.dp(this@SendActivity, 20)
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this)
            .setTitle("Receiver IP address")
            .setMessage("Shown on the receiving device's screen.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    Prefs.setLastIp(this, text)
                    val host = text.substringBefore(':')
                    val port = text.substringAfter(':', "").toIntOrNull() ?: Proto.DEFAULT_PORT
                    startStreamTo(Receiver(host, host, port))
                }
            }
            .show()
    }

    // ---- starting a stream: permissions -> screen-capture consent -> service --------------------------

    private fun startStreamTo(r: Receiver) {
        pending = r
        val need = ArrayList<String>()
        if (audioSwitch.isChecked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) launchCapture() else requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) launchCapture() // denied sound / notifications just means no sound / no notification text
    }

    private fun launchCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val intent = if (Build.VERSION.SDK_INT >= 34)
                mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mgr.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_CAPTURE)
        } catch (e: Exception) {
            Toast.makeText(this, "Screen capture is not available on this device", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val r = pending ?: return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen sharing was not allowed", Toast.LENGTH_SHORT).show()
            return
        }
        val mute = muteSwitch.isChecked
        StreamState.update {
            it.copy(
                phase = Phase.CONNECTING, receiverName = r.name, status = "Starting…", error = null,
                width = 0, height = 0, audioNote = "", audioOn = false, congested = false, preset = preset,
                aspect = aspect, reshaped = false, aspectNote = "", phoneMuted = false, muteWanted = mute, muteNote = "",
            )
        }
        try {
            startForegroundService(StreamService.startIntent(this, r, preset, aspect, audioSwitch.isChecked, mute, resultCode, data))
        } catch (e: Exception) {
            StreamState.update {
                StreamSnapshot(
                    error = "Could not start the streaming service: ${e.message}",
                    preset = it.preset, aspect = it.aspect, muteWanted = it.muteWanted,
                )
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_CAPTURE = 2
    }
}
