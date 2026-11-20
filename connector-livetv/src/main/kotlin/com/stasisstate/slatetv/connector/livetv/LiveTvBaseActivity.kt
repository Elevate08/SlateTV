package com.stasisstate.slatetv.connector.livetv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.stasisstate.slatetv.connector.livetv.LiveTvSettingsUi as Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Slate TV dark architectural palette for the 10-foot settings menu. */
internal object LiveTvSettingsUi {
    const val BACKGROUND = 0xff0a0d12.toInt()
    const val CARD = 0xff141923.toInt()
    const val CARD_BORDER = 0xff1f2735.toInt()
    const val CARD_FOCUS = 0xff1d2635.toInt()
    const val CARD_SELECTED_BORDER = 0xff2f8aa0.toInt()
    const val ACCENT = 0xff78dcf0.toInt()
    const val TEXT = 0xffebf4ff.toInt()
    const val MUTED = 0xff8fa0b8.toInt()
    const val OK = 0xff6fe3a1.toInt()
    const val WARN = 0xffffc66d.toInt()
    const val BAD = 0xffff7b72.toInt()
}

abstract class LiveTvBaseActivity : AppCompatActivity() {
    protected class Row(val view: LinearLayout, val title: TextView, val value: TextView)

    protected val scope = MainScope()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    protected fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    protected fun rootColumn(): LinearLayout = column().apply {
        setBackgroundColor(Color.rgb(16, 24, 39))
        setPadding(dp(36), dp(22), dp(36), dp(22))
    }

    protected fun label(text: String, size: Float = 18f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(235, 244, 255))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    protected fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        setOnClickListener { action() }
    }

    protected fun settings() = startActivity(Intent(this, LiveTvSettingsActivity::class.java))
    protected fun guide() = startActivity(Intent(this, LiveTvGuideActivity::class.java))

    protected fun tuneChannel(channelId: Long) {
        scope.launch {
            val opened = withContext(Dispatchers.IO) { LiveTvChannelTuner.tuneSystemChannel(this@LiveTvBaseActivity, channelId) }
            if (!opened) {
                Toast.makeText(this@LiveTvBaseActivity, "Could not open Live Channels for this channel.", Toast.LENGTH_LONG).show()
            }
        }
    }

    protected fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    protected fun sectionHeader(value: String) = text(value, 14f, Ui.ACCENT, bold = true).apply {
        letterSpacing = 0.18f
        setPadding(dp(4), dp(26), 0, dp(8))
        isFocusable = false
    }

    protected fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }

    protected fun row(title: String, value: String?, centered: Boolean = false, onClick: () -> Unit): Row {
        val titleView = text(title, 19f, Ui.TEXT, bold = true)
        val valueView = text(value.orEmpty(), 16f, Ui.MUTED).apply {
            visibility = if (value == null) View.GONE else View.VISIBLE
        }
        val view = column().apply {
            background = cardBackground(Ui.CARD, Ui.CARD_BORDER, focusStroke = Ui.ACCENT)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            minimumHeight = dp(56)
            gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            addView(titleView)
            addView(valueView)
        }
        if (centered) titleView.gravity = Gravity.CENTER
        return Row(view, titleView, valueView)
    }

    protected fun cardBackground(
        fill: Int,
        stroke: Int,
        focusStroke: Int = stroke,
        radius: Int = 12,
        borderWidth: Int = 1,
    ) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(Ui.CARD_FOCUS.takeIf { fill == Ui.CARD } ?: fill, focusStroke, radius, 3))
        addState(intArrayOf(), shape(fill, stroke, radius, borderWidth))
    }

    private fun shape(fill: Int, stroke: Int, radius: Int, width: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(width), stroke)
    }

    protected fun focusBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), tile(0xff294860.toInt(), 0xff78dcf0.toInt()))
        addState(intArrayOf(), tile(0xff19263a.toInt(), 0xff28384d.toInt()))
    }

    private fun tile(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(2), stroke)
    }
}
