package com.stasisstate.slatetv.connector.homeassistant

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** The whole sign-in sequence against a fake Home Assistant — never a real server (HA bans IPs). */
class HaAuthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: HaAuthClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        auth = HaAuthClient(server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun RecordedRequest.form(): Map<String, String> = body.readUtf8().split('&').filter { it.isNotEmpty() }
        .associate { pair -> pair.substringBefore('=').let { URLDecoder.decode(it, "UTF-8") } to URLDecoder.decode(pair.substringAfter('='), "UTF-8") }

    @Test
    fun parsesRealWorldProvidersWithNullId() = runBlocking {
        server.enqueue(json(
            """{"providers":[{"name":"OpenID Connect (SSO)","id":"default","type":"auth_oidc"},""" +
                """{"name":"Home Assistant Local","id":null,"type":"homeassistant"}],"preselect_remember_me":true}""",
        ))
        val providers = auth.providers()
        assertEquals(2, providers.size)
        assertEquals(HaAuthProvider("auth_oidc", "default", "OpenID Connect (SSO)"), providers[0])
        val local = providers.single { it.supportsPasswordSignIn }
        assertNull(local.id)
        assertEquals("/auth/providers", server.takeRequest().path)
    }

    @Test
    fun startFlowSendsClientIdHandlerWithNullIdAndSameHostRedirect() = runBlocking {
        server.enqueue(json(
            """{"type":"form","flow_id":"f1","handler":["homeassistant",null],"step_id":"init",""" +
                """"data_schema":[{"type":"string","name":"username","required":true},""" +
                """{"type":"string","name":"password","required":true}],"errors":{},"description_placeholders":null}""",
        ))
        val step = auth.startFlow(HaAuthProvider("homeassistant", null, "Home Assistant Local")) as HaFlowStep.Form
        assertEquals("f1", step.flowId)
        assertEquals(listOf("username", "password"), step.fields.map { it.name })
        assertTrue(step.fields[1].isSecret)
        assertFalse(step.fields[0].isSecret)
        assertNull(step.error)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/login_flow", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(HaAuthClient.CLIENT_ID, body.getString("client_id"))
        assertEquals(HaAuthClient.REDIRECT_URI, body.getString("redirect_uri"))
        assertEquals("homeassistant", body.getJSONArray("handler").getString(0))
        assertTrue(body.getJSONArray("handler").isNull(1))
        assertEquals("authorize", body.getString("type"))
        // Only keys every HA version's schema accepts — a 400 would count toward an IP ban.
        assertEquals(setOf("client_id", "handler", "redirect_uri", "type"), body.keys().asSequence().toSet())
    }

    @Test
    fun redirectUriSharesSchemeAndHostWithClientId() {
        val client = java.net.URI(HaAuthClient.CLIENT_ID)
        val redirect = java.net.URI(HaAuthClient.REDIRECT_URI)
        assertEquals("https", client.scheme)
        assertEquals(client.scheme, redirect.scheme)
        assertEquals(client.authority, redirect.authority)
        assertTrue(client.path.isNotEmpty())
    }

    @Test
    fun submitWalksInvalidAuthThenMfaThenCode() = runBlocking {
        server.enqueue(json(
            """{"type":"form","flow_id":"f1","step_id":"init","data_schema":[{"type":"string","name":"username","required":true},""" +
                """{"type":"string","name":"password","required":true}],"errors":{"base":"invalid_auth"}}""",
        ))
        server.enqueue(json(
            """{"type":"form","flow_id":"f1","step_id":"mfa","data_schema":[{"type":"string","name":"code","required":true}],""" +
                """"errors":{},"description_placeholders":{"mfa_module_name":"Authenticator app","mfa_module_id":"totp"}}""",
        ))
        server.enqueue(json(
            """{"type":"form","flow_id":"f1","step_id":"mfa","data_schema":[{"type":"string","name":"code","required":true}],""" +
                """"errors":{"base":"invalid_code"},"description_placeholders":{"mfa_module_name":"Authenticator app"}}""",
        ))
        server.enqueue(json("""{"type":"create_entry","flow_id":"f1","handler":["homeassistant",null],"result":"auth-code-1","version":1}"""))

        val wrong = auth.submit("f1", mapOf("username" to "me", "password" to "nope")) as HaFlowStep.Form
        assertEquals("invalid_auth", wrong.error)
        assertTrue(wrong.errorMessage!!.startsWith("Wrong username or password"))

        val mfa = auth.submit("f1", mapOf("username" to "me", "password" to "right")) as HaFlowStep.Form
        assertEquals("mfa", mfa.stepId)
        assertTrue(mfa.fields.single().isOneTimeCode)
        assertEquals("Two-factor authentication · Authenticator app", mfa.title)

        val badCode = auth.submit("f1", mapOf("code" to "000000")) as HaFlowStep.Form
        assertEquals("invalid_code", badCode.error)

        val done = auth.submit("f1", mapOf("code" to "123456")) as HaFlowStep.Done
        assertEquals("auth-code-1", done.authCode)

        val first = server.takeRequest()
        assertEquals("/auth/login_flow/f1", first.path)
        val body = JSONObject(first.body.readUtf8())
        assertEquals(HaAuthClient.CLIENT_ID, body.getString("client_id"))
        assertEquals("me", body.getString("username"))
        assertEquals("nope", body.getString("password"))
    }

    @Test
    fun abortedFlowIsReported() = runBlocking {
        server.enqueue(json("""{"type":"abort","flow_id":"f1","reason":"login_expired"}"""))
        val step = auth.submit("f1", mapOf("code" to "1")) as HaFlowStep.Aborted
        assertEquals("login_expired", step.reason)
        assertTrue(step.message.contains("took too long"))
    }

    @Test
    fun expiredFlowIsMarkedGoneAndBannedIpExplained() = runBlocking {
        server.enqueue(json("""{"message":"Invalid flow specified"}""", 404))
        try {
            auth.submit("f1", mapOf("code" to "1"))
            fail("expected failure")
        } catch (e: HaAuthException) {
            assertTrue(e.flowGone)
            assertEquals("The sign-in expired — start again", e.message)
        }
        server.enqueue(MockResponse().setResponseCode(403).setBody("403: Forbidden"))
        try {
            auth.startFlow(HaAuthProvider("homeassistant", null, "Local"))
            fail("expected failure")
        } catch (e: HaAuthException) {
            assertEquals(403, e.httpCode)
            assertTrue(e.message!!.contains("ip_bans.yaml"))
        }
    }

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example/auth/login_flow"))
        try {
            auth.startFlow(HaAuthProvider("homeassistant", null, "Local"))
            fail("expected failure")
        } catch (e: HaAuthException) {
            assertEquals(302, e.httpCode)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun onboardingRequiredIsExplained() = runBlocking {
        server.enqueue(json("""{"message":"Onboarding not finished","message_code":"onboarding_required"}""", 400))
        try {
            auth.providers()
            fail("expected failure")
        } catch (e: HaAuthException) {
            assertEquals("Finish setting up Home Assistant in a browser first", e.message)
        }
    }

    @Test
    fun exchangeCodePostsAuthorizationCodeGrant() = runBlocking {
        server.enqueue(json("""{"access_token":"short","token_type":"Bearer","refresh_token":"refresh","expires_in":1800}"""))
        val tokens = auth.exchangeCode("auth-code-1")
        assertEquals(HaAuthTokens("short", "refresh", 1800), tokens)
        val request = server.takeRequest()
        assertEquals("/auth/token", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals(
            mapOf("grant_type" to "authorization_code", "code" to "auth-code-1", "client_id" to HaAuthClient.CLIENT_ID),
            request.form(),
        )
    }

    /** Fake HA WebSocket: auth with [acceptToken], lists [existingNames], mints "long-lived-token". */
    private fun fakeWebSocket(acceptToken: String, existingNames: List<String>, received: MutableList<JSONObject>) =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"auth_required","ha_version":"2026.11.2"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                received += message
                val id = message.optInt("id")
                when (message.optString("type")) {
                    "auth" -> webSocket.send(
                        if (message.optString("access_token") == acceptToken) """{"type":"auth_ok","ha_version":"2026.11.2"}"""
                        else """{"type":"auth_invalid","message":"Invalid access token or password"}""",
                    )
                    "auth/refresh_tokens" -> {
                        val tokens = existingNames.joinToString(",") { """{"client_name":"$it","type":"long_lived_access_token","id":"x"}""" }
                        webSocket.send("""{"id":$id,"type":"result","success":true,"result":[$tokens,{"client_name":null,"client_id":"c","type":"normal","is_current":true,"id":"y"}]}""")
                    }
                    "auth/long_lived_access_token" -> webSocket.send(
                        """{"id":$id,"type":"result","success":true,"result":"long-lived-token"}""",
                    )
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
        }

    @Test
    fun mintsLongLivedTokenWithUniqueClientName() = runBlocking {
        val received = CopyOnWriteArrayList<JSONObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(
            fakeWebSocket("short", listOf("Slate TV (Chromecast)", "Slate TV (Chromecast) 2"), received),
        ))
        val token = auth.createLongLivedToken("short", "Slate TV (Chromecast)")
        assertEquals("long-lived-token", token)
        assertEquals("/api/websocket", server.takeRequest().path)
        val mint = received.single { it.optString("type") == "auth/long_lived_access_token" }
        assertEquals("Slate TV (Chromecast) 3", mint.getString("client_name"))
        assertEquals(3650, mint.getInt("lifespan"))
        assertTrue(mint.has("id"))
        assertEquals("short", received.first().getString("access_token"))
    }

    @Test
    fun completeSignInExchangesMintsValidatesThenRevokes() = runBlocking {
        val received = CopyOnWriteArrayList<JSONObject>()
        val order = CopyOnWriteArrayList<String>()
        val revokeForms = CopyOnWriteArrayList<Map<String, String>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                order += request.path!!
                return when (request.path) {
                    "/auth/token" -> json("""{"access_token":"short","token_type":"Bearer","refresh_token":"refresh","expires_in":1800}""")
                    "/api/websocket" -> MockResponse().withWebSocketUpgrade(fakeWebSocket("short", emptyList(), received))
                    "/api/" -> if (request.getHeader("Authorization") == "Bearer long-lived-token") json("""{"message":"API running."}""")
                    else MockResponse().setResponseCode(401)
                    "/api/config" -> json("""{"location_name":"Home","version":"2026.11.2"}""")
                    "/auth/revoke" -> {
                        revokeForms += request.form()
                        MockResponse().setResponseCode(200)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val result = auth.completeSignIn("auth-code-1", "Chromecast")
        assertEquals("long-lived-token", result.longLivedToken)
        assertEquals("Home", result.info.locationName)
        assertEquals(listOf("/auth/token", "/api/websocket", "/api/", "/api/config", "/auth/revoke"), order)
        assertEquals(listOf(mapOf("token" to "refresh")), revokeForms)
        assertEquals("Slate TV (Chromecast)", received.single { it.optString("type") == "auth/long_lived_access_token" }.getString("client_name"))
    }

    @Test
    fun shortLivedSessionIsRevokedEvenWhenMintingFails() = runBlocking {
        val order = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                order += request.path!!
                return when (request.path) {
                    "/auth/token" -> json("""{"access_token":"short","token_type":"Bearer","refresh_token":"refresh","expires_in":1800}""")
                    "/api/websocket" -> MockResponse().withWebSocketUpgrade(fakeWebSocket("other", emptyList(), CopyOnWriteArrayList()))
                    "/auth/revoke" -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }
        try {
            auth.completeSignIn("auth-code-1", "Chromecast")
            fail("expected failure")
        } catch (e: HaAuthException) {
            assertEquals("Home Assistant rejected the new session", e.message)
        }
        // /auth/revoke missing on old servers → legacy action=revoke on /auth/token.
        assertEquals(listOf("/auth/token", "/api/websocket", "/auth/revoke", "/auth/token"), order)
    }

    @Test
    fun clientNameHelpers() {
        assertEquals("Slate TV (Chromecast HD)", HaAuthClient.clientNameFor(" Chromecast HD "))
        assertEquals("Slate TV", HaAuthClient.clientNameFor(""))
        assertEquals("Slate TV", HaAuthClient.uniqueClientName("Slate TV", setOf("Other")))
        assertEquals("Slate TV 2", HaAuthClient.uniqueClientName("Slate TV", setOf("Slate TV")))
    }
}
