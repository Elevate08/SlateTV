package com.stasisstate.slatetv.connector.livetv

import java.net.InetAddress
import java.time.Instant

data class Program(
    val id: String,
    val name: String,
    val episodeTitle: String?,
    val overview: String?,
    val start: Instant?,
    val end: Instant?,
    val isLive: Boolean,
) {
    val title: String get() = listOfNotNull(name, episodeTitle).joinToString(" · ")
    private fun validTimes() = start != null && end != null && end > start

    fun progressPercent(now: Instant): Int {
        if (!validTimes()) return -1
        val beginning = start!!.toEpochMilli()
        val duration = end!!.toEpochMilli() - beginning
        if (duration <= 0) return -1
        return (((now.toEpochMilli() - beginning).toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }

    fun remainingTime(now: Instant): String? {
        if (!validTimes() || now < start!! || now > end!!) return null
        val seconds = end.epochSecond - now.epochSecond
        val minutes = (seconds + 59) / 60
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
    }
}

data class Channel(
    val id: String,
    val name: String,
    val number: String?,
    val imageUri: String?,
    val current: Program?,
)

/** Local network and Tailscale addresses. */
fun InetAddress.isLanOrTailscale(): Boolean {
    if (isSiteLocalAddress || isLoopbackAddress || isLinkLocalAddress) return true
    val bytes = address
    // Tailscale / CGNAT private mesh range: 100.64.0.0/10
    if (bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64) {
        return true
    }
    // IPv6 Unique Local Address (ULA): fc00::/7
    if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) {
        return true
    }
    return false
}

/** True only for public unicast internet addresses (never loopback, link-local, RFC1918, ULA, CGNAT/Tailscale, or multicast). */
fun InetAddress.isPublicInternetAddress(): Boolean =
    !isLanOrTailscale() && !isAnyLocalAddress && !isMulticastAddress
