package com.stasisstate.slatetv.connector.homeassistant

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Puts the doorbell alert on screen. With "Display over other apps" access it is drawn as a
 * focusable corner overlay window, so whatever is playing keeps playing underneath. Otherwise it
 * opens [DoorbellAlertActivity], a translucent activity in its own task.
 */
internal object DoorbellAlertPresenter {
    private const val TAG = "SlateHA"
    private var overlayView: DoorbellAlertView? = null

    /** The ring the activity host should show (set before launching it). */
    @Volatile var pendingRing: DoorbellRing? = null
        private set

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, ring: DoorbellRing) {
        val app = context.applicationContext
        val config = HomeAssistantRuntime.config.value
        overlayView?.let {
            it.bind(ring, config)
            return
        }
        DoorbellAlertActivity.current?.let {
            it.show(ring)
            return
        }
        if (canDrawOverlays(app) && showOverlay(app, ring, config)) return
        pendingRing = ring
        runCatching {
            app.startActivity(
                Intent(app, DoorbellAlertActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                ),
            )
        }.onFailure { Log.w(TAG, "Could not open doorbell alert: ${it.javaClass.simpleName}") }
    }

    fun consumePendingRing(): DoorbellRing? = pendingRing.also { pendingRing = null }

    private fun showOverlay(context: Context, ring: DoorbellRing, config: HaConfig): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val view = DoorbellAlertView(context) { dismissOverlay(context) }
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            },
            // Focusable (no FLAG_NOT_FOCUSABLE) so the D-pad and Back go to the alert, but not
            // touch-modal so the rest of the screen stays interactive on touch devices.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (40 * density).toInt()
            y = (36 * density).toInt()
            title = "Slate TV doorbell"
            windowAnimations = android.R.style.Animation_Toast
        }
        return runCatching {
            wm.addView(view, params)
            overlayView = view
            view.bind(ring, config)
            true
        }.getOrElse {
            Log.w(TAG, "Overlay window refused: ${it.javaClass.simpleName}")
            view.release()
            false
        }
    }

    private fun dismissOverlay(context: Context) {
        val view = overlayView ?: return
        overlayView = null
        view.release()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeView(view) }
    }

    /** Closes any visible alert (overlay or activity). */
    fun dismissAll(context: Context) {
        dismissOverlay(context.applicationContext)
        DoorbellAlertActivity.current?.finish()
    }
}
