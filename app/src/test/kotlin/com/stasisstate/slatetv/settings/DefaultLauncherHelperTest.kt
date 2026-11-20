package com.stasisstate.slatetv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLauncherHelperTest {
    private val self = "com.stasisstate.slatetv"

    @Test
    fun fallbackActionsAreOrderedHomeSettingsThenDefaultApps() {
        assertEquals(
            listOf("android.settings.HOME_SETTINGS", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            DefaultLauncherHelper.homeSettingsFallbackActions(),
        )
    }

    @Test
    fun normalizeHomePackageIgnoresResolverStubAndBlanks() {
        assertNull(DefaultLauncherHelper.normalizeHomePackage(null))
        assertNull(DefaultLauncherHelper.normalizeHomePackage(""))
        assertNull(DefaultLauncherHelper.normalizeHomePackage("  "))
        assertNull(DefaultLauncherHelper.normalizeHomePackage("android"))
        assertEquals("com.example.launcher", DefaultLauncherHelper.normalizeHomePackage("com.example.launcher"))
    }

    @Test
    fun slateIsActiveWhenHomeResolvesToItRegardlessOfRole() {
        assertTrue(DefaultLauncherHelper.computeHomeStatus(self, self, roleHeld = true).isActive)
        assertTrue(DefaultLauncherHelper.computeHomeStatus(self, self, roleHeld = false).isActive)
    }

    @Test
    fun anotherLauncherMayStillHandleHomeAfterSlateGetsTheRole() {
        val status = DefaultLauncherHelper.computeHomeStatus("com.example.launcher", self, roleHeld = true)

        assertFalse(status.isActive)
        assertTrue(status.isOverridden)
        assertTrue(DefaultLauncherHelper.statusText(status).contains("Home still opens com.example.launcher"))
    }

    @Test
    fun heldRoleDoesNotMakeAnUnresolvedHomeActive() {
        for (unresolved in listOf(null, "android")) {
            val status = DefaultLauncherHelper.computeHomeStatus(unresolved, self, roleHeld = true)
            assertFalse(status.isActive)
            assertFalse(status.isOverridden)
            assertNull(status.otherLauncherPackage)
        }
    }

    @Test
    fun adbTipChecksHomeBeforeDisablingTheOtherLauncher() {
        val tip = DefaultLauncherHelper.adbTipText(self, "com.example.launcher")
        val resolve = tip.indexOf("cmd package resolve-activity")
        val disable = tip.indexOf("pm disable-user")
        assertTrue(resolve >= 0 && disable > resolve)
        assertTrue(tip.contains("pm enable --user current com.example.launcher"))
    }

    @Test
    fun adbCommandsAreFormattedAndBrandNeutral() {
        assertEquals(
            "adb shell cmd package set-home-activity --user current com.stasisstate.slatetv/.ui.HomeActivity",
            DefaultLauncherHelper.adbSetHomeCommand(self),
        )
        assertEquals(
            "adb shell pm disable-user --user current com.example.launcher",
            DefaultLauncherHelper.adbDisableLauncherCommand("com.example.launcher"),
        )
        val tip = DefaultLauncherHelper.adbTipText(self, null)
        assertFalse(tip.contains("pm disable-user"))
        val banned = listOf("nvidia", "shield", "projectivy", "google tv", "immich", "jellyfin")
        banned.forEach { assertFalse("copy mentions $it", tip.lowercase().contains(it)) }
    }

    @Test
    fun statusTextReflectsState() {
        val active = DefaultLauncherHelper.computeHomeStatus(self, self, roleHeld = true)
        val other = DefaultLauncherHelper.computeHomeStatus("com.example.launcher", self, roleHeld = false)
        val unset = DefaultLauncherHelper.computeHomeStatus(null, self, roleHeld = false)

        assertTrue(DefaultLauncherHelper.statusText(active).contains("Slate TV is your Default Home"))
        assertFalse(other.isOverridden)
        assertTrue(DefaultLauncherHelper.statusText(other).contains("com.example.launcher"))
        assertTrue(DefaultLauncherHelper.statusText(unset).contains("No default Home"))
    }
}
