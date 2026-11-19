package com.stasisstate.slatetv.connector.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test fun httpsAlwaysAllowed() {
        assertNotNull(ExtensionUrlPolicy.validateManifestUrl("https://example.com/manifest.json"))
        assertNotNull(ExtensionUrlPolicy.validateManifestUrl("https://8.8.8.8/m.json"))
    }

    @Test fun cleartextPublicHostsRejected() {
        assertNull(ExtensionUrlPolicy.validateManifestUrl("http://example.com/manifest.json"))
        assertNull(ExtensionUrlPolicy.validateManifestUrl("http://8.8.8.8/manifest.json"))
        assertNull(ExtensionUrlPolicy.validateManifestUrl("http://172.32.0.1/manifest.json")) // just outside 172.16/12
        assertNull(ExtensionUrlPolicy.validateManifestUrl("http://[2001:db8::1]/manifest.json"))
        assertNull(ExtensionUrlPolicy.validateManifestUrl("http://notlocal.example.com/"))
    }

    @Test fun cleartextPrivateHostsAllowed() {
        listOf(
            "http://10.0.0.5/m.json", "http://172.16.0.1/m.json", "http://172.31.255.255/m.json",
            "http://192.168.1.20:8080/m.json", "http://127.0.0.1:9000/m.json", "http://localhost/m.json",
            "http://nas.local/m.json", "http://[::1]/m.json", "http://[fe80::1]/m.json",
            "http://[fd12:3456::1]/m.json", "http://[fc00::1]/m.json", "http://169.254.1.1/m.json",
        ).forEach { assertNotNull(it, ExtensionUrlPolicy.validateManifestUrl(it)) }
    }

    @Test fun nonHttpSchemesAndCredentialsRejected() {
        listOf(
            "file:///etc/passwd", "content://x/y", "javascript:alert(1)", "ftp://example.com/m.json",
            "https://user:pw@example.com/m.json", "https://example.com/m.json#frag", "", "   ",
        ).forEach { assertNull(it, ExtensionUrlPolicy.validateManifestUrl(it)) }
    }

    @Test fun overlongUrlRejected() {
        assertNull(ExtensionUrlPolicy.validateManifestUrl("https://example.com/" + "a".repeat(2100)))
    }

    @Test fun endpointResolutionIsSameOriginOnly() {
        val base = ExtensionUrlPolicy.parseHttpUrl("https://example.com/ext/manifest.json")!!
        assertEquals("https://example.com/ext/bg.json", ExtensionUrlPolicy.resolveEndpoint(base, "bg.json").toString())
        assertEquals("https://example.com/bg.json", ExtensionUrlPolicy.resolveEndpoint(base, "/bg.json").toString())
        assertEquals("https://example.com/x/bg.json", ExtensionUrlPolicy.resolveEndpoint(base, "https://example.com/x/bg.json").toString())
        assertNull(ExtensionUrlPolicy.resolveEndpoint(base, "https://evil.example.net/bg.json"))
        assertNull(ExtensionUrlPolicy.resolveEndpoint(base, "//evil.example.net/bg.json"))
        assertNull(ExtensionUrlPolicy.resolveEndpoint(base, "http://example.com/bg.json"))
        assertNull(ExtensionUrlPolicy.resolveEndpoint(base, "https://example.com:8443/bg.json"))
        assertNull(ExtensionUrlPolicy.resolveEndpoint(base, "https://u:p@example.com/bg.json"))
    }

    @Test fun imageUrlSchemes() {
        assertNotNull(ExtensionUrlPolicy.imageUrl("https://cdn.example.com/a.jpg"))
        assertNotNull(ExtensionUrlPolicy.imageUrl("http://cdn.example.com/a.jpg"))
        listOf("file:///sdcard/a.jpg", "content://media/1", "javascript:x", "data:image/png;base64,AA==", "ambient://x", "intent://x")
            .forEach { assertNull(it, ExtensionUrlPolicy.imageUrl(it)) }
    }

    @Test fun launchUriAllowlist() {
        assertNotNull(ExtensionUrlPolicy.launchUri("https://example.com/watch"))
        assertNotNull(ExtensionUrlPolicy.launchUri("http://example.com/watch"))
        assertEquals("market://details?id=com.example.app", ExtensionUrlPolicy.launchUri("market://details?id=com.example.app"))
        listOf(
            "intent://scan/#Intent;scheme=zxing;end", "INTENT://x", "file:///data/x", "content://x/1",
            "android-app://com.example", "javascript:alert(1)", "market://", "market://details?id=a#Intent;component=x/y;end",
            "https://exa mple.com", "tel:123",
        ).forEach { assertNull(it, ExtensionUrlPolicy.launchUri(it)) }
    }

    @Test fun textSanitisation() {
        assertEquals("abc", ExtensionUrlPolicy.text("a\u0000b‮c", 10))
        assertEquals("abcde", ExtensionUrlPolicy.text("abcdefgh", 5))
        assertNull(ExtensionUrlPolicy.text("   ", 5))
        assertNull(ExtensionUrlPolicy.text(null, 5))
    }

    @Test fun authHeaderRules() {
        assertTrue(AuthHeader.validName("X-API-Key"))
        assertTrue(AuthHeader.validName("Authorization"))
        listOf("Host", "Cookie", "Content-Length", "X Bad", "X-Bad\r\nX: y", "").forEach { assertFalse(it, AuthHeader.validName(it)) }
        assertEquals("Authorization" to "Bearer tok", AuthHeader.header(null, "tok"))
        assertEquals("authorization" to "Bearer tok", AuthHeader.header("authorization", "tok"))
        assertNull(AuthHeader.header("Authorization", "Basic abc")) // keys cannot contain spaces
        assertEquals("X-API-Key" to "k", AuthHeader.header("X-API-Key", "k"))
        assertNull(AuthHeader.header("X-API-Key", null))
        assertNull(AuthHeader.header("X-API-Key", "bad\nkey"))
    }
}
