package com.stasisstate.slatetv.connector.homeassistant

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Translucent host for the doorbell alert when overlay access isn't granted. Runs in its own task
 * (see manifest) so the app underneath stays visible and the launcher task isn't brought forward.
 */
class DoorbellAlertActivity : AppCompatActivity() {
    private var alertView: DoorbellAlertView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ring = DoorbellAlertPresenter.consumePendingRing()
        if (ring == null) {
            finish()
            return
        }
        val root = FrameLayout(this)
        val view = DoorbellAlertView(this) { finish() }
        val density = resources.displayMetrics.density
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            marginEnd = (40 * density).toInt()
            bottomMargin = (36 * density).toInt()
        })
        setContentView(root)
        alertView = view
        current = this
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
        view.bind(ring, HomeAssistantRuntime.config.value)
    }

    fun show(ring: DoorbellRing) {
        alertView?.bind(ring, HomeAssistantRuntime.config.value)
    }

    override fun onStop() {
        super.onStop()
        // Pressing Home or switching apps closes the alert rather than leaving it behind.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (current === this) current = null
        alertView?.release()
        alertView = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        @Volatile internal var current: DoorbellAlertActivity? = null
    }
}
