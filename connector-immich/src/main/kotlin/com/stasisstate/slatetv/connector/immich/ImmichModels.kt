package com.stasisstate.slatetv.connector.immich

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ImmichSourceMode(val displayName: String, val description: String) {
    RANDOM_LIBRARY("Random Library", "Random photos from your entire Immich library"),
    ALBUMS("Selected Albums", "Shuffle photos from specific albums you choose"),
    FAVORITES("Favorites", "Random photos marked as favorites in Immich"),
    RECENT("Recent Photos", "Newest photos uploaded to your Immich library"),
}

enum class ImmichImageQuality(val apiSize: String, val displayName: String) {
    PREVIEW("preview", "Preview (Fast, 1440p — recommended for TV)"),
    FULLSIZE("fullsize", "Full Size (Original resolution, higher RAM)"),
}

data class ImmichConfig(
    val serverUrl: String = "",
    val apiKey: String = "",
    val sourceMode: ImmichSourceMode = ImmichSourceMode.RANDOM_LIBRARY,
    val selectedAlbumIds: Set<String> = emptySet(),
    val imageQuality: ImmichImageQuality = ImmichImageQuality.PREVIEW,
    val landscapeOnly: Boolean = true,
    /** User-confirmed TOFU certificate pin; only honoured for `https://` on [PinnedCert.origin]. */
    val pinnedCert: PinnedCert? = null,
) {
    val normalizedServerUrl: String
        get() = normalizeUrl(serverUrl)

    /** `host:port` of the normalized server URL (lowercase), or null when it is not a valid http(s) URL. */
    val serverOrigin: String?
        get() = originOf(normalizedServerUrl)

    /** The pinned leaf DER to enforce for the current server, or null (system trust) when no pin applies. */
    val activePinnedCertDer: ByteArray?
        get() {
            val pin = pinnedCert ?: return null
            val url = normalizedServerUrl.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || originOf(normalizedServerUrl) != pin.origin) return null
            return pin.der
        }

    override fun toString(): String =
        "ImmichConfig(serverUrl=$normalizedServerUrl, apiKey=${if (apiKey.isBlank()) "<unset>" else "***"}, " +
            "sourceMode=$sourceMode, albums=${selectedAlbumIds.size}, quality=$imageQuality, " +
            "landscapeOnly=$landscapeOnly, pinnedCert=$pinnedCert)"

    /** Copy with a new server URL; drops album selection and the cert pin when the origin changes. */
    fun withServerUrl(url: String): ImmichConfig {
        val sameOrigin = originOf(normalizeUrl(url)) != null && originOf(normalizeUrl(url)) == serverOrigin
        return copy(
            serverUrl = url,
            selectedAlbumIds = if (normalizeUrl(url) == normalizedServerUrl) selectedAlbumIds else emptySet(),
            pinnedCert = if (sameOrigin) pinnedCert else null,
        )
    }

    val isConfigured: Boolean
        get() = normalizedServerUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        /** Lowercase `host:port` for an http(s) URL, or null. */
        fun originOf(url: String): String? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            return "${parsed.host.lowercase()}:${parsed.port}"
        }

        /**
         * Normalizes user input into a base URL. An explicit `http://` / `https://` scheme (and any port)
         * is always preserved. For bare input, public-looking domain names default to `https://`, while
         * IP literals, `localhost`, single-label / private-suffix hosts (`.local`, `.lan`, ...) and
         * hosts with an explicit non-443 port default to plain `http://` (typical for LAN Immich on :2283).
         */
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return trimmed
            }
            return "${defaultSchemeFor(trimmed)}://$trimmed"
        }

        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain")

        private fun defaultSchemeFor(bare: String): String {
            val authority = bare.substringBefore('/').substringBefore('?').substringAfterLast('@')
            if (authority.startsWith("[")) return "http"
            val host = authority.substringBefore(':').lowercase()
            val port = authority.substringAfter(':', "").toIntOrNull()
            if (port == 443) return "https"
            if (port != null) return "http"
            val local = host == "localhost" || IPV4_LITERAL.matches(host) || !host.contains('.') ||
                PRIVATE_SUFFIXES.any { host.endsWith(it) }
            return if (local) "http" else "https"
        }
    }
}

/**
 * A Trust-On-First-Use certificate pin: [derBase64] is the exact leaf certificate the user confirmed for
 * [origin] (`host:port`). Strings only, so data-class equality stays value-based.
 */
data class PinnedCert(val origin: String, val derBase64: String) {
    val der: ByteArray? get() = TofuTls.decodeBase64(derBase64)
    val sha256Hex: String? get() = der?.let(TofuTls::sha256Hex)
    val formattedFingerprint: String? get() = sha256Hex?.let(TofuTls::formatFingerprint)
    val host: String get() = origin.substringBeforeLast(':')
    val port: Int get() = origin.substringAfterLast(':').toIntOrNull() ?: 443

    override fun toString() = "PinnedCert(origin=$origin, sha256=${sha256Hex ?: "invalid"})"

    companion object {
        fun from(probed: TofuTls.ProbedCertificate): PinnedCert =
            PinnedCert("${probed.host.lowercase()}:${probed.port}", probed.derBase64)
    }
}

data class ImmichAlbum(
    val id: String,
    val name: String,
    val assetCount: Int,
    val description: String = "",
)

data class ImmichAsset(
    val id: String,
    val type: String,
    val originalFileName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val dateTakenIso: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
) {
    val isImage: Boolean
        get() = type.equals("IMAGE", ignoreCase = true)

    val isLandscape: Boolean
        get() = width == 0 || height == 0 || width >= height

    fun formatSubtitle(): String? {
        val parts = mutableListOf<String>()
        formatMonthYear(dateTakenIso)?.let { parts += it }
        val location = listOfNotNull(
            city?.takeIf { it.isNotBlank() },
            state?.takeIf { it.isNotBlank() } ?: country?.takeIf { it.isNotBlank() },
        ).joinToString(", ")
        if (location.isNotBlank()) {
            parts += location
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    companion object {
        private val MONTHS = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )

        internal fun formatMonthYear(iso: String?): String? {
            if (iso.isNullOrBlank() || iso.length < 7) return null
            val year = iso.substring(0, 4).toIntOrNull() ?: return null
            val month = iso.substring(5, 7).toIntOrNull() ?: return null
            if (month !in 1..12 || year < 1900) return null
            return "${MONTHS[month - 1]} $year"
        }
    }
}

sealed class ImmichConnectionResult {
    data class Success(
        val albumCount: Int,
        val serverRespondedPing: Boolean,
    ) : ImmichConnectionResult()

    data class Failure(
        val message: String,
        val isTlsError: Boolean = false,
        val isAuthError: Boolean = false,
        /** The server's certificate is not trusted (or no longer matches the pin): offer TOFU confirmation. */
        val isUntrustedCertificate: Boolean = false,
    ) : ImmichConnectionResult()
}

/** Outcome of [ImmichApiClient.signInAndCreateApiKey]. Never carries the password or session token. */
sealed class ImmichSignInResult {
    /** [apiKey] is the freshly minted, scoped Immich API key; [serverUrl] is the normalized base URL. */
    data class Success(val serverUrl: String, val apiKey: String) : ImmichSignInResult() {
        override fun toString() = "Success(serverUrl=$serverUrl, apiKey=***)"
    }

    data class Failure(
        val message: String,
        val isTlsError: Boolean = false,
        val isAuthError: Boolean = false,
        val isUntrustedCertificate: Boolean = false,
    ) : ImmichSignInResult()
}
