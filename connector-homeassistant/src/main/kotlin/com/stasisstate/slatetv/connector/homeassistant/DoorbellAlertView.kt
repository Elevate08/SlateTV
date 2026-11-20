package com.stasisstate.slatetv.connector.homeassistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The doorbell alert card: a picture-in-picture style live camera tile with quick-reply buttons.
 * D-pad Left/Right picks a reply, OK sends it, Back (or the Dismiss button) closes the alert.
 * Hosted either in an overlay window (over other apps) or in [DoorbellAlertActivity].
 */
@SuppressLint("ViewConstructor")
internal class DoorbellAlertView(
    context: Context,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val titleView: TextView
    private val cameraFrame: FrameLayout
    private val cameraImage: ImageView
    private val cameraStatus: TextView
    private val buttonRow: LinearLayout
    private val statusView: TextView
    private val countdown: ProgressBar
    private var feed: CameraLiveFeed? = null
    private var timeoutJob: Job? = null
    private var countdownAnimator: ValueAnimator? = null
    private var pulse: ObjectAnimator? = null
    private var ring: DoorbellRing? = null
    private var sending = false

    init {
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), BORDER)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(12).toFloat()
        }
        addView(card, LayoutParams(dp(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ALERT_RED)
            }
        }
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
        header.addView(TextView(context).apply {
            text = "DOORBELL"
            textSize = 12f
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ALERT_RED)
        })
        titleView = TextView(context).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, 0, 0)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)

        cameraFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        cameraImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Live camera view"
        }
        cameraFrame.addView(cameraImage, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cameraStatus = TextView(context).apply {
            textSize = 14f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
        }
        cameraFrame.addView(cameraStatus, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cameraFrame.addView(TextView(context).apply {
            text = "● LIVE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xCCB91C1C.toInt())
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(10), dp(10), 0, 0)
        })
        val cameraWidth = dp(CARD_WIDTH_DP - 32)
        card.addView(cameraFrame, LinearLayout.LayoutParams(cameraWidth, cameraWidth * 9 / 16).apply {
            topMargin = dp(12)
        })

        buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Replies scroll sideways when they don't fit; padding leaves room for the focus zoom.
        val buttonScroller = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(buttonRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(buttonScroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            marginStart = -dp(4)
            marginEnd = -dp(4)
        })

        statusView = TextView(context).apply {
            textSize = 13f
            setTextColor(TEXT_MUTED)
            text = HINT
            setPadding(dp(2), dp(10), 0, 0)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(statusView)

        countdown = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(TEXT_MUTED)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(BORDER)
        }
        card.addView(countdown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
            topMargin = dp(8)
        })
    }

    /** Shows [ring], replacing any alert already on screen. */
    fun bind(ring: DoorbellRing, config: HaConfig) {
        this.ring = ring
        sending = false
        titleView.text = ring.title
        contentDescription = "Doorbell: ${ring.title}"
        statusView.text = HINT
        statusView.setTextColor(TEXT_MUTED)

        buttonRow.removeAllViews()
        val replies = config.quickReplies.take(HaConfig.MAX_QUICK_REPLIES)
        replies.forEach { reply -> addButton(reply.label, primary = false) { send(reply) } }
        addButton("Dismiss", primary = true) { onDismiss() }
        buttonRow.getChildAt(0)?.post { buttonRow.getChildAt(0)?.requestFocus() }

        feed?.stop()
        feed = null
        val camera = ring.cameraEntityId
        if (camera == null) {
            cameraImage.setImageDrawable(null)
            cameraStatus.text = "No camera linked to this doorbell"
        } else {
            cameraStatus.text = "Connecting to camera…"
            feed = CameraLiveFeed(
                api = HomeAssistantRuntime.api(config),
                entityId = camera,
                targetWidthPx = dp(CARD_WIDTH_DP),
                onFrame = { cameraImage.setImageBitmap(it) },
                onStatus = { message ->
                    cameraStatus.text = message.orEmpty()
                    cameraStatus.visibility = if (message == null) View.GONE else View.VISIBLE
                },
            ).also { it.start(scope) }
        }
        pulse?.start()
        restartTimeout(config.alertTimeoutSeconds)
    }

    private fun restartTimeout(seconds: Int) {
        timeoutJob?.cancel()
        countdownAnimator?.cancel()
        val millis = seconds.coerceAtLeast(5) * 1000L
        countdownAnimator = ValueAnimator.ofInt(1000, 0).apply {
            duration = millis
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { countdown.progress = it.animatedValue as Int }
            start()
        }
        timeoutJob = scope.launch {
            delay(millis)
            onDismiss()
        }
    }

    private fun send(reply: QuickReply) {
        val ring = ring ?: return
        if (sending) return
        sending = true
        statusView.setTextColor(TEXT_MUTED)
        statusView.text = "Sending “${reply.label}”…"
        scope.launch {
            val result = HomeAssistantRuntime.sendReply(ring, reply)
            sending = false
            if (result.isSuccess) {
                statusView.setTextColor(SUCCESS)
                statusView.text = "✓ Sent “${reply.label}”"
                timeoutJob?.cancel()
                countdownAnimator?.cancel()
                delay(1_400)
                onDismiss()
            } else {
                statusView.setTextColor(ERROR)
                statusView.text = "Couldn't send — ${result.exceptionOrNull()?.message ?: "error"}. Back to dismiss."
            }
        }
    }

    private fun addButton(label: String, primary: Boolean, action: () -> Unit) {
        val button = TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minHeight = dp(40)
            minWidth = dp(72)
            val textColors = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(BG, TEXT_PRIMARY),
            )
            setTextColor(textColors)
            background = StateListDrawable().apply {
                fun shape(fill: Int, stroke: Int?) = GradientDrawable().apply {
                    setColor(fill)
                    cornerRadius = dp(20).toFloat()
                    stroke?.let { setStroke(dp(1), it) }
                }
                addState(intArrayOf(android.R.attr.state_focused), shape(TEXT_PRIMARY, null))
                addState(intArrayOf(), shape(if (primary) BUTTON_DISMISS else BUTTON, BORDER))
            }
            setOnClickListener { action() }
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (buttonRow.childCount > 0) params.marginStart = dp(8)
        buttonRow.addView(button, params)
    }

    // Android 13+ with predictive back (the default when targeting API 36 on Android 16) delivers
    // Back to this callback instead of as a key event, including in overlay windows.
    private var backCallback: Any? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback == null) {
            val dispatcher = findOnBackInvokedDispatcher() ?: return
            val callback = OnBackInvokedCallback { onDismiss() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            backCallback = callback
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
        }
        backCallback = null
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onDismiss()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !sending) {
            // Any interaction pauses the auto-dismiss so a reply in progress isn't cut off.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                restartTimeout(HomeAssistantRuntime.config.value.alertTimeoutSeconds)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Stops the live feed and timers. Call when the host removes the view. */
    fun release() {
        feed?.stop()
        feed = null
        pulse?.cancel()
        countdownAnimator?.cancel()
        timeoutJob?.cancel()
        scope.cancel()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val CARD_WIDTH_DP = 400
        private const val HINT = "◀ ▶ choose  ·  OK send  ·  BACK dismiss"
        private val BG = Color.parseColor("#0A0D12")
        private val CARD = Color.parseColor("#F2111620")
        private val BORDER = Color.parseColor("#33FFFFFF")
        private val BUTTON = Color.parseColor("#1F2937")
        private val BUTTON_DISMISS = Color.parseColor("#3B1219")
        private val TEXT_PRIMARY = Color.parseColor("#F8FAFC")
        private val TEXT_MUTED = Color.parseColor("#94A3B8")
        private val ALERT_RED = Color.parseColor("#F87171")
        private val SUCCESS = Color.parseColor("#86EFAC")
        private val ERROR = Color.parseColor("#FCA5A5")
    }
}
