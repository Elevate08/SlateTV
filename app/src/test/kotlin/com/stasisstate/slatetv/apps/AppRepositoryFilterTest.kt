package com.stasisstate.slatetv.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRepositoryFilterTest {
    private val self = "com.stasisstate.slatetv"
    private val plugins = setOf("com.stasisstate.slatetv.plugin.bg", "com.stasisstate.slatetv.plugin.shelf")

    @Test
    fun pluginAndSelfPackagesAreFilteredOutWhileOtherAppsRemainInOrder() {
        val candidates = listOf(
            "com.example.tvapp",
            "com.stasisstate.slatetv.plugin.bg",
            self,
            "com.sideloaded.phoneapp",
            "com.stasisstate.slatetv.plugin.shelf",
        )
        assertEquals(
            listOf("com.example.tvapp", "com.sideloaded.phoneapp"),
            filterVisiblePackages(candidates, self, plugins),
        )
    }

    @Test
    fun emptyPluginSetOnlyRemovesSelf() {
        assertEquals(listOf("a", "b"), filterVisiblePackages(listOf("a", self, "b"), self, emptySet()))
    }

    @Test
    fun isVisiblePackageChecks() {
        assertTrue(isVisiblePackage("com.example.tvapp", self, plugins))
        assertFalse(isVisiblePackage(self, self, plugins))
        assertFalse(isVisiblePackage("com.stasisstate.slatetv.plugin.bg", self, plugins))
    }
}
