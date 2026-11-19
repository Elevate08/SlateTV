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
    private val adapter = HomeShelfAdapter(context, scope, client) { card ->
        selected?.takeIf { card in it.data.items }?.let { launchShelfCard(context, it.plugin.packageName, card) }
    }

    init {
        cards.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        cards.adapter = adapter
        cards.itemAnimator = null
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
        adapter.submitList(shelf?.data?.items.orEmpty())
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
                focusFirstCard()
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
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (inHeader) false else focusHeaderAction()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (inHeader) focusFirstCard() else false
            else -> false
        }
    }

    fun requestFocus(): Boolean {
        if (container.visibility != View.VISIBLE) return false
        return focusFirstCard() || focusHeaderAction()
    }

    private fun focusFirstCard(): Boolean {
        val firstVisible = cards.layoutManager?.findViewByPosition(0) ?: cards.getChildAt(0)
        return firstVisible?.requestFocus() == true
    }

    private fun focusHeaderAction(): Boolean =
        (guide.visibility == View.VISIBLE && guide.requestFocus()) ||
            (next.visibility == View.VISIBLE && next.requestFocus())
}
