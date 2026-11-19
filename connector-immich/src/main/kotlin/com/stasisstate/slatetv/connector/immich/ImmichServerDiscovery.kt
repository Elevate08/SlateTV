package com.stasisstate.slatetv.connector.immich

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** A LAN Immich server found through mDNS / DNS-SD. [url] is ready to use as the server URL. */
data class DiscoveredImmichServer(val name: String, val host: String, val port: Int) {
    val url: String get() = "http://$host:$port"
}

/**
 * Best-effort LAN auto-discovery of Immich servers via [NsdManager].
 *
 * Browses `_immich._tcp.` (any service) and `_http._tcp.` (only services whose name contains "immich").
 * Everything is delivered on the main thread. Call [stop] when the UI goes away.
 */
class ImmichServerDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, DiscoveredImmichServer>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var resolving = false
    private var running = false
    private var onChanged: ((List<DiscoveredImmichServer>) -> Unit)? = null

    val servers: List<DiscoveredImmichServer> get() = found.values.toList()

    /** Starts browsing; [listener] is invoked on the main thread with the full list whenever it changes. */
    fun start(listener: (List<DiscoveredImmichServer>) -> Unit) {
        if (running) return
        val manager = nsd ?: return
        running = true
        onChanged = listener
        for (type in SERVICE_TYPES) {
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    main.post { listeners.remove(this) }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!isImmichService(info.serviceName, info.serviceType)) return
                    main.post {
                        if (!running) return@post
                        pending.addLast(info)
                        resolveNext()
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post {
                        if (found.remove(info.serviceName) != null) notifyChanged()
                    }
                }
            }
            val started = runCatching {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
            }.isSuccess
            if (started) listeners += discovery
        }
    }

    fun stop() {
        if (!running) return
        running = false
        onChanged = null
        pending.clear()
        resolving = false
        val manager = nsd
        listeners.forEach { runCatching { manager?.stopServiceDiscovery(it) } }
        listeners.clear()
        found.clear()
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
                val address = (host as? Inet4Address)?.hostAddress ?: host?.hostAddress?.substringBefore('%')
                if (address != null && info.port in 1..65535 && !address.contains(':')) {
                    found[info.serviceName] = DiscoveredImmichServer(info.serviceName, address, info.port)
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
        private val SERVICE_TYPES = listOf("_immich._tcp.", "_http._tcp.")

        /** `_immich._tcp.` services always qualify; `_http._tcp.` services only when named "immich". */
        internal fun isImmichService(serviceName: String?, serviceType: String?): Boolean {
            val type = serviceType.orEmpty().lowercase().trim('.')
            if (type.startsWith("_immich._tcp")) return true
            return type.startsWith("_http._tcp") && serviceName.orEmpty().contains("immich", ignoreCase = true)
        }
    }
}
