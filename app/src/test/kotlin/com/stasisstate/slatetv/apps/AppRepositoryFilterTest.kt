package com.stasisstate.slatetv.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRepositoryFilterTest {
    private val self = "com.stasisstate.slatetv"

    @Test
    fun selfPackageIsFilteredOutWhileOtherAppsRemainInOrder() {
        val candidates = listOf(
            "com.example.tvapp",
            self,
            "com.sideloaded.phoneapp",
        )
        assertEquals(
            listOf("com.example.tvapp", "com.sideloaded.phoneapp"),
            filterVisiblePackages(candidates, self),
        )
    }

    @Test
    fun isVisiblePackageChecks() {
        assertTrue(isVisiblePackage("com.example.tvapp", self))
        assertFalse(isVisiblePackage(self, self))
    }
}
