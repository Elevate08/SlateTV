package com.stasisstate.slatetv.connector.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder

class JellyfinArtworkUriTest {
    private fun provider(src: String) =
        "content://$JELLYFIN_IMAGE_PROVIDER_AUTHORITY?src=${URLEncoder.encode(src, "UTF-8")}&v=0.20.0"

    private fun srcOf(uri: String) =
        URLDecoder.decode(uri.substringAfter("src=").substringBefore('&'), "UTF-8")

    @Test fun addsCardSizeToJellyfinServerImages() {
        val out = downsizedJellyfinArtworkUri(provider("https://jf.example.com/Items/abc/Images/Primary?tag=t1"))
        assertTrue(out.startsWith("content://$JELLYFIN_IMAGE_PROVIDER_AUTHORITY?src="))
        assertTrue(out.endsWith("&v=0.20.0"))
        assertEquals("https://jf.example.com/Items/abc/Images/Primary?tag=t1&maxWidth=400&quality=80", srcOf(out))
    }

    @Test fun leavesOtherUrisUnchanged() {
        listOf(
            provider("android.resource://org.jellyfin.androidtv/drawable/tile_land_tv"),
            provider("https://jf.example.com/Items/abc/Images/Primary?maxWidth=200"),
            provider("https://jf.example.com/web/logo.png"),
            "content://com.other.app.images?src=" + URLEncoder.encode("https://x/Items/a/Images/Primary", "UTF-8"),
            "content://$JELLYFIN_IMAGE_PROVIDER_AUTHORITY?v=1",
            "https://jf.example.com/Items/abc/Images/Primary",
        ).forEach { assertEquals(it, downsizedJellyfinArtworkUri(it)) }
    }
}
