package com.phonestream.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small helpers for building the (programmatic, D-pad friendly) UI without any support libraries. */
object Ui {
    val BG = 0xFF0D1117.toInt()
    val CARD = 0xFF161B22.toInt()
    val CARD_FOCUS = 0xFF1F2A3D.toInt()
    val BORDER = 0xFF30363D.toInt()
    val ACCENT = 0xFF4C8DFF.toInt()
    val TEXT = 0xFFE6EDF3.toInt()
    val MUTED = 0xFF8B949E.toInt()
    val DANGER = 0xFFF85149.toInt()
    val OK = 0xFF3FB950.toInt()
    val WARN = 0xFFD29922.toInt()

    fun isTv(c: Context): Boolean {
        val ui = c.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    /** Landscape with room for two columns (a landscape phone, or a TV). */
    fun wide(c: Context): Boolean {
        val cfg = c.resources.configuration
        return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE && cfg.screenWidthDp >= 560
    }

    /** Not much height to spare (a phone held sideways): use tighter spacing. */
    fun short(c: Context): Boolean = c.resources.configuration.screenHeightDp < 480

    /** A round gear button for the corner of the start screen. Focusable, so the TV remote can reach it. */
    fun gear(c: Context, onClick: () -> Unit): View = GearView(c).apply {
        isFocusable = true
        isClickable = true
        contentDescription = "Settings"
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(c, CARD_FOCUS, ACCENT, 3, 24))
            addState(intArrayOf(android.R.attr.state_pressed), shape(c, CARD_FOCUS, ACCENT, 2, 24))
            addState(intArrayOf(), shape(c, CARD, BORDER, 1, 24))
        }
        setOnClickListener { onClick() }
    }

    fun shape(c: Context, fill: Int, stroke: Int, strokeDp: Int, radiusDp: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(c, strokeDp), stroke)
        }

    /** Background that highlights clearly when focused with a remote / keyboard. */
    fun focusBackground(c: Context, base: Int, selected: Boolean = false, radiusDp: Int = 14): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(c, CARD_FOCUS, ACCENT, 3, radiusDp))
            addState(intArrayOf(android.R.attr.state_pressed), shape(c, CARD_FOCUS, ACCENT, 2, radiusDp))
            addState(
                intArrayOf(),
                shape(c, base, if (selected) ACCENT else BORDER, if (selected) 2 else 1, radiusDp)
            )
        }

    fun label(c: Context, text: CharSequence, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** A focusable, clickable vertical container. */
    fun card(c: Context, selected: Boolean = false, onClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(c, 16)
            setPadding(p, p, p, p)
            background = focusBackground(c, CARD, selected)
            if (onClick != null) {
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    fun setSelected(c: Context, v: View, selected: Boolean) {
        v.background = focusBackground(c, CARD, selected)
    }

    fun button(c: Context, text: CharSequence, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(c).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val color = if (danger) DANGER else ACCENT
            setTextColor(if (primary || danger) 0xFFFFFFFF.toInt() else TEXT)
            val ph = dp(c, 20)
            val pv = dp(c, 12)
            setPadding(ph, pv, ph, pv)
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(c, if (primary || danger) color else CARD_FOCUS, 0xFFFFFFFF.toInt(), 3, 12))
                addState(intArrayOf(android.R.attr.state_pressed), shape(c, CARD_FOCUS, ACCENT, 2, 12))
                addState(intArrayOf(), shape(c, if (primary || danger) color else CARD, BORDER, 1, 12))
            }
            setOnClickListener { onClick() }
        }

    fun space(c: Context, heightDp: Int): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(c, heightDp))
    }
}

/** A cog drawn by hand (8 teeth and a hole), so there is no icon resource or library to depend on. */
class GearView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; style = Paint.Style.FILL }
    private val path = Path()

    init {
        minimumWidth = Ui.dp(c, 48)
        minimumHeight = Ui.dp(c, 48)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = Ui.dp(context, 48)
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) * 0.30f
        val inner = outer * 0.78f
        val hole = outer * 0.38f
        val teeth = 8
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        // Outline: for every tooth, up to the tip, across the top, down again, then along the root circle.
        for (i in 0 until teeth) {
            val a = i * 2 * PI / teeth
            val half = PI / teeth * 0.45
            val pts = arrayOf(
                a - half * 1.6 to inner, a - half to outer, a + half to outer, a + half * 1.6 to inner,
            )
            for ((j, p) in pts.withIndex()) {
                val x = cx + (p.second * cos(p.first)).toFloat()
                val y = cy + (p.second * sin(p.first)).toFloat()
                if (i == 0 && j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        path.close()
        path.addCircle(cx, cy, hole, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }
}
