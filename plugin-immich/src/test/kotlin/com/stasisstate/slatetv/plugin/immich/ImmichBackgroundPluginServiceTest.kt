package com.stasisstate.slatetv.plugin.immich

import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.ISlateBackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.PluginMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ImmichBackgroundPluginServiceTest {
    private val media = BackgroundMedia(
        id = "immich:preview:asset",
        imageUri = "https://immich.local/api/assets/asset/thumbnail?size=preview",
        headers = mapOf("x-api-key" to "test-key", "Accept" to "image/*"),
        subtitle = "Jan 2026",
        width = 1440,
        height = 810,
    )

    @Test
    fun `binder advertises the external plugin and settings activity`() {
        val binder = ImmichBackgroundPluginService().onBind(null) as ISlateBackgroundPlugin
        assertEquals(PluginContract.API_VERSION, binder.apiVersion)
        assertEquals(IMMICH_PACKAGE, binder.pluginInfo.id)
        assertEquals("$IMMICH_PACKAGE.ImmichSettingsActivity", binder.pluginInfo.settingsActivityClassName)
    }

    @Test
    fun `direct media retains the authentication headers passed into parcel conversion`() {
        val direct = media.forExternalHost { error("Direct media must not be cached") }!!
        assertEquals(media.headers, direct.headers)
        val item = PluginMediaItem.from(direct)
        assertEquals(media.id, item.id)
        assertEquals(media.imageUri, item.imageUri)
        assertEquals(media.subtitle, item.subtitle)
        assertEquals(media.width, item.width)
        assertEquals(media.height, item.height)
        assertFalse(item.allowUntrustedTls)
    }

    @Test
    fun `self-signed media publishes an authenticated download and strips transport credentials`() {
        val localUri = "content://$IMMICH_PACKAGE.media/image"
        val proxied = media.copy(allowUntrustedTls = true).forExternalHost { localUri }!!
        assertEquals(emptyMap<String, String>(), proxied.headers)
        val item = PluginMediaItem.from(proxied)
        assertEquals(localUri, item.imageUri)
        assertEquals(media.id, item.id)
        assertFalse(item.allowUntrustedTls)
    }

    @Test
    fun `failed cache publication does not fall back to direct media`() {
        assertNull(media.copy(allowUntrustedTls = true).forExternalHost { null })
    }

    @Test
    fun `only Slate TV can request media and batch counts are bounded`() {
        val slate = arrayOf("com.stasisstate.slatetv")
        assertEquals(0, allowedMediaCount(5, arrayOf("example.other.app")))
        assertEquals(0, allowedMediaCount(1, emptyArray()))
        assertEquals(0, allowedMediaCount(-1, slate))
        assertEquals(1, allowedMediaCount(1, slate))
        assertEquals(5, allowedMediaCount(Int.MAX_VALUE, slate))
    }
}
