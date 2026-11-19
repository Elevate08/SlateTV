package com.stasisstate.slatetv.connector.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredServer(val id: String, val name: String, val address: String)

internal const val DISCOVERY_PORT = 7359
internal const val DISCOVERY_MESSAGE = "who is JellyfinServer?"
private const val MAX_DISCOVERY_PACKET = 2048
private const val MAX_DISCOVERED = 16

/**
 * Parses one Jellyfin discovery reply (`{"Address":"http://192.168.1.5:8096","Id":"…","Name":"…"}`).
 * Returns `null` for malformed JSON or an address that is not a plain http(s) server URL.
 */
internal fun parseDiscoveryResponse(payload: String): DiscoveredServer? {
    if (payload.length > MAX_DISCOVERY_PACKET) return null
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    fun text(key: String) = if (json.isNull(key)) null else json.optString(key).trim().ifEmpty { null }
    val address = parseServerUrl(text("Address") ?: return null)?.toString() ?: return null
    val id = text("Id") ?: address
    val name = (text("Name") ?: "Jellyfin").take(80)
    return DiscoveredServer(id.take(64), name, address)
}

/** Jellyfin's built-in UDP auto-discovery: broadcast on port 7359 and collect replies for a bounded window. */
object JellyfinServerDiscovery {
    suspend fun discover(timeoutMs: Long = 1_500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val message = DISCOVERY_MESSAGE.toByteArray()
                socket.send(DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val found = linkedMapOf<String, DiscoveredServer>()
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                val buffer = ByteArray(MAX_DISCOVERY_PACKET)
                while (found.size < MAX_DISCOVERED) {
                    val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                    if (remainingMs <= 0) break
                    socket.soTimeout = remainingMs.toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    parseDiscoveryResponse(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        ?.let { found.putIfAbsent(it.id, it) }
                }
                found.values.toList()
            }
        }.getOrDefault(emptyList())
    }
}
