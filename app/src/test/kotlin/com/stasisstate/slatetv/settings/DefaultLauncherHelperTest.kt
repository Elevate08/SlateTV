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
    fun resolutionIsAuthoritativeForDefaultHome() {
        assertTrue(DefaultLauncherHelper.computeIsDefaultHome(self, self, roleHeld = true))
        assertTrue(DefaultLauncherHelper.computeIsDefaultHome(self, self, roleHeld = false))
        assertFalse(DefaultLauncherHelper.computeIsDefaultHome("com.example.launcher", self, roleHeld = true))
    }

    @Test
    fun roleCountsOnlyWhenResolutionIsUnset() {
        assertTrue(DefaultLauncherHelper.computeIsDefaultHome("android", self, roleHeld = true))
        assertFalse(DefaultLauncherHelper.computeIsDefaultHome("android", self, roleHeld = false))
        assertFalse(DefaultLauncherHelper.computeIsDefaultHome(null, self, roleHeld = false))
    }

    @Test
    fun adbCommandsAreFormattedAndBrandNeutral() {
        assertEquals(
            "adb shell cmd package set-home-activity com.stasisstate.slatetv/.ui.HomeActivity",
            DefaultLauncherHelper.adbSetHomeCommand(self),
        )
        assertEquals(
            "adb shell pm disable-user --user 0 com.example.launcher",
            DefaultLauncherHelper.adbDisableLauncherCommand("com.example.launcher"),
        )
        val tip = DefaultLauncherHelper.adbTipText(self, null)
        assertTrue(tip.contains("<current_launcher_package>"))
        val banned = listOf("nvidia", "shield", "projectivy", "google tv", "immich", "jellyfin")
        val copy = (tip + DefaultLauncherHelper.statusText(false, null) + DefaultLauncherHelper.statusText(true, null)).lowercase()
        banned.forEach { assertFalse("copy mentions $it", copy.contains(it)) }
    }

    @Test
    fun statusTextReflectsState() {
        assertTrue(DefaultLauncherHelper.statusText(true, null).contains("Slate TV is your Default Home"))
        assertTrue(DefaultLauncherHelper.statusText(false, "com.example.launcher").contains("com.example.launcher"))
        assertTrue(DefaultLauncherHelper.statusText(false, null).contains("No default Home"))
    }
}
