package com.stasisstate.slatetv.ui

import android.view.KeyEvent
import com.stasisstate.slatetv.apps.DockPreferences
import com.stasisstate.slatetv.apps.DockStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockReorderTest {

    private class InMemoryDockStorage : DockStorage {
        private val strings = mutableMapOf<String, String>()
        private val booleans = mutableMapOf<String, Boolean>()
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    }

    private val packages = listOf(
        "com.plexapp.android",
        "com.teamsmart.videomanager.tv",
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.spotify.tv.android",
    )

    @Test
    fun movingLeftAndRightWalksTheSameAppAcrossMultipleSlots() {
        val controller = DockReorderController()
        assertTrue(controller.enter("com.google.android.youtube.tv", packages))

        val leftOnce = controller.move(-1)!!
        assertEquals(
            listOf(
                "com.plexapp.android",
                "com.google.android.youtube.tv",
                "com.teamsmart.videomanager.tv",
                "com.netflix.ninja",
                "com.spotify.tv.android",
            ),
            leftOnce.order,
        )
        assertEquals(1, leftOnce.index)
        assertTrue(leftOnce.moved)

        val leftTwice = controller.move(-1)!!
        assertEquals(packages[0], leftTwice.order[1])
        assertEquals("com.google.android.youtube.tv", leftTwice.order[0])
        assertEquals(0, leftTwice.index)

        val rightOnce = controller.move(+1)!!
        val rightTwice = controller.move(+1)!!
        val rightThrice = controller.move(+1)!!
        assertEquals(1, rightOnce.index)
        assertEquals(2, rightTwice.index)
        assertEquals(3, rightThrice.index)
        assertEquals("com.google.android.youtube.tv", controller.order[3])
        assertEquals("com.netflix.ninja", controller.order[2])
    }

    @Test
    fun movingLeftAtTheFirstSlotStaysInBounds() {
        val controller = DockReorderController()
        controller.enter(packages.first(), packages)

        repeat(5) {
            val stayed = controller.move(-1)!!
            assertFalse(stayed.moved)
            assertEquals(0, stayed.index)
            assertEquals(packages, stayed.order)
        }
        assertEquals(packages, controller.order)
    }

    @Test
    fun movingRightAtTheLastSlotStaysInBounds() {
        val controller = DockReorderController()
        controller.enter(packages.last(), packages)

        repeat(5) {
            val stayed = controller.move(+1)!!
            assertFalse(stayed.moved)
            assertEquals(packages.lastIndex, stayed.index)
            assertEquals(packages, stayed.order)
        }
        assertEquals(packages, controller.order)
    }

    @Test
    fun movePinnedStaysInSyncWithTheReorderedList() {
        val prefs = DockPreferences(InMemoryDockStorage())
        prefs.setPinnedPackages(packages)
        val controller = DockReorderController()
        assertTrue(controller.enter("com.google.android.youtube.tv", prefs.getPinnedPackages()))

        val script = listOf(-1, -1, -1, -1, +1, +1, +1, +1, +1, +1, -1, 0, +3, -2, +10, -10)
        for (delta in script) {
            val ui = controller.move(delta)
            assertTrue("delta $delta should resolve while reorder is active", ui != null)
            val persisted = prefs.movePinned("com.google.android.youtube.tv", delta)
            assertEquals(ui!!.moved, persisted)
            assertEquals(prefs.getPinnedPackages(), ui.order)
            assertEquals(prefs.getPinnedPackages(), controller.order)
            assertEquals(
                prefs.getPinnedPackages().indexOf("com.google.android.youtube.tv"),
                ui.index,
            )
        }
    }

    @Test
    fun missingPackageAndInactiveModeDoNotMove() {
        assertNull(DockReorderController.movePackage(packages, "com.missing.app", -1))
        assertNull(DockReorderController.movePackage(emptyList(), "com.plexapp.android", +1))

        val controller = DockReorderController()
        assertFalse(controller.enter("com.missing.app", packages))
        assertFalse(controller.active)
        assertNull(controller.move(-1))
        assertNull(controller.exit())
    }

    @Test
    fun dpadLeftAndRightRepeatUntilConfirmOrBack() {
        val controller = DockReorderController()
        controller.enter("com.netflix.ninja", packages)

        val first = controller.onKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN) as DockReorderController.KeyResult.Moved
        val second = controller.onKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, repeatCount = 1) as DockReorderController.KeyResult.Moved
        val third = controller.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, repeatCount = 4) as DockReorderController.KeyResult.Moved
        assertEquals(2, first.step.index)
        assertEquals(1, second.step.index)
        assertEquals(2, third.step.index)
        assertEquals("com.netflix.ninja", controller.order[2])

        assertTrue(controller.onKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP) is DockReorderController.KeyResult.Consume)
        assertEquals(2, controller.order.indexOf("com.netflix.ninja"))

        val exited = controller.onKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN) as DockReorderController.KeyResult.Exit
        assertEquals("com.netflix.ninja", exited.exit.packageName)
        assertEquals(2, exited.exit.index)
        assertFalse(controller.active)
        assertEquals(
            "com.netflix.ninja",
            exited.exit.order[exited.exit.index],
        )
    }

    @Test
    fun enterNumpadAndBackExitAndTheFollowingKeyUpIsConsumed() {
        val controller = DockReorderController()
        controller.enter("com.spotify.tv.android", packages)

        val byEnter = controller.onKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN) as DockReorderController.KeyResult.Exit
        assertEquals(packages.lastIndex, byEnter.exit.index)
        assertTrue(
            controller.onKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, repeatCount = 2) is DockReorderController.KeyResult.Consume,
        )
        assertTrue(
            controller.onKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP) is DockReorderController.KeyResult.Consume,
        )
        assertTrue(
            controller.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN) is DockReorderController.KeyResult.Ignore,
        )

        assertTrue(controller.enter("com.plexapp.android", packages))
        val byNumpad = controller.onKey(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN) as DockReorderController.KeyResult.Exit
        assertEquals(0, byNumpad.exit.index)
        assertTrue(
            controller.onKey(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_UP) is DockReorderController.KeyResult.Consume,
        )

        assertTrue(controller.enter("com.teamsmart.videomanager.tv", controller.order))
        val byBack = controller.exit()!!
        assertEquals("com.teamsmart.videomanager.tv", byBack.packageName)
        assertEquals(1, byBack.index)
        assertFalse(controller.active)
        assertNull(controller.move(+1))
    }

    @Test
    fun verticalKeysAreConsumedAndDoNotChangeOrder() {
        val controller = DockReorderController()
        controller.enter("com.google.android.youtube.tv", packages)
        val before = controller.order

        assertTrue(controller.onKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN) is DockReorderController.KeyResult.Consume)
        assertTrue(controller.onKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN) is DockReorderController.KeyResult.Consume)
        assertTrue(controller.onKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP) is DockReorderController.KeyResult.Consume)
        assertEquals(before, controller.order)
        assertTrue(controller.active)
        assertTrue(controller.onKey(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN) is DockReorderController.KeyResult.Ignore)
    }

    @Test
    fun atBoundsLeftAndRightKeysAreConsumedWithoutChangingIndex() {
        val controller = DockReorderController()
        controller.enter(packages.first(), packages)
        assertTrue(controller.onKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN) is DockReorderController.KeyResult.Consume)
        assertEquals(0, controller.order.indexOf(packages.first()))

        controller.exit()
        controller.enter(packages.last(), packages)
        assertTrue(controller.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, repeatCount = 3) is DockReorderController.KeyResult.Consume)
        assertEquals(packages.lastIndex, controller.order.indexOf(packages.last()))
    }

    @Test
    fun promptTellsTheUserHowToFinish() {
        assertEquals(
            "◀  Move YouTube  ▶  ·  OK or Back to finish",
            DockReorderController.prompt("YouTube"),
        )
    }
}
