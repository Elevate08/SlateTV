package dev.slatetv.launcher.ui

import android.view.View
import android.widget.ImageView
import dev.slatetv.launcher.wallpaper.LoadedBackdrop

/**
 * Double-buffered GPU crossfade backdrop renderer with Smart Portrait Blur Pillarbox.
 *
 * Uses standard ImageViews (never SurfaceView/TextureView) so HDMI-CEC wakes on
 * Nvidia Shield never lose the GL surface or flash black.
 */
class BackdropRenderer(
    private val layerA: ImageView,
    private val layerB: ImageView,
    private val portraitForeground: ImageView,
    private val dimView: View,
) {
    private var showingA = true
    private var lastRenderedId: String? = null

    fun setDimPercent(percent: Int) {
        dimView.alpha = (percent.coerceIn(0, 60) / 100f)
    }

    fun render(backdrop: LoadedBackdrop, animate: Boolean = true) {
        if (backdrop.mediaId == lastRenderedId) return
        lastRenderedId = backdrop.mediaId

        val incoming = if (showingA) layerB else layerA
        val outgoing = if (showingA) layerA else layerB
        showingA = !showingA

        val backgroundBmp = if (backdrop.isPortrait && backdrop.blurredBackgroundBitmap != null) {
            backdrop.blurredBackgroundBitmap
        } else {
            backdrop.primaryBitmap
        }

        incoming.animate().cancel()
        outgoing.animate().cancel()
        portraitForeground.animate().cancel()

        incoming.setImageBitmap(backgroundBmp)
        incoming.bringToFront()
        portraitForeground.bringToFront()

        val duration = if (animate) CROSSFADE_MS else 0L

        if (duration == 0L) {
            incoming.alpha = 1f
            outgoing.alpha = 0f
            updatePortraitOverlay(backdrop, animate = false)
            return
        }

        incoming.alpha = 0f
        incoming.animate()
            .alpha(1f)
            .setDuration(duration)
            .withEndAction {
                outgoing.alpha = 0f
            }
            .start()

        updatePortraitOverlay(backdrop, animate = true)
    }

    private fun updatePortraitOverlay(backdrop: LoadedBackdrop, animate: Boolean) {
        if (backdrop.isPortrait && backdrop.blurredBackgroundBitmap != null) {
            portraitForeground.setImageBitmap(backdrop.primaryBitmap)
            portraitForeground.visibility = View.VISIBLE
            if (animate) {
                portraitForeground.alpha = 0f
                portraitForeground.animate().alpha(1f).setDuration(CROSSFADE_MS).start()
            } else {
                portraitForeground.alpha = 1f
            }
        } else {
            if (portraitForeground.visibility == View.VISIBLE) {
                if (animate) {
                    portraitForeground.animate()
                        .alpha(0f)
                        .setDuration(CROSSFADE_MS / 2)
                        .withEndAction {
                            portraitForeground.visibility = View.GONE
                            portraitForeground.setImageDrawable(null)
                        }
                        .start()
                } else {
                    portraitForeground.visibility = View.GONE
                    portraitForeground.alpha = 0f
                    portraitForeground.setImageDrawable(null)
                }
            }
        }
    }

    /**
     * Called on Activity resume / HDMI-CEC wake to verify neither layer got stuck mid-fade.
     */
    fun ensureVisibleAfterWake() {
        val active = if (showingA) layerA else layerB
        active.animate().cancel()
        active.alpha = 1f
        if (portraitForeground.visibility == View.VISIBLE) {
            portraitForeground.animate().cancel()
            portraitForeground.alpha = 1f
        }
    }

    companion object {
        private const val CROSSFADE_MS = 650L
    }
}
