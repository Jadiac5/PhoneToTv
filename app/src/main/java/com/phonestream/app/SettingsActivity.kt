package com.phonestream.app

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.phonestream.app.ui.Ui
import com.phonestream.app.update.UpdateAction
import com.phonestream.app.update.UpdateManager
import com.phonestream.app.update.UpdatePhase
import com.phonestream.app.update.UpdateState
import com.phonestream.app.update.UpdateUi
import com.phonestream.app.update.Updates
import kotlin.math.max

/** Settings: the one update button, how updates are checked, and what the phone-speaker test found. */
class SettingsActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var notesText: TextView
    private lateinit var checkedText: TextView
    private lateinit var updateButton: TextView
    private lateinit var muteStatus: TextView
    private lateinit var muteButton: TextView
    private lateinit var syncValue: TextView

    private val updateListener: (UpdateState) -> Unit = { renderUpdate(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        UpdateManager.addListener(this, updateListener)
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.onResume(this) // carries on after the user allowed "install unknown apps"
        renderMute()
    }

    override fun onStop() {
        UpdateManager.removeListener(updateListener)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        renderUpdate(UpdateManager.state)
    }

    // ---- UI -------------------------------------------------------------------------------------------

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Ui.dp(this@SettingsActivity, top) }

    private fun buildUi() {
        val wide = Ui.wide(this)
        val short = Ui.short(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val side = max(Ui.dp(this, 20), ((r - l) - Ui.dp(this, if (wide) 980 else 640)) / 2)
            val top = Ui.dp(this, if (short) 12 else 24)
            if (col.paddingLeft != side || col.paddingTop != top) col.setPadding(side, top, side, Ui.dp(this, 32))
        }

        col.addView(Ui.label(this, "Settings", 28f, bold = true))

        val updates = buildUpdatesCard()
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildAutoCheckCard())
            addView(buildMuteCard(), lp(top = 12))
            addView(buildSyncCard(), lp(top = 12))
            addView(
                Ui.label(
                    this@SettingsActivity,
                    "PhoneStream ${UpdateManager.installedVersion(this@SettingsActivity)} · github.com/${Updates.REPO}",
                    12f, Ui.MUTED
                ),
                lp(top = 16)
            )
        }

        if (wide) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = Ui.dp(this, 8)
            row.addView(updates, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = gap })
            row.addView(options, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = gap })
            col.addView(row, lp(top = 16))
        } else {
            col.addView(updates, lp(top = 16))
            col.addView(options, lp(top = 12))
        }

        setContentView(scroll)
        updateButton.requestFocus()
    }

    private fun buildUpdatesCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.label(this, "Updates", 18f, bold = true))
        card.addView(
            Ui.label(this, "Installed: version ${UpdateManager.installedVersion(this)}", 14f, Ui.MUTED),
            lp(top = 4)
        )
        statusText = Ui.label(this, "", 15f)
        card.addView(statusText, lp(top = 10))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 8)).apply {
            topMargin = Ui.dp(this@SettingsActivity, 8)
        })
        notesText = Ui.label(this, "", 13f, Ui.MUTED)
        card.addView(notesText, lp(top = 8))
        updateButton = Ui.button(this, "Check for updates", primary = true) { onUpdateButton() }
        card.addView(updateButton, lp(top = 14))
        checkedText = Ui.label(this, "", 12f, Ui.MUTED)
        card.addView(checkedText, lp(top = 8))
        return card
    }

    private fun buildAutoCheckCard(): LinearLayout {
        val sw = Switch(this).apply {
            text = "Look for updates when the app opens"
            textSize = 16f
            setTextColor(Ui.TEXT)
            isChecked = Prefs.autoCheck(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAutoCheck(this@SettingsActivity, on) }
        }
        return Ui.card(this).apply { addView(sw) }
    }

    private fun buildMuteCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.label(this, "Phone speaker while streaming", 18f, bold = true))
        muteStatus = Ui.label(this, "", 14f, Ui.MUTED)
        card.addView(muteStatus, lp(top = 4))
        muteButton = Ui.button(this, "Test again") {
            Prefs.setMuteSupport(this, 0)
            renderMute()
        }
        card.addView(muteButton, lp(top = 12))
        renderMute()
        return card
    }

    private fun buildSyncCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.label(this, "Picture and sound in step", 18f, bold = true))
        card.addView(
            Ui.label(
                this,
                "On the TV the picture follows the sound automatically. If it is still early or late (a soundbar or " +
                    "Bluetooth speaker adds delay), nudge it here: plus delays the picture.",
                14f, Ui.MUTED
            ),
            lp(top = 4)
        )
        syncValue = Ui.label(this, "", 16f, bold = true)
        card.addView(syncValue, lp(top = 10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gap = Ui.dp(this, 8)
        row.addView(
            Ui.button(this, "Earlier") { nudgeSync(-Prefs.SYNC_STEP_MS) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = gap }
        )
        row.addView(
            Ui.button(this, "Later") { nudgeSync(Prefs.SYNC_STEP_MS) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = gap }
        )
        card.addView(row, lp(top = 10))
        renderSync()
        return card
    }

    private fun nudgeSync(delta: Int) {
        Prefs.setSyncOffsetMs(this, Prefs.syncOffsetMs(this) + delta)
        renderSync()
    }

    private fun renderSync() {
        if (!::syncValue.isInitialized) return
        val v = Prefs.syncOffsetMs(this)
        syncValue.text = when {
            v == 0 -> "Picture delay: none (automatic)"
            v > 0 -> "Picture delay: +$v ms"
            else -> "Picture delay: $v ms"
        }
    }

    // ---- state ----------------------------------------------------------------------------------------

    private fun onUpdateButton() {
        when (UpdateUi.action(UpdateManager.state)) {
            UpdateAction.CHECK -> UpdateManager.check(this)
            UpdateAction.UPDATE -> UpdateManager.startUpdate(this)
            UpdateAction.NONE -> {}
        }
    }

    private fun renderUpdate(s: UpdateState) {
        updateButton.text = UpdateUi.buttonLabel(s)
        updateButton.alpha = if (UpdateUi.action(s) == UpdateAction.NONE) 0.6f else 1f

        val status = UpdateUi.status(s)
        statusText.text = status
        statusText.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        statusText.setTextColor(
            when (s.phase) {
                UpdatePhase.ERROR -> Ui.DANGER
                UpdatePhase.UP_TO_DATE -> Ui.OK
                else -> Ui.TEXT
            }
        )

        progress.visibility = if (s.phase == UpdatePhase.DOWNLOADING) View.VISIBLE else View.GONE
        progress.progress = s.progress

        val notes = if (s.phase != UpdatePhase.CHECKING && s.phase != UpdatePhase.UP_TO_DATE) s.info?.notes.orEmpty() else ""
        notesText.text = if (notes.isEmpty()) "" else "What's new:\n" + UpdateUi.shortNotes(notes)
        notesText.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE

        val at = Prefs.lastCheckAt(this)
        checkedText.text = if (at > 0) "Last checked: " + DateUtils.getRelativeTimeSpanString(
            at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ) else "Not checked yet"
    }

    private fun renderMute() {
        if (!::muteStatus.isInitialized) return
        muteStatus.text = when (Prefs.muteSupport(this)) {
            1 -> "Works on this phone: it stays silent while the TV plays the sound."
            2 -> "This phone can't be silenced without also silencing the stream, so it stays audible while streaming."
            else -> "Not tested yet. PhoneStream checks this the first time you stream with sound."
        }
        muteButton.visibility = if (Prefs.muteSupport(this) == 0) View.GONE else View.VISIBLE
    }
}
