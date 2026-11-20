package com.stasisstate.slatetv.connector.immich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException

/**
 * Lightweight, header-authenticated REST client for self-hosted Immich servers (v1.115+, v2.x, v3.x).
 *
 * Security & reliability hardening:
 * - Authenticates via the `x-api-key` header, or `x-immich-share-key` for a phone-paired shared album
 *   (never query params).
 * - Origin-bound redirects: strips auth headers if any redirect changes scheme, host, or port,
 *   and drops body headers when an HTTP 303 turns a POST into a GET.
 * - Self-signed HTTPS is supported ONLY through a user-confirmed TOFU pin ([TofuTls.buildPinnedSsl]); there is
 *   no trust-all mode. Without a pin the platform's system trust store applies.
 * - All OkHttp calls are cooperatively cancellable with Kotlin coroutines.
 */
class ImmichApiClient(
    private val baseStandardClient: OkHttpClient = defaultClient(),
) {
    private val recentPageCounter = AtomicInteger(1)

    private var cachedPinKey: String? = null
    private var cachedPinnedClient: OkHttpClient? = null

    /** Returns the system-trust client, or a client trusting exactly the pinned leaf for this server origin. */
    @Synchronized
    fun clientFor(config: ImmichConfig): OkHttpClient {
        val der = config.activePinnedCertDer ?: return baseStandardClient
        val host = config.normalizedServerUrl.toHttpUrlOrNull()?.host ?: return baseStandardClient
        val key = "${host.lowercase()}|${TofuTls.sha256Hex(der)}"
        cachedPinnedClient?.let { if (cachedPinKey == key) return it }
        val pinned = TofuTls.buildPinnedSsl(der, host)
        val created = baseStandardClient.newBuilder()
            .sslSocketFactory(pinned.sslSocketFactory, pinned.trustManager)
            .hostnameVerifier(pinned.hostnameVerifier)
            .build()
        cachedPinKey = key
        cachedPinnedClient = created
        return created
    }

    /**
     * Unauthenticated reachability + TLS preflight (`GET /api/server/ping`). Used before asking for a password
     * so an untrusted certificate is confirmed (and pinned) before any credential is typed.
     */
    suspend fun ping(serverUrl: String, pinnedCert: PinnedCert? = null): ImmichConnectionResult =
        withContext(Dispatchers.IO) {
            val base = validateServerUrl(serverUrl)
                ?: return@withContext ImmichConnectionResult.Failure("Invalid server URL. Use http://host:2283 or https://host.")
            val client = clientFor(ImmichConfig(serverUrl = base, pinnedCert = pinnedCert))
            try {
                // Any HTTP response proves reachability + TLS trust; some proxies answer the ping with 403/404.
                client.executeCancellable(Request.Builder().url("$base/api/server/ping").get().build()).use { resp ->
                    ImmichConnectionResult.Success(albumCount = 0, serverRespondedPing = resp.isSuccessful)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                networkFailure(base, e)
            } catch (e: IllegalArgumentException) {
                ImmichConnectionResult.Failure("Invalid server URL: ${e.message}")
            }
        }

    private fun networkFailure(base: String, e: IOException): ImmichConnectionResult.Failure = when {
        TofuTls.isUntrustedCertificateError(e) -> ImmichConnectionResult.Failure(
            message = UNTRUSTED_CERT_MESSAGE,
            isTlsError = true,
            isUntrustedCertificate = true,
        )
        e is SSLException -> ImmichConnectionResult.Failure(
            message = "Secure connection to $base failed (${e.message ?: "TLS error"}).",
            isTlsError = true,
        )
        else -> ImmichConnectionResult.Failure("Cannot reach $base (${e.message ?: "network error"}).")
    }

    fun validateServerUrl(rawUrl: String): String? {
        val normalized = ImmichConfig.normalizeUrl(rawUrl)
        val parsed = normalized.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        if (parsed.host.isBlank()) return null
        return normalized
    }

    fun buildAssetImageUrl(config: ImmichConfig, assetId: String): String {
        val base = config.normalizedServerUrl
        val built = base.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("api")
            ?.addPathSegment("assets")
            ?.addPathSegment(assetId)
            ?.addPathSegment("thumbnail")
            ?.addQueryParameter("size", config.imageQuality.apiSize)
            ?.build()
        return built?.toString()
            ?: "$base/api/assets/$assetId/thumbnail?size=${config.imageQuality.apiSize}"
    }

    fun buildAuthHeaders(config: ImmichConfig): Map<String, String> = mapOf(
        config.authHeader,
        "Accept" to "image/*",
    )

    fun resetPagination() {
        recentPageCounter.set(1)
    }

    suspend fun testConnection(config: ImmichConfig): ImmichConnectionResult =
        withContext(Dispatchers.IO) {
            val base = validateServerUrl(config.serverUrl)
                ?: return@withContext ImmichConnectionResult.Failure("Invalid server URL. Use http://host:2283 or https://host.")
            if (!config.isConfigured) {
                return@withContext ImmichConnectionResult.Failure(
                    message = "Not signed in — sign in or pair with your phone.",
                    isAuthError = true,
                )
            }

            val safeConfig = config.copy(serverUrl = base)
            val client = clientFor(safeConfig)

            val pingOk = try {
                val pingReq = Request.Builder()
                    .url("$base/api/server/ping")
                    .get()
                    .build()
                client.executeCancellable(pingReq).use { it.isSuccessful }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withContext networkFailure(base, e)
            } catch (e: IllegalArgumentException) {
                return@withContext ImmichConnectionResult.Failure("Invalid server URL: ${e.message}")
            }

            if (safeConfig.usesSharedLink) {
                return@withContext testSharedLink(client, safeConfig, pingOk)
            }

            try {
                val albums = fetchAlbumsInternal(client, safeConfig)
                // Also verify asset.read + asset.view permission by testing one random asset thumbnail if available
                val sampleAssets = runCatching {
                    fetchRandomInternal(client, safeConfig, favoritesOnly = false, size = 1)
                }.getOrDefault(emptyList())
                val firstAsset = sampleAssets.firstOrNull()
                if (firstAsset != null) {
                    val thumbReq = Request.Builder()
                        .url(buildAssetImageUrl(safeConfig, firstAsset.id))
                        .auth(safeConfig)
                        .header("Accept", "image/*")
                        .get()
                        .build()
                    client.executeCancellable(thumbReq).use { resp ->
                        if (resp.code == 401 || resp.code == 403) {
                            throw ImmichAuthException(
                                resp.code,
                                "API key can read albums but lacks asset.view permission (HTTP ${resp.code}).",
                            )
                        }
                    }
                }
                ImmichConnectionResult.Success(
                    albumCount = albums.size,
                    serverRespondedPing = pingOk,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImmichAuthException) {
                ImmichConnectionResult.Failure(
                    message = e.message ?: "Authentication failed (HTTP ${e.code}). Check API key & scopes.",
                    isAuthError = true,
                )
            } catch (e: SSLException) {
                networkFailure(base, e)
            } catch (e: Exception) {
                ImmichConnectionResult.Failure(
                    message = e.message ?: "Unexpected error connecting to Immich.",
                )
            }
        }

    /**
     * Signs in with email + password, mints a scoped "Slate TV" API key, then discards the credentials.
     *
     * - `POST /api/auth/login` -> `accessToken` (held only in local variables).
     * - `POST /api/api-keys` with `Bearer <accessToken>` and scoped permissions; retries once without
     *   `permissions` if an older server answers HTTP 400.
     * - Best-effort `POST /api/auth/logout` so no lingering session is left on the server.
     * - Redirects are NOT followed (a redirect could replay the password body to another origin).
     * - The password and token are never logged, never put into messages/results, and never stored.
     */
    suspend fun signInAndCreateApiKey(
        serverUrl: String,
        email: String,
        password: String,
        pinnedCert: PinnedCert? = null,
    ): ImmichSignInResult = withContext(Dispatchers.IO) {
        val base = validateServerUrl(serverUrl)
            ?: return@withContext ImmichSignInResult.Failure(
                "Invalid server URL. Use an address like https://photos.example.com.",
            )
        if (email.isBlank() || password.isEmpty()) {
            return@withContext ImmichSignInResult.Failure("Enter your Immich email and password.")
        }
        // No redirect interceptor and followRedirects(false): the password body is never replayed anywhere.
        val client = clientFor(ImmichConfig(serverUrl = base, pinnedCert = pinnedCert))
            .newBuilder().apply { interceptors().clear() }
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var accessToken: String? = null
        try {
            val loginBody = JSONObject().put("email", email.trim()).put("password", password).toString()
            accessToken = client.executeCancellable(
                Request.Builder()
                    .url("$base/api/auth/login")
                    .header("Accept", "application/json")
                    .post(loginBody.toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ).use { resp ->
                when {
                    resp.isSuccessful -> JSONObject(readBoundedJsonBody(resp)).optString("accessToken")
                        .takeIf { it.isNotBlank() }
                        ?: return@withContext ImmichSignInResult.Failure("Immich did not return a session. Is this an Immich server?")
                    resp.isRedirect -> return@withContext ImmichSignInResult.Failure(
                        "The server redirected the sign-in request. Enter the exact address, including https:// if required.",
                    )
                    resp.code == 400 || resp.code == 401 -> return@withContext ImmichSignInResult.Failure(
                        "Incorrect email or password.",
                        isAuthError = true,
                    )
                    resp.code == 404 -> return@withContext ImmichSignInResult.Failure(
                        "Immich was not found at that address (HTTP 404).",
                    )
                    else -> return@withContext ImmichSignInResult.Failure("Immich sign-in failed (HTTP ${resp.code}).")
                }
            }

            val scoped = JSONObject().put("name", API_KEY_NAME)
                .put("permissions", JSONArray(API_KEY_PERMISSIONS)).toString()
            val legacy = JSONObject().put("name", API_KEY_NAME).toString()
            var secret: String? = null
            var code = 0
            for (payload in listOf(scoped, legacy)) {
                val (status, minted) = client.executeCancellable(
                    Request.Builder()
                        .url("$base/api/api-keys")
                        .header("Authorization", "Bearer $accessToken")
                        .header("Accept", "application/json")
                        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                        .build(),
                ).use { resp ->
                    resp.code to if (resp.isSuccessful) {
                        JSONObject(readBoundedJsonBody(resp)).optString("secret").takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
                code = status
                secret = minted
                if (status != 400) break // only an older server rejecting `permissions` warrants the fallback
            }
            when {
                secret != null -> ImmichSignInResult.Success(base, secret)
                code in 200..299 -> ImmichSignInResult.Failure("Immich did not return an API key.")
                code == 401 || code == 403 -> ImmichSignInResult.Failure(
                    "This account is not allowed to create API keys (HTTP $code).",
                    isAuthError = true,
                )
                else -> ImmichSignInResult.Failure("Could not create an API key (HTTP $code).")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            when {
                TofuTls.isUntrustedCertificateError(e) -> ImmichSignInResult.Failure(
                    UNTRUSTED_CERT_MESSAGE,
                    isTlsError = true,
                    isUntrustedCertificate = true,
                )
                e is SSLException -> ImmichSignInResult.Failure("Secure connection to $base failed.", isTlsError = true)
                else -> ImmichSignInResult.Failure("Cannot reach $base. Check the address and your network.")
            }
        } catch (_: Exception) {
            ImmichSignInResult.Failure("Unexpected response from Immich during sign-in.")
        } finally {
            accessToken?.let { token ->
                // Best-effort server-side logout; local copies of credentials go out of scope here.
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url("$base/api/auth/logout")
                            .header("Authorization", "Bearer $token")
                            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                            .build(),
                    ).execute().close()
                }
            }
            accessToken = null
        }
    }

    /** Verifies a shared-album credential with `GET /api/shared-links/me`. */
    private suspend fun testSharedLink(
        client: OkHttpClient,
        config: ImmichConfig,
        pingOk: Boolean,
    ): ImmichConnectionResult = try {
        val req = Request.Builder()
            .url("${config.normalizedServerUrl}/api/shared-links/me")
            .auth(config)
            .header("Accept", "application/json")
            .get()
            .build()
        client.executeCancellable(req).use { resp ->
            checkResponse(resp.code, resp.message, sharedLink = true)
            val link = JSONObject(readBoundedJsonBody(resp))
            val album = link.optJSONObject("album")
            if (album == null || album.optString("id") != config.sharedAlbumId) {
                return ImmichConnectionResult.Failure("The shared link no longer points at the paired album — pair again.")
            }
        }
        // Prove photos can actually be listed and downloaded with the share key, not just that the link exists.
        val sample = fetchSharedAlbumInternal(client, config, maxItems = 1).firstOrNull { it.isImage }
        if (sample != null) {
            val thumb = Request.Builder().url(buildAssetImageUrl(config, sample.id)).auth(config)
                .header("Accept", "image/*").get().build()
            client.executeCancellable(thumb).use { checkResponse(it.code, it.message, sharedLink = true) }
        }
        ImmichConnectionResult.Success(albumCount = 1, serverRespondedPing = pingOk)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ImmichAuthException) {
        ImmichConnectionResult.Failure(e.message ?: "Shared link rejected.", isAuthError = true)
    } catch (e: IOException) {
        networkFailure(config.normalizedServerUrl, e)
    } catch (_: Exception) {
        ImmichConnectionResult.Failure("Unexpected response from Immich.")
    }

    /**
     * Tries each of [ImmichConfig.candidateUrls] for what the user typed and returns the first address whose
     * `GET /api/server/ping` answers `pong` — so "192.168.1.5" finds `http://192.168.1.5:2283` on its own.
     */
    suspend fun findServer(rawUrl: String, pinnedCert: PinnedCert? = null): ImmichServerProbe =
        withContext(Dispatchers.IO) {
            val candidates = ImmichConfig.candidateUrls(rawUrl).filter { validateServerUrl(it) != null }
            if (candidates.isEmpty()) {
                return@withContext ImmichServerProbe.NotFound("That doesn't look like a valid server address.")
            }
            var firstFailure: String? = null
            var firstResponder: String? = null
            for (candidate in candidates) {
                val client = clientFor(ImmichConfig(serverUrl = candidate, pinnedCert = pinnedCert))
                try {
                    val req = Request.Builder().url("$candidate/api/server/ping").header("Accept", "application/json")
                        .get().build()
                    client.executeCancellable(req).use { resp ->
                        val body = if (resp.isSuccessful) runCatching { readBoundedJsonBody(resp) }.getOrDefault("") else ""
                        if (body.contains("pong")) return@withContext ImmichServerProbe.Found(candidate)
                        if (firstResponder == null) firstResponder = candidate
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (TofuTls.isUntrustedCertificateError(e)) {
                        return@withContext ImmichServerProbe.UntrustedCertificate(candidate)
                    }
                    if (firstFailure == null) firstFailure = networkFailure(candidate, e).message
                } catch (_: IllegalArgumentException) {
                    // Try the next candidate.
                }
            }
            // Something answered, just not with Immich's pong (e.g. a proxy guarding /ping): let the caller try it.
            firstResponder?.let { return@withContext ImmichServerProbe.Found(it) }
            ImmichServerProbe.NotFound(firstFailure ?: "Immich was not found at that address.")
        }

    /** Unauthenticated `GET /api/server/version`, or null if the server doesn't answer it. */
    suspend fun serverVersion(serverUrl: String, pinnedCert: PinnedCert? = null): ImmichServerVersion? =
        withContext(Dispatchers.IO) {
            val base = validateServerUrl(serverUrl) ?: return@withContext null
            val client = clientFor(ImmichConfig(serverUrl = base, pinnedCert = pinnedCert))
            try {
                val req = Request.Builder().url("$base/api/server/version").header("Accept", "application/json")
                    .get().build()
                client.executeCancellable(req).use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val obj = JSONObject(readBoundedJsonBody(resp))
                    if (!obj.has("major")) return@use null
                    ImmichServerVersion(obj.optInt("major"), obj.optInt("minor"), obj.optInt("patch"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Phone pairing, one poll: looks up the album shared link whose custom URL is [slug] with
     * `GET /api/shared-links/me` (unauthenticated — the slug is the lookup). Only TV → Immich traffic.
     */
    suspend fun findSharedLinkBySlug(
        serverUrl: String,
        slug: String,
        pinnedCert: PinnedCert? = null,
    ): ImmichSharedLinkLookup = withContext(Dispatchers.IO) {
        val base = validateServerUrl(serverUrl)
            ?: return@withContext ImmichSharedLinkLookup.Failure("Invalid server address.")
        val client = clientFor(ImmichConfig(serverUrl = base, pinnedCert = pinnedCert))
        try {
            val req = Request.Builder()
                .url("$base/api/shared-links/me")
                .header(ImmichConfig.HEADER_SHARE_SLUG, slug)
                .header("Accept", "application/json")
                .get()
                .build()
            client.executeCancellable(req).use { resp ->
                when {
                    resp.isSuccessful -> {
                        val link = JSONObject(readBoundedJsonBody(resp))
                        val key = link.optString("key")
                        val album = link.optJSONObject("album")
                        when {
                            !link.optString("type").equals("ALBUM", ignoreCase = true) || album == null ->
                                ImmichSharedLinkLookup.NotAnAlbum
                            key.isBlank() || album.optString("id").isBlank() ->
                                ImmichSharedLinkLookup.Failure("Immich returned an incomplete shared link.")
                            else -> ImmichSharedLinkLookup.Found(
                                key = key,
                                albumId = album.optString("id"),
                                albumName = album.optString("albumName", "Shared album"),
                                assetCount = album.optInt("assetCount", 0),
                            )
                        }
                    }
                    resp.code == 401 || resp.code == 403 || resp.code == 404 -> {
                        val message = runCatching { JSONObject(readBoundedJsonBody(resp)).optString("message") }
                            .getOrDefault("")
                        if (message.contains("password", ignoreCase = true)) {
                            ImmichSharedLinkLookup.PasswordProtected
                        } else {
                            ImmichSharedLinkLookup.NotYet
                        }
                    }
                    else -> ImmichSharedLinkLookup.Failure("Immich returned HTTP ${resp.code}.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val failure = networkFailure(base, e)
            ImmichSharedLinkLookup.Failure(failure.message, isUntrustedCertificate = failure.isUntrustedCertificate)
        } catch (_: Exception) {
            ImmichSharedLinkLookup.Failure("Unexpected response from Immich.")
        }
    }

    suspend fun listAlbums(config: ImmichConfig): List<ImmichAlbum> =
        withContext(Dispatchers.IO) {
            val base = validateServerUrl(config.serverUrl) ?: return@withContext emptyList()
            if (config.apiKey.isBlank()) return@withContext emptyList() // shared links can't list albums
            val safeConfig = config.copy(serverUrl = base)
            fetchAlbumsInternal(clientFor(safeConfig), safeConfig)
        }

    suspend fun fetchCandidateAssets(
        config: ImmichConfig,
        batchSize: Int = 24,
    ): List<ImmichAsset> = withContext(Dispatchers.IO) {
        val base = validateServerUrl(config.serverUrl) ?: return@withContext emptyList()
        if (!config.isConfigured) return@withContext emptyList()
        val safeConfig = config.copy(serverUrl = base)
        val client = clientFor(safeConfig)
        if (safeConfig.usesSharedLink) {
            return@withContext filterEligible(
                fetchSharedAlbumInternal(client, safeConfig, maxItems = batchSize),
                landscapeOnly = safeConfig.landscapeOnly,
            ).shuffled().take(batchSize * 2)
        }
        val raw = when (safeConfig.sourceMode) {
            ImmichSourceMode.RANDOM_LIBRARY -> fetchRandomInternal(client, safeConfig, favoritesOnly = false, size = batchSize)
            ImmichSourceMode.FAVORITES -> fetchRandomInternal(client, safeConfig, favoritesOnly = true, size = batchSize)
            ImmichSourceMode.RECENT -> fetchRecentInternal(client, safeConfig, size = batchSize)
            ImmichSourceMode.ALBUMS -> fetchFromSelectedAlbumsInternal(client, safeConfig, maxItems = batchSize)
        }
        filterEligible(raw, landscapeOnly = safeConfig.landscapeOnly)
    }

    private fun filterEligible(raw: List<ImmichAsset>, landscapeOnly: Boolean): List<ImmichAsset> {
        val imagesOnly = raw.filter { it.isImage }.distinctBy { it.id }
        if (!landscapeOnly) return imagesOnly
        val landscape = imagesOnly.filter { it.isLandscape }
        return landscape.ifEmpty { imagesOnly }
    }

    private suspend fun fetchAlbumsInternal(client: OkHttpClient, config: ImmichConfig): List<ImmichAlbum> {
        val req = Request.Builder()
            .url("${config.normalizedServerUrl}/api/albums")
            .auth(config)
            .header("Accept", "application/json")
            .get()
            .build()

        client.executeCancellable(req).use { resp ->
            checkResponse(resp.code, resp.message)
            val body = readBoundedJsonBody(resp)
            val array = JSONArray(body)
            return buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isNotBlank()) {
                        add(
                            ImmichAlbum(
                                id = id,
                                name = obj.optString("albumName", "Untitled Album"),
                                assetCount = obj.optInt("assetCount", 0),
                                description = obj.optString("description", ""),
                            ),
                        )
                    }
                }
            }.sortedWith(compareByDescending<ImmichAlbum> { it.assetCount }.thenBy { it.name.lowercase() })
        }
    }

    private suspend fun fetchRandomInternal(
        client: OkHttpClient,
        config: ImmichConfig,
        favoritesOnly: Boolean,
        size: Int,
    ): List<ImmichAsset> {
        val primaryPayload = JSONObject().apply {
            put("type", "IMAGE")
            put("visibility", "timeline")
            put("withExif", true)
            put("size", size.coerceIn(1, 100))
            if (favoritesOnly) put("isFavorite", true)
        }
        val legacyPayload = JSONObject().apply {
            put("type", "IMAGE")
            put("isArchived", false)
            put("withExif", true)
            put("size", size.coerceIn(1, 100))
            if (favoritesOnly) put("isFavorite", true)
        }
        return postSearchWithLegacyFallback(
            client = client,
            config = config,
            path = "/api/search/random",
            primaryPayload = primaryPayload,
            legacyPayload = legacyPayload,
        ) { body ->
            parseAssetArray(JSONArray(body))
        }
    }

    private suspend fun fetchRecentInternal(
        client: OkHttpClient,
        config: ImmichConfig,
        size: Int,
    ): List<ImmichAsset> {
        val currentPage = recentPageCounter.get().coerceAtLeast(1)
        val primaryPayload = JSONObject().apply {
            put("type", "IMAGE")
            put("visibility", "timeline")
            put("order", "desc")
            put("size", size.coerceIn(1, 100))
            put("page", currentPage)
            put("withExif", true)
        }
        val legacyPayload = JSONObject().apply {
            put("type", "IMAGE")
            put("isArchived", false)
            put("order", "desc")
            put("size", size.coerceIn(1, 100))
            put("page", currentPage)
            put("withExif", true)
        }
        return postSearchWithLegacyFallback(
            client = client,
            config = config,
            path = "/api/search/metadata",
            primaryPayload = primaryPayload,
            legacyPayload = legacyPayload,
        ) { body ->
            val root = JSONObject(body)
            val assetsObj = root.optJSONObject("assets")
            val nextPage = assetsObj?.optString("nextPage")?.toIntOrNull()
            if (nextPage != null && nextPage > currentPage && nextPage <= MAX_RECENT_PAGES) {
                recentPageCounter.set(nextPage)
            } else {
                recentPageCounter.set(1)
            }
            val items = assetsObj?.optJSONArray("items") ?: JSONArray()
            parseAssetArray(items)
        }
    }

    /**
     * Fetches photos from selected albums using paginated `/api/search/metadata` (compatible with
     * Immich v2 AND v3 where `GET /api/albums/{id}` removed embedded `assets`), falling back to
     * `GET /api/albums/{id}` only if metadata search returns empty on an older server.
     */
    private suspend fun fetchFromSelectedAlbumsInternal(
        client: OkHttpClient,
        config: ImmichConfig,
        maxItems: Int,
    ): List<ImmichAsset> {
        val albumIds = config.selectedAlbumIds.toList()
        if (albumIds.isEmpty()) {
            return fetchRandomInternal(client, config, favoritesOnly = false, size = maxItems)
        }

        val collected = mutableListOf<ImmichAsset>()
        val sampledAlbums = albumIds.shuffled().take(4)

        // Primary path: paginated POST /api/search/metadata scoped by albumIds (Immich v2 & v3 safe)
        for (albumId in sampledAlbums) {
            val payload = JSONObject().apply {
                put("albumIds", JSONArray(listOf(albumId)))
                put("type", "IMAGE")
                put("withExif", true)
                put("size", (maxItems * 2).coerceIn(12, 100))
            }
            val req = Request.Builder()
                .url("${config.normalizedServerUrl}/api/search/metadata")
                .auth(config)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val pageAssets = runCatching {
                client.executeCancellable(req).use { resp ->
                    // Older servers don't accept shared-link auth for search; fall through to GET /albums/{id}.
                    if ((resp.code == 401 || resp.code == 403) && !config.usesSharedLink) {
                        checkResponse(resp.code, resp.message)
                    }
                    if (!resp.isSuccessful) return@use emptyList()
                    val root = JSONObject(readBoundedJsonBody(resp))
                    val items = root.optJSONObject("assets")?.optJSONArray("items") ?: JSONArray()
                    parseAssetArray(items)
                }
            }.getOrElse { err ->
                if (err is CancellationException || err is ImmichAuthException) throw err
                emptyList()
            }
            collected += pageAssets
        }

        // Legacy fallback: GET /api/albums/{id} (Immich v1/v2 servers that didn't support albumIds in metadata search)
        if (collected.isEmpty()) {
            for (albumId in sampledAlbums) {
                val albumUrl = config.normalizedServerUrl.toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegment("api")
                    ?.addPathSegment("albums")
                    ?.addPathSegment(albumId)
                    ?.build()
                    ?: continue
                val req = Request.Builder()
                    .url(albumUrl)
                    .auth(config)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.executeCancellable(req).use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        checkResponse(resp.code, resp.message, sharedLink = config.usesSharedLink)
                    }
                    if (!resp.isSuccessful) return@use
                    val obj = JSONObject(readBoundedJsonBody(resp))
                    val assets = obj.optJSONArray("assets") ?: JSONArray()
                    collected += parseAssetArray(assets)
                }
            }
        }

        // Filter images + orientation BEFORE sampling so video-heavy albums don't starve photo results
        val eligible = filterEligible(collected, landscapeOnly = config.landscapeOnly)
        return eligible.shuffled().take(maxItems * 2)
    }

    /**
     * Lists a paired shared album through the timeline API (`/api/timeline/buckets` + `/api/timeline/bucket`),
     * the only listing that accepts shared-link auth on every server with custom link URLs (v1.137 → v3.x).
     * Samples up to [MAX_SHARED_BUCKETS] month buckets, weighted by size, so large albums rotate fully.
     */
    private suspend fun fetchSharedAlbumInternal(
        client: OkHttpClient,
        config: ImmichConfig,
        maxItems: Int,
    ): List<ImmichAsset> {
        val albumId = config.sharedAlbumId.takeIf { it.isNotBlank() } ?: return emptyList()
        fun timelineUrl(path: String, timeBucket: String? = null) = config.normalizedServerUrl.toHttpUrlOrNull()!!
            .newBuilder()
            .addPathSegment("api").addPathSegment("timeline").addPathSegment(path)
            .addQueryParameter("albumId", albumId)
            .apply { timeBucket?.let { addQueryParameter("timeBucket", it) } }
            .build()

        val buckets = client.executeCancellable(
            Request.Builder().url(timelineUrl("buckets")).auth(config).header("Accept", "application/json").get().build(),
        ).use { resp ->
            checkResponse(resp.code, resp.message, sharedLink = true)
            val array = JSONArray(readBoundedJsonBody(resp))
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val bucket = obj.optString("timeBucket")
                    val count = obj.optInt("count", 0)
                    if (bucket.isNotBlank() && count > 0) add(bucket to count)
                }
            }
        }
        val chosen = weightedSample(buckets, MAX_SHARED_BUCKETS)
        val collected = mutableListOf<ImmichAsset>()
        for (bucket in chosen) {
            client.executeCancellable(
                Request.Builder().url(timelineUrl("bucket", bucket)).auth(config)
                    .header("Accept", "application/json").get().build(),
            ).use { resp ->
                checkResponse(resp.code, resp.message, sharedLink = true)
                collected += parseTimelineBucket(JSONObject(readBoundedJsonBody(resp)))
            }
            if (collected.count { it.isImage } >= maxItems * 2) break
        }
        return collected
    }

    private fun weightedSample(buckets: List<Pair<String, Int>>, count: Int): List<String> {
        val pool = buckets.toMutableList()
        val picked = mutableListOf<String>()
        while (picked.size < count && pool.isNotEmpty()) {
            var roll = kotlin.random.Random.nextLong(pool.sumOf { it.second.toLong() })
            val index = pool.indexOfFirst { roll -= it.second; roll < 0 }.coerceAtLeast(0)
            picked += pool.removeAt(index).first
        }
        return picked
    }

    /** Timeline buckets are columnar: parallel arrays `id[]`, `isImage[]`, `ratio[]`, `fileCreatedAt[]`, ... */
    private fun parseTimelineBucket(root: JSONObject): List<ImmichAsset> {
        val ids = root.optJSONArray("id") ?: return emptyList()
        val isImage = root.optJSONArray("isImage")
        val ratio = root.optJSONArray("ratio")
        val created = root.optJSONArray("fileCreatedAt")
        val city = root.optJSONArray("city")
        val country = root.optJSONArray("country")
        val trashed = root.optJSONArray("isTrashed")
        fun JSONArray?.text(i: Int) = this?.optString(i)?.takeIf { it.isNotBlank() && it != "null" }
        return buildList {
            for (i in 0 until ids.length()) {
                val id = ids.optString(i).takeIf { it.isNotBlank() } ?: continue
                if (trashed?.optBoolean(i, false) == true) continue
                val r = ratio?.optDouble(i, 0.0)?.takeIf { !it.isNaN() && it > 0 } ?: 0.0
                add(
                    ImmichAsset(
                        id = id,
                        type = if (isImage?.optBoolean(i, true) != false) "IMAGE" else "VIDEO",
                        width = if (r > 0) (r * 1000).toInt() else 0,
                        height = if (r > 0) 1000 else 0,
                        dateTakenIso = created.text(i),
                        city = city.text(i),
                        country = country.text(i),
                    ),
                )
            }
        }
    }

    private suspend fun postSearchWithLegacyFallback(
        client: OkHttpClient,
        config: ImmichConfig,
        path: String,
        primaryPayload: JSONObject,
        legacyPayload: JSONObject,
        parse: (String) -> List<ImmichAsset>,
    ): List<ImmichAsset> {
        val url = "${config.normalizedServerUrl}$path"
        val req1 = Request.Builder()
            .url(url)
            .auth(config)
            .header("Accept", "application/json")
            .post(primaryPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.executeCancellable(req1).use { resp ->
            if (resp.isSuccessful) {
                return parse(readBoundedJsonBody(resp))
            }
            if (resp.code != 400) {
                checkResponse(resp.code, resp.message)
            }
        }

        // Fallback for older Immich v1.x servers rejecting `visibility`
        val req2 = Request.Builder()
            .url(url)
            .auth(config)
            .header("Accept", "application/json")
            .post(legacyPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.executeCancellable(req2).use { resp ->
            checkResponse(resp.code, resp.message)
            return parse(readBoundedJsonBody(resp))
        }
    }

    private fun readBoundedJsonBody(resp: Response): String {
        val body = resp.body ?: return ""
        if (body.contentLength() > MAX_JSON_BYTES) {
            throw IOException("Immich JSON response exceeds $MAX_JSON_BYTES bytes")
        }
        val source = body.source()
        if (source.request(MAX_JSON_BYTES + 1)) {
            throw IOException("Immich JSON response exceeds $MAX_JSON_BYTES bytes")
        }
        return source.readUtf8()
    }

    private fun parseAssetArray(array: JSONArray): List<ImmichAsset> {
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                val exif = obj.optJSONObject("exifInfo")
                val rawWidth = exif?.optInt("exifImageWidth", 0) ?: 0
                val rawHeight = exif?.optInt("exifImageHeight", 0) ?: 0
                val orientation = exif?.optString("orientation", "")?.trim().orEmpty()
                val rotated = orientation in ROTATED_EXIF_ORIENTATIONS
                val width = if (rotated) rawHeight else rawWidth
                val height = if (rotated) rawWidth else rawHeight

                val dateTaken = exif?.optString("dateTimeOriginal")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: obj.optString("fileCreatedAt").takeIf { it.isNotBlank() && it != "null" }

                add(
                    ImmichAsset(
                        id = id,
                        type = obj.optString("type", "IMAGE"),
                        originalFileName = obj.optString("originalFileName", ""),
                        width = width,
                        height = height,
                        dateTakenIso = dateTaken,
                        city = exif?.optString("city")?.takeIf { it.isNotBlank() && it != "null" },
                        state = exif?.optString("state")?.takeIf { it.isNotBlank() && it != "null" },
                        country = exif?.optString("country")?.takeIf { it.isNotBlank() && it != "null" },
                    ),
                )
            }
        }
    }

    private fun checkResponse(code: Int, message: String, sharedLink: Boolean = false) {
        when (code) {
            in 200..299 -> Unit
            401 -> throw ImmichAuthException(
                401,
                if (sharedLink) {
                    "The shared album link no longer works (HTTP 401). It may have expired or been deleted — pair again."
                } else {
                    "Invalid Immich API key (HTTP 401)."
                },
            )
            403 -> throw ImmichAuthException(403, "Immich API key lacks required permissions (HTTP 403).")
            else -> throw IOException("Immich server returned HTTP $code: $message")
        }
    }

    class ImmichAuthException(val code: Int, message: String) : IOException(message)

    companion object {
        const val HEADER_API_KEY = ImmichConfig.HEADER_API_KEY
        private val SENSITIVE_HEADERS = listOf(
            ImmichConfig.HEADER_API_KEY,
            ImmichConfig.HEADER_SHARE_KEY,
            ImmichConfig.HEADER_SHARE_SLUG,
            "Authorization",
        )

        private fun Request.Builder.auth(config: ImmichConfig): Request.Builder =
            config.authHeader.let { (name, value) -> header(name, value) }
        const val UNTRUSTED_CERT_MESSAGE =
            "This server's HTTPS certificate is not trusted. Review and pin it to continue."

        /** Headers describing a request body; dropped when a 303 switches the request to bodyless GET (RFC 9110 §15.4). */
        private val BODY_REPRESENTATION_HEADERS =
            listOf("Content-Type", "Content-Length", "Content-Encoding", "Content-Language", "Transfer-Encoding")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ROTATED_EXIF_ORIENTATIONS = setOf("5", "6", "7", "8")
        private const val API_KEY_NAME = "Slate TV"
        private val API_KEY_PERMISSIONS = listOf("album.read", "asset.read", "asset.view")
        private const val MAX_RECENT_PAGES = 10
        private const val MAX_SHARED_BUCKETS = 3
        private const val MAX_JSON_BYTES = 4L * 1024 * 1024
        private const val MAX_REDIRECTS = 5

        suspend fun OkHttpClient.executeCancellable(request: Request): Response =
            newCall(request).executeCancellable()

        /** Enqueues this [Call] and suspends for its response, cancelling the call if the coroutine is cancelled. */
        suspend fun Call.executeCancellable(): Response {
            val call = this
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        cont.resume(response) { _, discarded, _ -> discarded.close() }
                    }
                })
            }
        }

        /**
         * Builds an OkHttpClient (system trust only) that strips sensitive auth headers (`x-api-key`,
         * `x-immich-share-*`, `Authorization`) on any cross-origin HTTP redirect so credentials can never leak to third-party hosts,
         * and switches HTTP 303 redirects to a bodyless GET per RFC 9110.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val original = chain.request()
                var currentReq = original
                var response = chain.proceed(currentReq)
                var redirectCount = 0
                while (response.isRedirect && redirectCount < MAX_REDIRECTS) {
                    val location = response.header("Location") ?: break
                    val nextUrl = currentReq.url.resolve(location) ?: break
                    val sameOrigin = nextUrl.scheme == original.url.scheme &&
                        nextUrl.host.equals(original.url.host, ignoreCase = true) &&
                        nextUrl.port == original.url.port
                    val code = response.code
                    response.close()
                    val nextBuilder = currentReq.newBuilder().url(nextUrl)
                    if (code == 303 && currentReq.method != "GET" && currentReq.method != "HEAD") {
                        nextBuilder.method("GET", null)
                        BODY_REPRESENTATION_HEADERS.forEach(nextBuilder::removeHeader)
                    }
                    if (!sameOrigin) SENSITIVE_HEADERS.forEach(nextBuilder::removeHeader)
                    currentReq = nextBuilder.build()
                    response = chain.proceed(currentReq)
                    redirectCount++
                }
                response
            }
            .build()
    }
}
