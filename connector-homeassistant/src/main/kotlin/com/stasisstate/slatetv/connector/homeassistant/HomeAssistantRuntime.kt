package com.stasisstate.slatetv.connector.homeassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Process-wide Home Assistant integration. Owns the config, a single live WebSocket subscription
 * for the entities the launcher cares about (home controls, doorbells, cameras), and doorbell ring
 * detection. Lives at application scope so rings arrive while another app is in front.
 */
object HomeAssistantRuntime {
    private const val TAG = "SlateHA"

    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private lateinit var store: HaConfigStore

    private val _config = MutableStateFlow(HaConfig())
    val config: StateFlow<HaConfig> = _config.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _status = MutableStateFlow<HaStatus>(HaStatus.NotConfigured)
    val status: StateFlow<HaStatus> = _status.asStateFlow()

    private val _rings = MutableSharedFlow<DoorbellRing>(extraBufferCapacity = 4)
    val rings: SharedFlow<DoorbellRing> = _rings.asSharedFlow()

    private val entityStore = HaEntityStore()
    private var detector = DoorbellDetector()
    private var connectionJob: Job? = null

    val isInitialized: Boolean get() = ::appContext.isInitialized

    fun init(context: Context, scope: CoroutineScope) {
        if (isInitialized) return
        appContext = context.applicationContext
        this.scope = scope
        store = HaConfigStore(appContext)
        _config.value = store.load()
        restartConnection()
        syncListener()
    }

    /** True when doorbell alerts must keep working while another app is on screen. */
    fun shouldListenInBackground(config: HaConfig = _config.value): Boolean =
        config.isConfigured && config.alertsEnabled && config.doorbells.isNotEmpty()

    /** Starts/stops the background listener to match the config; call when Slate comes to the front. */
    fun syncListener() {
        if (isInitialized) DoorbellListenerService.sync(appContext, shouldListenInBackground())
    }

    fun api(config: HaConfig = _config.value): HaApiClient = HaApiClient(config.normalizedServerUrl, config.token)

    fun updateConfig(next: HaConfig) {
        val previous = _config.value
        store.save(next)
        _config.value = store.load()
        val connectionChanged = previous.normalizedServerUrl != next.normalizedServerUrl ||
            previous.token != next.token || watchedEntityIds(previous) != watchedEntityIds(next)
        if (connectionChanged || _status.value is HaStatus.AuthFailed) restartConnection()
        if (shouldListenInBackground(previous) != shouldListenInBackground()) syncListener()
    }

    /** Reconnects now (e.g. after network recovery) if the connection is down. */
    fun reconnectIfIdle() {
        if (_status.value is HaStatus.Disconnected) restartConnection()
    }

    private fun watchedEntityIds(config: HaConfig): List<String> =
        (config.homeEntityIds + config.doorbells.flatMap { listOfNotNull(it.triggerEntityId, it.cameraEntityId) })
            .distinct().sorted()

    private fun restartConnection() {
        connectionJob?.cancel()
        connectionJob = null
        entityStore.clear()
        _entities.value = emptyMap()
        val config = _config.value
        val watched = watchedEntityIds(config)
        if (!config.isConfigured) {
            _status.value = HaStatus.NotConfigured
            return
        }
        if (watched.isEmpty()) {
            _status.value = HaStatus.Connected
            return
        }
        detector = DoorbellDetector()
        val connection = HaConnection(
            api = api(config),
            token = config.token,
            entityIds = watched,
            onStatus = { _status.value = it },
            onSnapshot = { event ->
                // A fresh session: rebuild state from scratch and never ring on the snapshot.
                entityStore.clear()
                entityStore.apply(event)
                _entities.value = entityStore.snapshot
            },
            onEvent = ::handleEvent,
        )
        connectionJob = scope.launch {
            runCatching { connection.run() }.onFailure { Log.w(TAG, "Connection loop ended: ${it.javaClass.simpleName}") }
        }
    }

    private fun handleEvent(event: JSONObject) {
        val changes = entityStore.apply(event)
        _entities.value = entityStore.snapshot
        val config = _config.value
        val now = System.currentTimeMillis()
        for (change in changes) {
            val entityId = change.new?.entityId ?: continue
            val binding = config.doorbells.firstOrNull { it.triggerEntityId == entityId } ?: continue
            if (detector.isRing(change, now)) emitRing(binding, now)
        }
    }

    private fun emitRing(binding: DoorbellBinding, now: Long) {
        val ring = DoorbellRing(
            binding = binding,
            title = ringTitle(binding),
            cameraEntityId = binding.cameraEntityId,
            atMillis = now,
        )
        _rings.tryEmit(ring)
        if (_config.value.alertsEnabled) DoorbellAlertPresenter.show(appContext, ring)
    }

    fun ringTitle(binding: DoorbellBinding): String {
        val entities = _entities.value
        val name = entities[binding.triggerEntityId]?.friendlyName
            ?: binding.cameraEntityId?.let { entities[it]?.friendlyName }
            ?: binding.triggerEntityId.substringAfter('.').replace('_', ' ')
        return name
    }

    /** Shows the alert for [binding] exactly as a real ring would (used by "Test doorbell"). */
    fun simulateRing(binding: DoorbellBinding) {
        val ring = DoorbellRing(binding, ringTitle(binding), binding.cameraEntityId, System.currentTimeMillis())
        DoorbellAlertPresenter.show(appContext, ring)
    }

    suspend fun pressTile(entity: HaEntity): Result<Unit> = runCatching {
        api().callService(HaDomains.tileAction(entity))
    }

    suspend fun setBrightness(entityId: String, percent: Int): Result<Unit> = runCatching {
        api().callService(HaDomains.brightnessAction(entityId, percent))
    }

    /**
     * Runs the reply's bound action (if any) and fires `slatetv_quick_reply` for automations.
     * The event needs an admin token, so it only fails the reply when there is no bound action.
     */
    suspend fun sendReply(ring: DoorbellRing, reply: QuickReply): Result<Unit> = runCatching {
        val api = api()
        val variables = mapOf(
            "message" to reply.label,
            "doorbell" to ring.binding.triggerEntityId,
            "camera" to ring.cameraEntityId,
            "source" to "slatetv",
        )
        val action = reply.actionEntityId
        if (action != null) api.callService(HaDomains.replyAction(action, variables))
        val event = runCatching { api.fireEvent(QUICK_REPLY_EVENT, variables) }
        if (action == null) event.getOrThrow()
    }

    const val QUICK_REPLY_EVENT = "slatetv_quick_reply"
}
