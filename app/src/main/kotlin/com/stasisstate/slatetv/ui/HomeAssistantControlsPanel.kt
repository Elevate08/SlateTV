package com.stasisstate.slatetv.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.connector.homeassistant.HaConfig
import com.stasisstate.slatetv.connector.homeassistant.HaDomains
import com.stasisstate.slatetv.connector.homeassistant.HaEntity
import com.stasisstate.slatetv.connector.homeassistant.HomeAssistantRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Visible tiles for the selected Home Assistant entities, in the user's chosen order. */
internal fun homeAssistantTiles(config: HaConfig, entities: Map<String, HaEntity>): List<HaEntity> =
    if (!config.isConfigured) {
        emptyList()
    } else {
        config.homeEntityIds.map { id -> entities[id] ?: HaEntity(id, HaEntity.STATE_UNKNOWN) }
    }

/**
 * A row of Home Assistant controls above the dock. OK toggles (or runs scenes/scripts); holding
 * OK on a dimmable light opens a brightness picker.
 */
internal class HomeAssistantControlsPanel(
    private val container: ViewGroup,
    private val scope: CoroutineScope,
    private val onFocusLabel: (String) -> Unit,
    private val onDialogOpen: (Boolean) -> Unit,
) {
    private val context = container.context
    private val tiles = container.findViewById<LinearLayout>(R.id.homeAssistantTiles)
    private val inflater = LayoutInflater.from(context)
    private var current: List<HaEntity> = emptyList()
    private val pending = HashSet<String>()

    val isVisible: Boolean get() = container.visibility == View.VISIBLE

    fun hasFocus(): Boolean = container.hasFocus()

    fun render(config: HaConfig, entities: Map<String, HaEntity>) {
        val items = homeAssistantTiles(config, entities)
        current = items
        items.forEachIndexed { index, entity ->
            val tile = tiles.getChildAt(index) ?: inflater.inflate(R.layout.item_home_assistant_tile, tiles, false)
                .also(tiles::addView)
            bindTile(tile, entity)
        }
        if (tiles.childCount > items.size) tiles.removeViews(items.size, tiles.childCount - items.size)
        container.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Focuses the first tile; false when the row is hidden. */
    fun requestFocus(): Boolean = isVisible && tiles.getChildAt(0)?.requestFocus() == true

    private fun bindTile(tile: View, entity: HaEntity) {
        tile.tag = entity.entityId
        val name = entity.friendlyName
        val busy = entity.entityId in pending
        tile.findViewById<TextView>(R.id.haTileName).text = name
        tile.findViewById<TextView>(R.id.haTileState).text = when {
            busy -> context.getString(R.string.ha_tile_working)
            entity.state == HaEntity.STATE_UNKNOWN && entity.attributes.isEmpty() -> "—"
            !entity.isAvailable -> context.getString(R.string.ha_tile_unavailable)
            HaDomains.isMomentary(entity.domain) -> context.getString(R.string.ha_tile_run)
            entity.brightnessPercent != null -> context.getString(R.string.ha_tile_on_brightness, entity.brightnessPercent)
            entity.isOn -> context.getString(R.string.ha_tile_on)
            else -> context.getString(R.string.ha_tile_off)
        }
        val indicatorColor = when {
            !entity.isAvailable -> Color.parseColor("#475569")
            HaDomains.isMomentary(entity.domain) -> Color.parseColor("#7DD3FC")
            entity.isOn -> ContextCompat.getColor(context, R.color.slate_accent_amber)
            else -> Color.parseColor("#7E8CA0")
        }
        tile.findViewById<View>(R.id.haTileIndicator).backgroundTintList = ColorStateList.valueOf(indicatorColor)
        tile.alpha = if (entity.isAvailable || entity.attributes.isEmpty()) 1f else 0.55f
        tile.contentDescription = "$name, ${tile.findViewById<TextView>(R.id.haTileState).text}"

        val hint = when {
            HaDomains.isMomentary(entity.domain) -> context.getString(R.string.ha_tile_hint_run, name)
            entity.supportsBrightness -> context.getString(R.string.ha_tile_hint_brightness, name)
            else -> context.getString(R.string.ha_tile_hint_toggle, name)
        }
        tile.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120L).start()
            if (focused) onFocusLabel(hint)
        }
        if (tile.isFocused) onFocusLabel(hint)
        tile.setOnClickListener { press(entity.entityId) }
        tile.setOnLongClickListener {
            val latest = current.firstOrNull { it.entityId == entity.entityId }
            if (latest != null && latest.supportsBrightness) showBrightness(latest) else press(entity.entityId)
            true
        }
    }

    private fun press(entityId: String) {
        val entity = current.firstOrNull { it.entityId == entityId } ?: return
        if (!pending.add(entityId)) return
        refreshTile(entityId)
        scope.launch {
            val result = HomeAssistantRuntime.pressTile(entity)
            pending -= entityId
            refreshTile(entityId)
            if (result.isFailure) Toast.makeText(context, R.string.ha_tile_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshTile(entityId: String) {
        val index = current.indexOfFirst { it.entityId == entityId }
        if (index >= 0) tiles.getChildAt(index)?.let { bindTile(it, current[index]) }
    }

    private fun showBrightness(entity: HaEntity) {
        val dialog = Dialog(context, R.style.Theme_SlateTV_Dialog)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_surface)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            layoutParams = ViewGroup.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(TextView(context).apply {
            text = context.getString(R.string.ha_brightness_title, entity.friendlyName)
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })
        val currentPct = entity.brightnessPercent ?: 100
        var focusTarget: View? = null
        for (pct in BRIGHTNESS_STEPS) {
            val row = TextView(context).apply {
                text = if (pct == 0) context.getString(R.string.ha_brightness_off) else "$pct%"
                textSize = 15f
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    dialog.dismiss()
                    scope.launch {
                        val result = HomeAssistantRuntime.setBrightness(entity.entityId, pct)
                        if (result.isFailure) Toast.makeText(context, R.string.ha_tile_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (focusTarget == null || kotlin.math.abs(pct - currentPct) < 13) focusTarget = row
            root.addView(row)
        }
        dialog.setContentView(root)
        onDialogOpen(true)
        dialog.setOnDismissListener { onDialogOpen(false) }
        dialog.show()
        focusTarget?.requestFocus()
    }

    private companion object {
        val BRIGHTNESS_STEPS = listOf(100, 75, 50, 25, 0)
    }
}
