package com.stasisstate.slatetv.plugin.jellyfin

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

abstract class JellyfinTvActivity : AppCompatActivity() {
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

    protected fun settings() = startActivity(Intent(this, JellyfinSettingsActivity::class.java))
    protected fun guide() = startActivity(Intent(this, JellyfinGuideActivity::class.java))

    protected fun launchJellyfin() {
        val opened = runCatching {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(JELLYFIN_TV)
                ?: packageManager.getLaunchIntentForPackage(JELLYFIN_TV)
                ?: return@runCatching false
            startActivity(intent)
            true
        }.getOrDefault(false)
        if (!opened) Toast.makeText(this, "Install Jellyfin for Android TV to watch channels.", Toast.LENGTH_LONG).show()
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
