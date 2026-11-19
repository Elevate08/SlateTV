package com.stasisstate.slatetv.shelf

import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HomeShelf(val plugin: ShelfPluginMetadata, val data: ShelfData)

internal class ShelfManager(
    private val scope: CoroutineScope,
    private val registry: ShelfPluginRegistry,
    private val disabledIds: () -> Set<String>,
) {
    private val mutableShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val shelves = mutableShelves.asStateFlow()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var foregroundJob: Job? = null

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
        val enabled = registry.refresh().filterNot { it.metadata.id in disabled }
        val enabledIds = enabled.map { it.metadata.id }.toSet()
        mutableShelves.value = shelves.value.filter { it.plugin.id in enabledIds }
        val fetched = enabled.map { plugin ->
            async {
                try {
                    plugin.fetchShelf()?.takeIf { it.available && it.items.isNotEmpty() }
                        ?.let { HomeShelf(plugin.metadata, it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
        val currentlyDisabled = disabledIds()
        mutableShelves.value = fetched.filterNot { it.plugin.id in currentlyDisabled }
    }
}
