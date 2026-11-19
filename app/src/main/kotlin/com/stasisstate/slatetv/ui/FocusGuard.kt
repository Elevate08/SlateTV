package com.stasisstate.slatetv.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Guarantees Android TV D-pad focus never disappears into limbo after HDMI-CEC
 * wake-up, overlay dismissal, or dock updates.
 */
object FocusGuard {
    fun ensureHomeFocus(
        currentFocus: View?,
        dockRecyclerView: RecyclerView,
        fallbackFocusView: View,
        isOverlayOpen: Boolean,
    ) {
        if (isOverlayOpen) return
        if (currentFocus != null && currentFocus.isShown && currentFocus.isFocusable) return

        val firstDockChild = dockRecyclerView.layoutManager?.findViewByPosition(0)
            ?: dockRecyclerView.getChildAt(0)
        if (firstDockChild != null && firstDockChild.isShown && firstDockChild.requestFocus()) {
            return
        }
        fallbackFocusView.requestFocus()
    }
}
