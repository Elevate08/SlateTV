package com.stasisstate.slatetv.connector.homeassistant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.URI

/**
 * A Home Assistant instance found via mDNS. [urls] are the candidate server addresses, best first:
 * the instance's own `internal_url`, the resolved LAN address, then `external_url`. All are already
 * normalized; TXT records are unauthenticated, so the UI shows the chosen URL before any sign-in.
 */
data class DiscoveredHaServer(
    val key: String,
    val name: String,
    val version: String,
    val urls: List<String>,
) {
    val primaryUrl: String get() = urls.first()
}

/**
 * Best-effort LAN discovery of Home Assistant via [NsdManager]. HA's zeroconf integration advertises
 * `_home-assistant._tcp.local.` with TXT properties `location_name`, `uuid`, `version`,
 * `internal_url`, `external_url` and `base_url` (homeassistant/components/zeroconf/__init__.py).
 * Callbacks arrive on the main thread; call [stop] when the screen goes away.
 */
class HaServerDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, DiscoveredHaServer>()
    private val serviceKeys = HashMap<String, String>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var running = false
    private var onChanged: ((List<DiscoveredHaServer>) -> Unit)? = null

    val servers: List<DiscoveredHaServer> get() = found.values.toList()

    fun start(onChanged: (List<DiscoveredHaServer>) -> Unit) {
        if (running) return
        val manager = nsd ?: return
        running = true
        this.onChanged = onChanged
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (listener === this) listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (!running) return@post
                    pending.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    val key = serviceKeys.remove(info.serviceName) ?: return@post
                    if (found.remove(key) != null) notifyChanged()
                }
            }
        }
        if (runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }.isSuccess) {
            listener = discovery
        }
    }

    fun stop() {
        if (!running) return
        running = false
        onChanged = null
        pending.clear()
        resolving = false
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        found.clear()
        serviceKeys.clear()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val manager = nsd ?: return
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        val started = runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = finishResolve(null)
                override fun onServiceResolved(info: NsdServiceInfo) = finishResolve(info)
            })
        }.isSuccess
        if (!started) finishResolve(null)
    }

    private fun finishResolve(info: NsdServiceInfo?) {
        main.post {
            resolving = false
            if (running && info != null) {
                @Suppress("DEPRECATION")
                val host = info.host
                val address = (host as? Inet4Address)?.hostAddress
                val server = parse(info.serviceName.orEmpty(), address, info.port, info.attributes.orEmpty())
                if (server != null) {
                    serviceKeys[info.serviceName.orEmpty()] = server.key
                    found[server.key] = server
                    notifyChanged()
                }
            }
            resolveNext()
        }
    }

    private fun notifyChanged() {
        onChanged?.invoke(servers)
    }

    companion object {
        const val SERVICE_TYPE = "_home-assistant._tcp."

        /** Builds a [DiscoveredHaServer] from a resolved service; null when there's no usable address. */
        internal fun parse(
            serviceName: String,
            ipv4Address: String?,
            port: Int,
            txt: Map<String, ByteArray?>,
        ): DiscoveredHaServer? {
            fun prop(key: String) = txt.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?.value?.toString(Charsets.UTF_8)?.trim().orEmpty()
            fun usable(url: String) = HaConfig.normalizeServerUrl(url).takeIf {
                url.contains("://") && it.isNotBlank()
            }
            val resolved = ipv4Address?.takeIf { port in 1..65535 && !it.contains(':') }?.let { "http://$it:$port" }
            val urls = listOfNotNull(
                usable(prop("internal_url")),
                resolved,
                usable(prop("base_url")),
                usable(prop("external_url")),
            ).distinct()
            if (urls.isEmpty()) return null
            val name = prop("location_name").ifBlank { serviceName.ifBlank { "Home Assistant" } }
            val key = prop("uuid").ifBlank { serviceName.ifBlank { urls.first() } }
            return DiscoveredHaServer(key = key, name = name, version = prop("version"), urls = urls)
        }
    }
}

/** Whether credentials sent to a server URL would cross the internet unencrypted. */
object HaUrlSafety {
    /** True for plain `http://` to a host that isn't obviously on the local network. */
    fun isInsecureRemote(serverUrl: String): Boolean {
        val normalized = HaConfig.normalizeServerUrl(serverUrl)
        if (normalized.isBlank()) return false
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        if (uri.scheme != "http") return false
        return !isLocalHost(uri.host.orEmpty().trim('[', ']'))
    }

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || !host.contains('.') && !host.contains(':')) return true
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        ipv4(host)?.let { (a, b) ->
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127) // CGNAT/Tailscale
        }
        if (host.contains(':')) {
            return host == "::1" || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") ||
                host.startsWith("feb") || host.startsWith("fc") || host.startsWith("fd")
        }
        return false
    }

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return numbers[0] to numbers[1]
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain", ".home")
}
