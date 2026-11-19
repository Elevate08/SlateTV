package com.stasisstate.slatetv.connector.extensions

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest

/** URL, scheme and text sanitisation rules shared by every part of the protocol. */
internal object ExtensionUrlPolicy {
    const val MAX_URL_LENGTH = 2048
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Parses an http(s) URL with no embedded credentials or fragment. */
    fun parseHttpUrl(value: String?): HttpUrl? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        if (trimmed.any { it.isISOControl() }) return null
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        return url
    }

    /** `https://` always; `http://` only for private LAN / loopback hosts. */
    fun validateManifestUrl(value: String?): HttpUrl? =
        parseHttpUrl(value)?.takeIf { transportAllowed(it) }

    fun transportAllowed(url: HttpUrl): Boolean = url.isHttps || isPrivateHost(url.host)

    fun originOf(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"

    fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = originOf(a) == originOf(b)

    /** Resolves [value] against [base]; the result must be a same-origin http(s) URL. */
    fun resolveEndpoint(base: HttpUrl, value: String?): HttpUrl? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_URL_LENGTH || raw.any { it.isISOControl() }) return null
        val resolved = base.resolve(raw) ?: return null
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty() || resolved.fragment != null) return null
        return resolved.takeIf { sameOrigin(base, it) }
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        val literal = parseIpLiteral(h) ?: return false
        return isPrivateAddress(literal)
    }

    private fun parseIpLiteral(host: String): InetAddress? {
        // Only literals reach InetAddress.getByName, so this never performs a DNS lookup.
        val isLiteral = ':' in host || IPV4.matches(host)
        if (!isLiteral) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    fun isPrivateAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
        is Inet6Address ->
            address.isLoopbackAddress || address.isLinkLocalAddress || (address.address[0].toInt() and 0xfe) == 0xfc
        else -> false
    }

    /** Image URLs: http(s) only, bounded length, no credentials. */
    fun imageUrl(value: String?): String? = parseHttpUrl(value)?.toString()?.takeIf { it.length <= MAX_URL_LENGTH }

    /** Shelf launch URIs: https, http or market only. Everything else (intent, file, content, ...) is dropped. */
    fun launchUri(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty() || v.length > MAX_URL_LENGTH) return null
        if (v.any { it <= ' ' || it.isISOControl() }) return null
        if (v.contains("#Intent;", ignoreCase = true)) return null
        return when (v.substringBefore(':', "").lowercase()) {
            "https", "http" -> parseHttpUrl(v)?.toString()
            "market" -> {
                val rest = v.substring("market:".length).removePrefix("//")
                v.takeIf { it.startsWith("market://", ignoreCase = true) && rest.substringBefore('/').substringBefore('?').isNotEmpty() }
            }
            else -> null
        }
    }

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun packageName(value: String?): String? =
        value?.trim()?.takeIf { it.length <= 255 && PACKAGE_NAME.matches(it) }

    /** Removes control and bidi-override characters, trims, and caps the length. */
    fun text(value: String?, cap: Int): String? {
        if (value == null) return null
        val cleaned = value.filterNot { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' }
            .trim()
        return cleaned.take(cap).trim().ifEmpty { null }
    }

    fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
}

/** Cleartext HTTP is limited to private networks: every address a hostname resolves to must be private. */
internal object LanOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || !addresses.all { ExtensionUrlPolicy.isPrivateAddress(it) }) {
            throw UnknownHostException("Cleartext HTTP is limited to local network hosts")
        }
        return addresses
    }
}

/** Per-extension API key / token header handling. */
internal object AuthHeader {
    private val NAME = Regex("^[A-Za-z0-9-]{1,64}$")
    private val DENIED = setOf(
        "host", "content-length", "content-type", "content-encoding", "transfer-encoding", "connection",
        "cookie", "set-cookie", "upgrade", "te", "trailer", "expect", "proxy-authorization", "accept",
        "user-agent", "location", "range",
    )

    fun validName(name: String?): Boolean =
        name != null && NAME.matches(name) && name.lowercase() !in DENIED

    fun validKey(key: String?): Boolean =
        key != null && key.length in 1..512 && key.all { it in '!'..'~' && it !in "\"\\," }

    /** Header to send for [key]: defaults to `Authorization: Bearer <key>` when no name is declared. */
    fun header(name: String?, key: String?): Pair<String, String>? {
        if (key.isNullOrEmpty() || !validKey(key)) return null
        val headerName = name?.takeIf { validName(it) } ?: "Authorization"
        val value = if (headerName.equals("Authorization", ignoreCase = true)) "Bearer $key" else key
        return headerName to value
    }
}
