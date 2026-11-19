package dev.slatetv.launcher

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.slatetv.launcher.ui.HomeActivity
import dev.slatetv.plugin.api.PluginRequestContext
import dev.slatetv.plugin.immich.ImmichBackgroundPlugin
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
    fun pluginRegistryDiscoversBuiltInAndExternalAidlPlugins() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SlateApplication
        val external = app.pluginRegistry.refreshExternalPlugins()
        val allIds = app.pluginRegistry.getAllPlugins().map { it.metadata.id }

        assertTrue("Built-in Immich plugin must be registered", ImmichBackgroundPlugin.PLUGIN_ID in allIds)
        assertTrue("External sample plugin APK should be discovered", external.any { it.metadata.id == "external.sample.nordic" })

        val samplePlugin = app.pluginRegistry.getPluginById("external.sample.nordic")
        val media = samplePlugin.fetchNext(PluginRequestContext())
        assertNotNull("External AIDL plugin must return valid BackgroundMedia over IPC", media)
        assertTrue(media!!.imageUri.startsWith("ambient://"))
    }
}
