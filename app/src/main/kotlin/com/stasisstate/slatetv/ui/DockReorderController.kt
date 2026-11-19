package com.stasisstate.slatetv.ui

import android.view.KeyEvent

/**
 * Continuous dock reorder. Left and right slide the active package one slot;
 * OK, Enter, or [exit] finishes. Bounds clamps are the same rules as
 * [com.stasisstate.slatetv.apps.DockPreferences.movePinned].
 */
class DockReorderController {

    var packageName: String? = null
        private set

    var order: List<String> = emptyList()
        private set

    val active: Boolean
        get() = packageName != null

    /** True until the confirming key is released, so that key-up cannot activate the tile. */
    private var swallowConfirmUp = false

    fun enter(packageName: String, order: List<String>): Boolean {
        if (packageName !in order) return false
        this.packageName = packageName
        this.order = order.toList()
        swallowConfirmUp = false
        return true
    }

    fun exit(): ExitResult? {
        val pkg = packageName ?: return null
        val index = order.indexOf(pkg)
        packageName = null
        return ExitResult(pkg, index, order.toList())
    }

    /**
     * Replace the working order from persisted pins without leaving reorder mode.
     * Drops out of the mode when [packageName] is no longer pinned.
     */
    fun syncOrder(order: List<String>) {
        this.order = order.toList()
        if (packageName != null && packageName !in order) {
            packageName = null
        }
    }

    /**
     * Move the active package by [delta] slots.
     * At either end the index stays put and [MoveResult.moved] is false.
     * Returns null when reorder mode is inactive or the package is gone.
     */
    fun move(delta: Int): MoveResult? {
        val pkg = packageName ?: return null
        val next = movePackage(order, pkg, delta) ?: return null
        order = next.order
        return next
    }

    fun onKey(keyCode: Int, action: Int, repeatCount: Int = 0): KeyResult {
        if (!active) {
            // The confirming press already left the mode on key-down. Keep eating that
            // press (repeats and key-up) so the focused tile does not launch.
            if (swallowConfirmUp && keyKind(keyCode) == KeyKind.CONFIRM) {
                if (action == KeyEvent.ACTION_UP) swallowConfirmUp = false
                return KeyResult.Consume
            }
            return KeyResult.Ignore
        }
        val kind = keyKind(keyCode) ?: return KeyResult.Ignore
        if (action != KeyEvent.ACTION_DOWN) return KeyResult.Consume
        return when (kind) {
            KeyKind.LEFT -> directionResult(move(-1))
            KeyKind.RIGHT -> directionResult(move(+1))
            KeyKind.VERTICAL -> KeyResult.Consume
            KeyKind.CONFIRM -> {
                if (repeatCount > 0) {
                    KeyResult.Consume
                } else {
                    swallowConfirmUp = true
                    KeyResult.Exit(exit()!!)
                }
            }
        }
    }

    private fun directionResult(step: MoveResult?): KeyResult {
        val resolved = step ?: return KeyResult.Consume
        return if (resolved.moved) KeyResult.Moved(resolved) else KeyResult.Consume
    }

    data class MoveResult(
        val order: List<String>,
        val index: Int,
        val moved: Boolean,
    )

    data class ExitResult(
        val packageName: String,
        val index: Int,
        val order: List<String>,
    )

    sealed class KeyResult {
        data object Ignore : KeyResult()
        data object Consume : KeyResult()
        data class Moved(val step: MoveResult) : KeyResult()
        data class Exit(val exit: ExitResult) : KeyResult()
    }

    enum class KeyKind { LEFT, RIGHT, VERTICAL, CONFIRM }

    companion object {
        fun prompt(label: String): String = "◀  Move $label  ▶  ·  OK or Back to finish"

        /**
         * Missing package yields null. A delta that would leave the index unchanged
         * (either end, or zero) yields [MoveResult.moved] false and the original order.
         */
        fun movePackage(order: List<String>, packageName: String, delta: Int): MoveResult? {
            if (order.isEmpty()) return null
            val index = order.indexOf(packageName)
            if (index < 0) return null
            val target = (index + delta).coerceIn(0, order.lastIndex)
            if (target == index) return MoveResult(order.toList(), index, moved = false)
            val next = order.toMutableList()
            val item = next.removeAt(index)
            next.add(target, item)
            return MoveResult(next, target, moved = true)
        }

        fun keyKind(keyCode: Int): KeyKind? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyKind.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyKind.RIGHT
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> KeyKind.VERTICAL
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> KeyKind.CONFIRM
            else -> null
        }
    }
}
