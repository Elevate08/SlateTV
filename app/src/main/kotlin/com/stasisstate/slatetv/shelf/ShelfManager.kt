package com.stasisstate.slatetv.shelf

import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HomeShelf(val plugin: ShelfPluginMetadata, val data: ShelfData)

/**
 * Coordinates foreground Home Shelf refreshes off the main thread.
 *
 * Shield wake resilience:
 * - Runs PackageManager discovery on [Dispatchers.IO] (never blocks UI thread).
 * - Preserves the last good shelf across up to [MAX_TRANSIENT_FAILURES] consecutive null/timeout
 *   fetches so a brief DHCP hiccup after Shield wake doesn't make the Live TV shelf vanish.
 * - Immediately hides a shelf if the plugin explicitly reports `available == false` or is disabled.
 */
internal class ShelfManager(
    private val scope: CoroutineScope,
    private val registry: ShelfPluginRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val disabledIds: () -> Set<String>,
) {
    private val mutableShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val shelves = mutableShelves.asStateFlow()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var foregroundJob: Job? = null
    private val transientFailures = mutableMapOf<String, Int>()

    fun start() {
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            while (isActive) {
                refreshOnce()
                withTimeoutOrNull(60_000) { requests.receive() }
            }
        }
    }

    fun stop() {
        foregroundJob?.cancel()
        foregroundJob = null
        requests.tryReceive()
    }

    fun refresh() {
        val disabled = disabledIds()
        mutableShelves.value = shelves.value.filterNot { it.plugin.id in disabled }
        if (foregroundJob?.isActive == true) requests.trySend(Unit)
    }

    internal suspend fun refreshOnce() = coroutineScope {
        val disabled = disabledIds()
        val enabled = withContext(ioDispatcher) { registry.refresh() }
            .filterNot { it.metadata.id in disabled }
        val enabledIds = enabled.map { it.metadata.id }.toSet()
        transientFailures.keys.retainAll(enabledIds)
        val previousById = shelves.value.filter { it.plugin.id in enabledIds }.associateBy { it.plugin.id }
        mutableShelves.value = previousById.values.toList()

        val rawResults = enabled.map { plugin ->
            async {
                val payload = try {
                    plugin.fetchShelf()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                plugin.metadata to payload
            }
        }.awaitAll()

        val fetched = rawResults.mapNotNull { (metadata, payload) ->
            val id = metadata.id
            when {
                payload == null -> {
                    // Transient RPC/bind timeout — keep last good shelf briefly so wake doesn't flicker
                    val count = (transientFailures[id] ?: 0) + 1
                    transientFailures[id] = count
                    if (count <= MAX_TRANSIENT_FAILURES) previousById[id] else null
                }
                payload.available && payload.items.isNotEmpty() -> {
                    transientFailures.remove(id)
                    HomeShelf(metadata, payload)
                }
                else -> {
                    // Plugin explicitly reported unavailable or empty — hide immediately
                    transientFailures.remove(id)
                    null
                }
            }
        }

        val currentlyDisabled = disabledIds()
        mutableShelves.value = fetched.filterNot { it.plugin.id in currentlyDisabled }
    }

    private companion object {
        const val MAX_TRANSIENT_FAILURES = 2
    }
}
