package com.stasisstate.slatetv.ui

import android.view.View
import android.widget.ImageView
import com.stasisstate.slatetv.wallpaper.LoadedBackdrop

/**
 * Double-buffered GPU crossfade backdrop renderer with Smart Portrait Blur Pillarbox.
 *
 * Uses standard ImageViews inside an isolated container (never SurfaceView/TextureView)
 * so HDMI-CEC wakes on Android TV never lose the surface or flash black.
 *
 * Hardening:
 * - Releases outgoing drawables immediately after fade completion (keeps peak RAM minimal).
 * - Deduplicates on `(mediaId, smartPortraitBlurApplied)` so toggling portrait blur updates live.
 * - Tracks `activeShowsPortraitOverlay` explicitly so wake recovery NEVER restores a fading-out portrait.
 */
class BackdropRenderer(
    private val layerA: ImageView,
    private val layerB: ImageView,
    private val portraitForeground: ImageView,
    private val dimView: View,
) {
    private var showingA = true
    private var lastRenderKey: Pair<String, Boolean>? = null
    private var activeShowsPortraitOverlay = false

    fun setDimPercent(percent: Int) {
        dimView.alpha = (percent.coerceIn(0, 60) / 100f)
    }

    fun render(backdrop: LoadedBackdrop, animate: Boolean = true) {
        val showPortrait = backdrop.isPortrait && backdrop.blurredBackgroundBitmap != null
        val renderKey = backdrop.mediaId to showPortrait
        if (renderKey == lastRenderKey) return
        lastRenderKey = renderKey
        activeShowsPortraitOverlay = showPortrait

        val incoming = if (showingA) layerB else layerA
        val outgoing = if (showingA) layerA else layerB
        showingA = !showingA

        val backgroundBmp = if (showPortrait) {
            backdrop.blurredBackgroundBitmap!!
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
            outgoing.setImageDrawable(null)
            updatePortraitOverlay(backdrop, showPortrait, animate = false)
            return
        }

        incoming.alpha = 0f
        incoming.animate()
            .alpha(1f)
            .setDuration(duration)
            .withEndAction {
                outgoing.alpha = 0f
                outgoing.setImageDrawable(null)
            }
            .start()

        updatePortraitOverlay(backdrop, showPortrait, animate = true)
    }

    private fun updatePortraitOverlay(backdrop: LoadedBackdrop, showPortrait: Boolean, animate: Boolean) {
        if (showPortrait) {
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
                            if (!activeShowsPortraitOverlay) {
                                portraitForeground.visibility = View.GONE
                                portraitForeground.setImageDrawable(null)
                            }
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
     * Called on Activity resume / HDMI-CEC wake to snap both layers cleanly to their
     * settled state derived from the currently selected backdrop.
     */
    fun ensureVisibleAfterWake() {
        val active = if (showingA) layerA else layerB
        val inactive = if (showingA) layerB else layerA

        active.animate().cancel()
        inactive.animate().cancel()
        portraitForeground.animate().cancel()

        active.alpha = 1f
        inactive.alpha = 0f
        inactive.setImageDrawable(null)

        if (activeShowsPortraitOverlay) {
            portraitForeground.visibility = View.VISIBLE
            portraitForeground.alpha = 1f
        } else {
            portraitForeground.visibility = View.GONE
            portraitForeground.alpha = 0f
            portraitForeground.setImageDrawable(null)
        }
    }

    fun releaseDrawables() {
        layerA.animate().cancel()
        layerB.animate().cancel()
        portraitForeground.animate().cancel()
        layerA.setImageDrawable(null)
        layerB.setImageDrawable(null)
        portraitForeground.setImageDrawable(null)
        lastRenderKey = null
    }

    companion object {
        private const val CROSSFADE_MS = 650L
    }
}
