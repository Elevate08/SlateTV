package com.stasisstate.slatetv.connector.extensions

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

internal fun loopbackServer(): MockWebServer = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

internal fun MockWebServer.base(): HttpUrl = "http://127.0.0.1:$port/".toHttpUrl()

internal fun json(body: String, code: Int = 200) =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

internal fun sampleManifest(
    id: String = "com.example.alpine-feed",
    type: ExtensionType = ExtensionType.BOTH,
    manifestUrl: String = "https://example.com/manifest.json",
    backgroundUrl: String? = "https://example.com/backgrounds.json",
    shelfUrl: String? = "https://example.com/shelf.json",
    refresh: Int = 900,
    authHeaderName: String? = "X-API-Key",
) = ExtensionManifest(
    id = id, name = "Alpine Gallery", description = "Mountains", version = "1.0.0", author = "Author",
    type = type, manifestUrl = manifestUrl, backgroundUrl = backgroundUrl, shelfUrl = shelfUrl,
    refreshIntervalSeconds = refresh, authHeaderName = authHeaderName,
)

internal fun installed(manifest: ExtensionManifest, enabled: Boolean = true) =
    InstalledExtension(manifest, enabled, installedAtEpochMs = 1_000L)

internal class FakeKeyValueStore : KeyValueStore {
    val map = HashMap<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String): Boolean { map[key] = value; return true }
    override fun remove(key: String): Boolean { map.remove(key); return true }
}

internal class FakeVault : SecretVault {
    val secrets = HashMap<String, String>()
    override fun has(id: String) = id in secrets
    override fun get(id: String) = secrets[id]
    override fun put(id: String, secret: String) { secrets[id] = secret }
    override fun remove(id: String) { secrets.remove(id) }
}

internal class FakeClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke() = now
}
