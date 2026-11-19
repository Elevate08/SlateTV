package com.stasisstate.slatetv.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.shelf.HomeShelf
import com.stasisstate.slatetv.shelf.launchPluginActivity
import com.stasisstate.slatetv.shelf.launchShelfCard
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/** Outcome of trying to put focus on the first shelf card. */
internal enum class ShelfCardFocus {
    /** Card 0 took focus immediately. */
    FOCUSED,

    /** Card 0 isn't laid out yet but the shelf has cards: consume the key and retry after layout. */
    PENDING,

    /** Nothing to focus (hidden shelf or no cards): let the key fall through. */
    UNAVAILABLE,
}

internal fun resolveCardFocus(focusedNow: Boolean, visible: Boolean, hasCards: Boolean): ShelfCardFocus = when {
    focusedNow -> ShelfCardFocus.FOCUSED
    visible && hasCards -> ShelfCardFocus.PENDING
    else -> ShelfCardFocus.UNAVAILABLE
}

/** True when the D-pad event is consumed by the shelf (focus moved, or a card focus is pending). */
internal fun shelfEntryConsumed(visible: Boolean, focusCard: () -> ShelfCardFocus, focusHeader: () -> Boolean): Boolean =
    visible && (focusCard() != ShelfCardFocus.UNAVAILABLE || focusHeader())

/**
 * Pure vertical-traversal decision. Returns true when the shelf consumed the key; false when
 * traversal exits the shelf (Up -> Gallery Mode, Down -> Dock).
 */
internal fun shelfVerticalConsumed(
    keyCode: Int,
    inHeader: Boolean,
    focusCard: () -> ShelfCardFocus,
    focusHeader: () -> Boolean,
): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> if (inHeader) false else focusHeader()
    KeyEvent.KEYCODE_DPAD_DOWN -> inHeader && focusCard() != ShelfCardFocus.UNAVAILABLE
    else -> false
}

internal class HomeShelfController(
    private val container: ViewGroup,
    scope: CoroutineScope,
    client: OkHttpClient,
) {
    private val context = container.context
    private val title = container.findViewById<TextView>(R.id.homeShelfTitle)
    private val next = container.findViewById<View>(R.id.nextShelf)
    private val guide = container.findViewById<View>(R.id.fullShelfGuide)
    private val cards = container.findViewById<RecyclerView>(R.id.homeShelfCards)
    private var shelves = emptyList<HomeShelf>()
    private var selected: HomeShelf? = null
    private var pendingCardFocus = false
    private val adapter = HomeShelfAdapter(context, scope, client) { card ->
        selected?.takeIf { card in it.data.items }?.let { launchShelfCard(context, it.plugin.packageName, card) }
    }

    init {
        cards.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        cards.adapter = adapter
        cards.itemAnimator = null
        cards.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (pendingCardFocus) cards.post { retryPendingCardFocus() }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
        next.setOnClickListener {
            if (shelves.size < 2) return@setOnClickListener
            val index = shelves.indexOf(selected)
            show(shelves[(index + 1) % shelves.size])
        }
    }

    fun render(updated: List<HomeShelf>) {
        shelves = updated
        next.visibility = if (shelves.size > 1) View.VISIBLE else View.GONE
        show(updated.firstOrNull { it.plugin.id == selected?.plugin?.id } ?: updated.firstOrNull())
    }

    private fun show(shelf: HomeShelf?) {
        if (selected?.plugin?.id != shelf?.plugin?.id) cards.scrollToPosition(0)
        selected = shelf
        container.visibility = if (shelf == null) View.GONE else View.VISIBLE
        if (shelf == null || shelf.data.items.isEmpty()) pendingCardFocus = false
        adapter.submitList(shelf?.data?.items.orEmpty()) {
            if (pendingCardFocus && container.visibility == View.VISIBLE) cards.post { retryPendingCardFocus() }
        }
        title.text = shelf?.data?.shelfTitle
        val activity = shelf?.data?.guideActivityClassName
        guide.visibility = if (activity == null) View.GONE else View.VISIBLE
        guide.setOnClickListener {
            if (shelf != null && activity != null) launchPluginActivity(context, shelf.plugin.packageName, activity)
        }
    }

    fun hasFocus() = container.hasFocus()

    fun moveHorizontal(keyCode: Int): Boolean {
        val focus = container.findFocus()
        val firstCard = cards.layoutManager?.findViewByPosition(0)
        return when {
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focus === firstCard ->
                focusHeaderAction()
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focus === guide && next.visibility == View.VISIBLE ->
                next.requestFocus()
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus === next && guide.visibility == View.VISIBLE ->
                guide.requestFocus()
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && (focus === next || focus === guide) ->
                focusFirstCard() != ShelfCardFocus.UNAVAILABLE
            else -> false
        }
    }

    /**
     * Handles vertical D-pad traversal between the shelf card strip and shelf header actions
     * (Full Guide / Next Shelf). Returns false when traversal exits the shelf (Up -> Gallery Mode,
     * Down -> Dock).
     */
    fun moveVertical(keyCode: Int): Boolean {
        val focus = container.findFocus() ?: return false
        val inHeader = focus === guide || focus === next
        return shelfVerticalConsumed(keyCode, inHeader, ::focusFirstCard, ::focusHeaderAction)
    }

    fun requestFocus(): Boolean =
        shelfEntryConsumed(container.visibility == View.VISIBLE, ::focusFirstCard, ::focusHeaderAction)

    /** Drops any deferred first-card focus (focus moved elsewhere: overlay, Gallery Mode, dock). */
    fun clearPendingFocus() {
        pendingCardFocus = false
    }

    private fun hasCards() = selected?.data?.items?.isNotEmpty() == true

    private fun focusFirstCardNow(): Boolean {
        val firstVisible = cards.layoutManager?.findViewByPosition(0) ?: cards.getChildAt(0)
        return firstVisible?.requestFocus() == true
    }

    private fun retryPendingCardFocus() {
        if (pendingCardFocus && container.visibility == View.VISIBLE && focusFirstCardNow()) pendingCardFocus = false
    }

    private fun focusFirstCard(): ShelfCardFocus {
        val result = resolveCardFocus(focusFirstCardNow(), container.visibility == View.VISIBLE, hasCards())
        when (result) {
            ShelfCardFocus.FOCUSED -> pendingCardFocus = false
            ShelfCardFocus.PENDING -> {
                pendingCardFocus = true
                cards.post { retryPendingCardFocus() }
            }
            ShelfCardFocus.UNAVAILABLE -> Unit
        }
        return result
    }

    private fun focusHeaderAction(): Boolean =
        (guide.visibility == View.VISIBLE && guide.requestFocus()) ||
            (next.visibility == View.VISIBLE && next.requestFocus())
}
