package com.stasisstate.slatetv.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Clips [this] view's content to a rounded rectangle of [radiusDp].
 *
 * Done in code (outline + clipToOutline) rather than the `android:clipToOutline`
 * XML attribute, which is only honored from API 31.
 */
fun View.clipToRoundedRect(radiusDp: Float) {
    val radiusPx = radiusDp * resources.displayMetrics.density
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
    clipToOutline = true
}

/** Tile background corner radius (bg_dock_tile / bg_dock_card). */
const val TILE_CORNER_RADIUS_DP = 14f

/** Tile padding between the background ring and the content. */
const val TILE_CONTENT_INSET_DP = 4f

/** Inner content radius concentric with the tile's rounded background. */
const val TILE_CONTENT_RADIUS_DP = TILE_CORNER_RADIUS_DP - TILE_CONTENT_INSET_DP

/** Corner radius for square app icons shown inside a tile (about 22% of a ~40dp icon). */
const val TILE_ICON_RADIUS_DP = 9f
