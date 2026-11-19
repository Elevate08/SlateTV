package com.stasisstate.slatetv

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stasisstate.slatetv.connector.immich.BuiltInImmichPlugin
import com.stasisstate.slatetv.ui.HomeActivity
import com.stasisstate.slatetv.wallpaper.plugins.AmbientSlatePlugin
import com.stasisstate.slatetv.wallpaper.plugins.LocalFolderPlugin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeActivityInstrumentedTest {

    @Test
    fun homeActivityRendersBackdropAndControlsImmediately() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            Thread.sleep(600)
            scenario.onActivity { activity ->
                val backdropA = activity.findViewById<View>(R.id.backdropLayerA)
                val backdropB = activity.findViewById<View>(R.id.backdropLayerB)
                val btnSettings = activity.findViewById<View>(R.id.btnSettings)
                val btnAllApps = activity.findViewById<View>(R.id.btnAllApps)

                val root = activity.findViewById<android.view.ViewGroup>(R.id.rootContainer)
                val backdropContainer = activity.findViewById<View>(R.id.backdropContainer)
                val hudContainer = activity.findViewById<View>(R.id.hudContainer)
                assertTrue(
                    "Backdrop container must always stay beneath HUD in z-order",
                    root.indexOfChild(backdropContainer) < root.indexOfChild(hudContainer),
                )

                assertNotNull(backdropA)
                assertNotNull(backdropB)
                assertTrue(
                    "At least one backdrop layer must be opaque (never black screen)",
                    backdropA.alpha > 0.5f || backdropB.alpha > 0.5f,
                )
                assertEquals(View.VISIBLE, btnSettings.visibility)
                assertEquals(View.VISIBLE, btnAllApps.visibility)
                assertNotNull("TV remote focus must be assigned", activity.currentFocus)
            }
        }
    }

    @Test
    fun shelfLayoutStartsHidden() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val shelf = activity.layoutInflater.inflate(R.layout.home_shelf, null)
                assertEquals(View.GONE, shelf.visibility)
            }
        }
    }

    @Test
    fun settingsDrawerAndAllAppsOpenAndCloseCleanly() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            Thread.sleep(400)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnSettings).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsOverlay).visibility)
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsOverlay).visibility)

                activity.findViewById<View>(R.id.btnAllApps).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.allAppsOverlay).visibility)
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.allAppsOverlay).visibility)
            }
        }
    }

    @Test
    fun pluginRegistryRegistersBuiltInProviders() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SlateApplication
        app.pluginRegistry.refreshExtensions()
        val ids = app.pluginRegistry.getAllPlugins().map { it.metadata.id }.toSet()

        assertTrue(AmbientSlatePlugin.PLUGIN_ID in ids)
        assertTrue(LocalFolderPlugin.PLUGIN_ID in ids)
        assertTrue(BuiltInImmichPlugin.PLUGIN_ID in ids)
    }
}
