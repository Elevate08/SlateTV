package com.stasisstate.slatetv.connector.homeassistant

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema rendering model, discovery TXT parsing, URL safety, and ADB provisioning validation. */
class HaSetupHelpersTest {
    // ── data_schema → form model ────────────────────────────────────────────────────

    @Test
    fun parsesSelectOptionsInVoluptuousSerializeShape() {
        val fields = HaLoginParser.fields(JSONArray(
            """[{"type":"select","options":[["totp","Authenticator app"],["notify","Notify"]],"name":"multi_factor_auth_module","required":false}]""",
        ))
        val field = fields.single()
        assertEquals(HaFormField.Type.SELECT, field.type)
        assertEquals(listOf("totp" to "Authenticator app", "notify" to "Notify"), field.options)
        assertEquals("Two-factor method", field.label)
    }

    @Test
    fun toleratesOtherOptionShapesAndUnknownTypes() {
        val fields = HaLoginParser.fields(JSONArray(
            """[{"type":"select","options":{"a":"Alice"},"name":"user"},""" +
                """{"type":"select","options":["x","y"],"name":"pick"},""" +
                """{"type":"constant","name":"weird","required":true},""" +
                """{"name":""},"not an object"]""",
        ))
        assertEquals(listOf("user", "pick", "weird"), fields.map { it.name })
        assertEquals(listOf("a" to "Alice"), fields[0].options)
        assertEquals(listOf("x" to "x", "y" to "y"), fields[1].options)
        assertEquals(HaFormField.Type.STRING, fields[2].type)
    }

    @Test
    fun valuesValidateBeforeAnythingIsSent() {
        val fields = listOf(
            HaFormField("username", HaFormField.Type.STRING, required = true),
            HaFormField("password", HaFormField.Type.STRING, required = true),
        )
        assertEquals("Enter password", HaLoginParser.values(fields, mapOf("username" to "me")).exceptionOrNull()?.message)
        // Usernames are trimmed; passwords are sent exactly as typed.
        assertEquals(
            mapOf("username" to "me", "password" to " p w "),
            HaLoginParser.values(fields, mapOf("username" to " me ", "password" to " p w ")).getOrThrow(),
        )
        val code = listOf(HaFormField("code", HaFormField.Type.STRING, required = true))
        assertEquals(mapOf("code" to "123456"), HaLoginParser.values(code, mapOf("code" to "123 456")).getOrThrow())
        val select = listOf(HaFormField("m", HaFormField.Type.SELECT, true, listOf("totp" to "App")))
        assertTrue(HaLoginParser.values(select, mapOf("m" to "sms")).isFailure)
        val number = listOf(HaFormField("n", HaFormField.Type.INTEGER, true))
        assertEquals(mapOf("n" to 42L), HaLoginParser.values(number, mapOf("n" to "42")).getOrThrow())
    }

    // ── mDNS TXT ────────────────────────────────────────────────────────────────────

    private fun txt(vararg pairs: Pair<String, String?>) = pairs.associate { it.first to it.second?.toByteArray() }

    @Test
    fun discoveryPrefersInternalUrlThenResolvedThenExternal() {
        val server = HaServerDiscovery.parse(
            "Home._home-assistant._tcp.local.",
            "192.168.1.20",
            8123,
            txt(
                "location_name" to "Home",
                "uuid" to "abc123",
                "version" to "2026.11.2",
                "internal_url" to "http://homeassistant.local:8123",
                "external_url" to "https://ha.example.com",
                "base_url" to "https://ha.example.com",
            ),
        )!!
        assertEquals("Home", server.name)
        assertEquals("abc123", server.key)
        assertEquals("2026.11.2", server.version)
        assertEquals(
            listOf("http://homeassistant.local:8123", "http://192.168.1.20:8123", "https://ha.example.com"),
            server.urls,
        )
    }

    @Test
    fun discoveryFallsBackToResolvedAddressAndIgnoresJunk() {
        val server = HaServerDiscovery.parse("Cabin", "10.0.0.5", 8123, txt("internal_url" to "", "external_url" to "javascript:alert(1)", "base_url" to null))!!
        assertEquals("Cabin", server.name)
        assertEquals(listOf("http://10.0.0.5:8123"), server.urls)
        assertNull(HaServerDiscovery.parse("x", null, 0, emptyMap()))
        assertNull(HaServerDiscovery.parse("x", "fe80::1", 8123, emptyMap()))
    }

    // ── URL safety ──────────────────────────────────────────────────────────────────

    @Test
    fun plainHttpWarningOnlyForNonLanHosts() {
        assertTrue(HaUrlSafety.isInsecureRemote("http://ha.example.com:8123"))
        assertTrue(HaUrlSafety.isInsecureRemote("http://8.8.8.8:8123"))
        assertFalse(HaUrlSafety.isInsecureRemote("https://ha.example.com"))
        assertFalse(HaUrlSafety.isInsecureRemote("http://192.168.1.20:8123"))
        assertFalse(HaUrlSafety.isInsecureRemote("http://172.20.0.2:8123"))
        assertFalse(HaUrlSafety.isInsecureRemote("http://10.1.2.3"))
        assertFalse(HaUrlSafety.isInsecureRemote("http://homeassistant.local:8123"))
        assertFalse(HaUrlSafety.isInsecureRemote("http://homeassistant:8123"))
        assertFalse(HaUrlSafety.isInsecureRemote("http://100.101.102.103:8123"))
        assertFalse(HaUrlSafety.isInsecureRemote("http://[fd00::5]:8123"))
        assertTrue(HaUrlSafety.isInsecureRemote("http://172.32.0.1"))
    }

    // ── ADB provisioning ────────────────────────────────────────────────────────────

    @Test
    fun provisioningValidatesUrlAndToken() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ4In0.sig-_part"
        val ok = HaProvisioning.validate(" https://ha.example.com/ ", "  $jwt\n") as HaProvisioning.Check.Valid
        assertEquals("https://ha.example.com", ok.serverUrl)
        assertEquals(jwt, ok.token)
        assertFalse(ok.toString().contains(jwt))

        fun code(url: String?, token: String?) =
            (HaProvisioning.validate(url, token) as HaProvisioning.Check.Rejected).outcome
        assertEquals(HaProvisioning.RESULT_BAD_REQUEST, code(null, jwt).code)
        assertTrue(code("ha.example.com", jwt).data.contains("http://"))
        assertTrue(code("ftp://ha.example.com", jwt).data.contains("not a valid"))
        assertTrue(code("https://ha.example.com", null).data.contains("missing --es token"))
        assertTrue(code("https://ha.example.com", "two words").data.contains("unexpected characters"))
        assertTrue(code("https://ha.example.com", "x".repeat(5000)).data.contains("unexpected characters"))
    }

    @Test
    fun provisioningFailuresNeverEchoTheToken() {
        assertEquals(HaProvisioning.RESULT_TOKEN_REJECTED, HaProvisioning.failure("https://h", 401, "Access token was rejected").code)
        assertEquals(HaProvisioning.RESULT_UNREACHABLE, HaProvisioning.failure("https://h", 0, "timeout").code)
        assertEquals(HaProvisioning.RESULT_ERROR, HaProvisioning.failure("https://h", 500, "Home Assistant returned 500").code)
        val command = HaProvisioning.adbCommand("com.stasisstate.slatetv", "https://ha.example.com")
        assertTrue(command.contains("-a ${HaProvisioning.ACTION}"))
        assertTrue(command.contains("-n com.stasisstate.slatetv/com.stasisstate.slatetv.connector.homeassistant.HaAdbConfigReceiver"))
        assertTrue(command.contains("--es token \"\$(cat token.txt)\""))
    }
}
