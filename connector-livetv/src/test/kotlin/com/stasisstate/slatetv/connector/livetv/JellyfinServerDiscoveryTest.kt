package com.stasisstate.slatetv.connector.livetv

import org.junit.Assert.*
import org.junit.Test

class JellyfinServerDiscoveryTest {
    @Test fun parsesOfficialDiscoveryResponse() {
        val server = parseDiscoveryResponse(
            """{"Address":"http://192.168.1.20:8096","Id":"abc123","Name":"Living Room","EndpointAddress":null}""",
        )
        assertEquals(DiscoveredServer("abc123", "Living Room", "http://192.168.1.20:8096/"), server)
    }

    @Test fun usesAddressAsIdAndDefaultNameWhenMissing() {
        val server = parseDiscoveryResponse("""{"Address":"https://jf.local"}""")
        assertEquals("Jellyfin", server?.name)
        assertEquals("https://jf.local/", server?.address)
        assertEquals("https://jf.local/", server?.id)
    }

    @Test fun rejectsMalformedOrUnsafeResponses() {
        assertNull(parseDiscoveryResponse("not json"))
        assertNull(parseDiscoveryResponse("""{"Name":"No address"}"""))
        assertNull(parseDiscoveryResponse("""{"Address":"ftp://192.168.1.2"}"""))
        assertNull(parseDiscoveryResponse("""{"Address":"http://user:pw@192.168.1.2:8096"}"""))
        assertNull(parseDiscoveryResponse("""{"Address":"http://192.168.1.2:8096/?x=1"}"""))
        assertNull(parseDiscoveryResponse("""{"Address":"http://a/","Name":"${"x".repeat(3000)}"}"""))
    }

    @Test fun discoveryProtocolConstantsMatchJellyfin() {
        assertEquals(7359, DISCOVERY_PORT)
        assertEquals("who is JellyfinServer?", DISCOVERY_MESSAGE)
    }

    @Test fun savedModeDefaultsPreserveConfiguredInstalls() {
        assertEquals(LiveTvSourceMode.SYSTEM_LIVE_CHANNELS, defaultSourceMode(null, ""))
        assertEquals(LiveTvSourceMode.JELLYFIN_SERVER, defaultSourceMode(null, "https://jf.example/"))
        assertEquals(LiveTvSourceMode.SYSTEM_LIVE_CHANNELS, defaultSourceMode("SYSTEM_LIVE_CHANNELS", "https://jf.example/"))
        assertEquals(LiveTvSourceMode.JELLYFIN_SERVER, defaultSourceMode("JELLYFIN_SERVER", ""))
        assertEquals(LiveTvSourceMode.SYSTEM_LIVE_CHANNELS, defaultSourceMode("bogus", ""))
        assertEquals(LiveTvSourceMode.SYSTEM_LIVE_CHANNELS, LiveTvConfig().sourceMode)
    }
}
