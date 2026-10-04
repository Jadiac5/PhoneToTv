package com.phonestream.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phonestream.app.net.DeviceInfo
import com.phonestream.app.receive.ReceiveActivity
import com.phonestream.app.send.PhoneMute
import com.phonestream.app.send.SendActivity
import com.phonestream.app.ui.Ui
import com.phonestream.app.update.UpdateAction
import com.phonestream.app.update.UpdateManager
import com.phonestream.app.update.UpdateState
import com.phonestream.app.update.UpdateUi
import kotlin.math.max

/** First screen: is this device the one that sends its screen, or the one that shows it? */
class MainActivity : Activity() {
    private var pill: TextView? = null
    private val updateListener: (UpdateState) -> Unit = { renderPill(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        PhoneMute.restoreLeftover(this) // gives the volume back if a stream died without doing so
        buildUi()
        UpdateManager.autoCheck(this)
    }

    override fun onStart() {
        super.onStart()
        UpdateManager.addListener(this, updateListener)
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.onResume(this)
    }

    override fun onStop() {
        UpdateManager.removeListener(updateListener)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        renderPill(UpdateManager.state)
    }

    private fun buildUi() {
        val tv = Ui.isTv(this)
        val wide = Ui.wide(this)
        val short = Ui.short(this)
        val horizontal = tv || wide
        val preferred = Prefs.lastMode(this) ?: if (tv) Prefs.MODE_RECEIVE else Prefs.MODE_SEND

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val side = max(Ui.dp(this, 20), ((r - l) - Ui.dp(this, if (horizontal) 900 else 720)) / 2)
            val vertical = Ui.dp(this, if (short) 16 else 32)
            if (col.paddingLeft != side || col.paddingTop != vertical) col.setPadding(side, vertical, side, vertical)
        }

        col.addView(Ui.label(this, "PhoneStream", if (short) 28f else 34f, bold = true), wrap())
        col.addView(
            Ui.label(this, "Mirror a phone's screen and sound to a TV over your Wi-Fi.", 15f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
            },
            wrap(top = 6)
        )
        col.addView(
            Ui.label(this, "This device: " + DeviceInfo.deviceName(this), 13f, Ui.MUTED),
            wrap(top = 4)
        )

        val row = LinearLayout(this).apply { orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val send = choice(
            "Send", "Share THIS device's screen and sound.\nChoose this on your phone.",
            Prefs.MODE_SEND, short
        ) { open(SendActivity::class.java) }
        val receive = choice(
            "Receive", "Show another device's screen here, fullscreen.\nChoose this on your TV.",
            Prefs.MODE_RECEIVE, short
        ) { open(ReceiveActivity::class.java) }

        val updatePill = Ui.card(this, selected = true, onClick = { onPill() }).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            val t = Ui.label(this@MainActivity, "", 14f, bold = true).apply { gravity = Gravity.CENTER }
            addView(t)
            tag = t
        }
        pill = updatePill.tag as TextView

        val cardLp = { weight: Float ->
            if (horizontal) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                setMargins(Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8), 0)
            } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@MainActivity, if (short) 8 else 12)
            }
        }
        row.addView(send, cardLp(1f))
        row.addView(receive, cardLp(1f))
        // Only shown when an update is known. Beside the cards when there is room, a slim bar below them otherwise.
        if (horizontal) {
            row.addView(updatePill, cardLp(0.6f).apply { gravity = Gravity.CENTER_VERTICAL })
        } else {
            row.addView(updatePill, cardLp(1f))
        }
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@MainActivity, if (short) 14 else 28)
        })

        val gear = Ui.gear(this) { open(SettingsActivity::class.java) }.apply { id = View.generateViewId() }
        val first = if (preferred == Prefs.MODE_RECEIVE) receive else send
        for (v in arrayOf(send, receive, updatePill)) {
            v.id = View.generateViewId()
            v.nextFocusUpId = gear.id
        }
        gear.nextFocusDownId = first.id

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(gear, FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.TOP or Gravity.END).apply {
            val m = Ui.dp(this@MainActivity, if (short) 8 else 16)
            setMargins(0, m, m, 0)
        })

        setContentView(root)
        first.requestFocus()
    }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Ui.dp(this@MainActivity, top) }

    private fun choice(title: String, body: String, mode: String, short: Boolean, onClick: () -> Unit): View =
        Ui.card(this, selected = Prefs.lastMode(this) == mode, onClick = onClick).apply {
            val p = Ui.dp(this@MainActivity, if (short) 16 else 24)
            setPadding(p, p, p, p)
            addView(Ui.label(this@MainActivity, title, if (short) 22f else 26f, bold = true))
            addView(Ui.label(this@MainActivity, body, 14f, Ui.MUTED), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(this@MainActivity, 6) })
        }

    // ---- update button ----------------------------------------------------------------------------------

    private fun renderPill(s: UpdateState) {
        val label = pill ?: return
        val text = UpdateUi.pill(s)
        label.text = text.orEmpty()
        (label.parent as View).visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun onPill() {
        if (UpdateUi.action(UpdateManager.state) == UpdateAction.UPDATE) UpdateManager.startUpdate(this)
        else open(SettingsActivity::class.java)
    }

    private fun open(cls: Class<out Activity>) {
        startActivity(Intent(this, cls))
    }
}
