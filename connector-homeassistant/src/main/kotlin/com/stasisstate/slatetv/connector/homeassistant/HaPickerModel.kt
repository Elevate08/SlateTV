package com.stasisstate.slatetv.connector.homeassistant

/** One selectable entity in a settings picker, already labelled for a 10-foot screen. */
data class HaPickerItem(
    val entityId: String,
    val title: String,
    val area: String? = null,
    /** Device name, only when it adds something the title doesn't already say. */
    val device: String? = null,
    val state: String = "",
    val stateActive: Boolean = false,
    val domain: String = entityId.substringBefore('.'),
    /** Hidden or config/diagnostic in HA; only listed when "show all" is on. */
    val secondary: Boolean = false,
    /** Last-resort qualifier when name, area and device all collide. */
    val disambiguator: String? = null,
    val missing: Boolean = false,
) {
    /** Secondary line: area and device (just the device under an area heading), else the entity's kind. */
    fun context(includeArea: Boolean = true): String {
        val parts = listOfNotNull(area.takeIf { includeArea }, device, disambiguator)
        return parts.joinToString("  ·  ").ifEmpty { HaPickerModel.domainLabel(domain) }
    }
}

data class HaPickerSection(
    val title: String,
    val items: List<HaPickerItem>,
    /** Area headings already name the area, so rows under them omit it. */
    val showsArea: Boolean = true,
)

/** Pure picker logic: which entities to offer, how to label them, how to rank and group them. */
object HaPickerModel {
    const val SECTION_SELECTED = "Selected"
    const val SECTION_CURRENT = "Current"
    const val SECTION_SUGGESTED = "Suggested"

    /** Score at or above which an entity is surfaced under "Suggested". */
    const val SUGGEST_THRESHOLD = 30

    /**
     * Builds labelled items for [entities] in [domains]. Ids in [keep] are always included (and
     * placeholders created for ones Home Assistant no longer reports) so saving never drops them.
     */
    fun items(
        entities: Collection<HaEntity>,
        registry: HaRegistry,
        domains: Set<String>,
        keep: Collection<String> = emptyList(),
    ): List<HaPickerItem> {
        val byId = entities.associateBy { it.entityId }
        val base = entities.filter { it.domain in domains || it.entityId in keep }.map { describe(it, registry) } +
            keep.filter { it !in byId }.distinct().map { missingItem(it, registry) }
        return disambiguate(base)
    }

    fun describe(entity: HaEntity, registry: HaRegistry): HaPickerItem {
        val title = entity.friendlyName
        val area = registry.area(entity.entityId)?.name
        val deviceName = registry.device(entity.entityId)?.name?.takeIf { it.isNotBlank() }
        val device = deviceName?.takeUnless { name ->
            title.contains(name, ignoreCase = true) || name.equals(area, ignoreCase = true)
        }
        return HaPickerItem(
            entityId = entity.entityId,
            title = title,
            area = area,
            device = device,
            state = stateLabel(entity),
            stateActive = entity.isOn,
            secondary = registry.isSecondary(entity.entityId),
        )
    }

    private fun missingItem(entityId: String, registry: HaRegistry) = HaPickerItem(
        entityId = entityId,
        title = registry.entry(entityId)?.name ?: entityId.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() },
        area = registry.area(entityId)?.name,
        state = "Missing",
        missing = true,
    )

    /**
     * Rows that still read identically after name + area + device get a qualifier: their kind when
     * that alone tells them apart (a "Lamp" light vs a "Lamp" switch), else their entity id. Primary
     * rows only compete with other primary rows, since hidden ones aren't shown by default.
     */
    fun disambiguate(items: List<HaPickerItem>): List<HaPickerItem> {
        fun key(item: HaPickerItem) = listOf(item.title.lowercase(), item.area?.lowercase(), item.device?.lowercase())
        val all = items.groupBy(::key)
        val primary = items.filterNot { it.secondary }.groupBy(::key)
        return items.map { item ->
            val rivals = (if (item.secondary) all else primary)[key(item)].orEmpty()
            when {
                rivals.size < 2 -> item
                rivals.count { it.domain == item.domain } == 1 -> item.copy(disambiguator = domainLabel(item.domain))
                else -> item.copy(disambiguator = item.entityId)
            }
        }
    }

    /**
     * Groups [items] for display: [pinned] ids first (in their order), then anything scored at least
     * [SUGGEST_THRESHOLD] by [score], then one section per area (A–Z), then area-less items by
     * domain. Each entity appears once. Secondary items are dropped unless [showSecondary] or pinned.
     */
    fun sections(
        items: List<HaPickerItem>,
        domains: Set<String>,
        pinned: List<String> = emptyList(),
        pinnedTitle: String = SECTION_SELECTED,
        score: (HaPickerItem) -> Int = { 0 },
        showSecondary: Boolean = false,
        query: String = "",
    ): List<HaPickerSection> {
        val pinnedSet = pinned.toSet()
        val visible = items.filter { (showSecondary || !it.secondary || it.entityId in pinnedSet) && matches(it, query) }
        val byId = visible.associateBy { it.entityId }
        val result = mutableListOf<HaPickerSection>()
        val pinnedItems = pinned.distinct().mapNotNull(byId::get)
        if (pinnedItems.isNotEmpty()) result += HaPickerSection(pinnedTitle, pinnedItems)

        val rest = visible.filter { it.entityId !in pinnedSet }
        val scores = rest.associate { it.entityId to score(it) }
        val suggested = rest.filter { scores.getValue(it.entityId) >= SUGGEST_THRESHOLD }
            .sortedWith(compareByDescending<HaPickerItem> { scores.getValue(it.entityId) }.thenBy { it.title.lowercase() })
        if (suggested.isNotEmpty()) result += HaPickerSection(SECTION_SUGGESTED, suggested)

        val suggestedIds = suggested.map { it.entityId }.toSet()
        val remaining = rest.filter { it.entityId !in suggestedIds }
        val domainOrder = domains.withIndex().associate { (i, d) -> d to i }
        val withinGroup = compareBy<HaPickerItem>({ domainOrder[it.domain] ?: Int.MAX_VALUE }, { it.title.lowercase() }, { it.entityId })
        remaining.filter { it.area != null }.groupBy { it.area!! }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .forEach { (area, group) -> result += HaPickerSection(area, group.sortedWith(withinGroup), showsArea = false) }
        val anyAreas = visible.any { it.area != null }
        remaining.filter { it.area == null }.groupBy { it.domain }
            .toList().sortedBy { (domain, _) -> domainOrder[domain] ?: Int.MAX_VALUE }
            .forEach { (domain, group) ->
                val title = if (!anyAreas || domain in AREALESS_DOMAINS) domainPlural(domain) else "${domainPlural(domain)} · no area"
                result += HaPickerSection(title, group.sortedWith(withinGroup))
            }
        return result
    }

    /** Every whitespace-separated word of [query] must appear in the name, area, device or id. */
    fun matches(item: HaPickerItem, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = listOfNotNull(item.title, item.area, item.device, item.entityId).joinToString(" ").lowercase()
        return words.all { it in haystack }
    }

    // ── Ranking ────────────────────────────────────────────────────────────────────

    private val DOORBELL_WORDS = setOf("doorbell", "visitor", "ding", "ring", "chime")
    private val PRESENCE_CLASSES = setOf("occupancy", "motion", "presence")

    /** How likely [entity] is the "someone rang" signal. 0 means no evidence. */
    fun doorbellScore(entity: HaEntity, registry: HaRegistry): Int {
        val entry = registry.entry(entity.entityId)
        val device = registry.device(entity.entityId)
        val deviceClass = entity.attributes["device_class"] as? String
        val words = words(entity.entityId.substringAfter('.'), entity.friendlyName, entry?.name, entry?.translationKey)
        val onDoorbellDevice = device != null && listOf(device.name, device.model).any { it.contains("doorbell", ignoreCase = true) }
        var score = 0
        if (deviceClass == "doorbell") score += 100
        if (entity.domain == "event") {
            val types = (entity.attributes["event_types"] as? List<*>).orEmpty().map { it.toString() }
            if (words(*types.toTypedArray()).any { it in DOORBELL_WORDS }) score += 80
        }
        if (words.any { it in DOORBELL_WORDS }) score += 60
        if (onDoorbellDevice) {
            score += 40
            if (deviceClass in PRESENCE_CLASSES || "person" in words) score += 10
        }
        if (entity.domain == "input_button") score += 30
        return score
    }

    /**
     * How well [camera] matches the doorbell [triggerId]: same device beats same area beats a
     * shared entity-id prefix (the only signal without registry access).
     */
    fun cameraScore(camera: HaEntity, triggerId: String?, registry: HaRegistry): Int {
        var score = 0
        val cameraDevice = registry.device(camera.entityId)
        if (cameraDevice != null && listOf(cameraDevice.name, cameraDevice.model).any { it.contains("doorbell", ignoreCase = true) }) {
            score += 20
        }
        if (triggerId == null) return score
        val triggerEntry = registry.entry(triggerId)
        if (triggerEntry?.deviceId != null && triggerEntry.deviceId == registry.entry(camera.entityId)?.deviceId) score += 100
        val triggerArea = registry.area(triggerId)
        if (triggerArea != null && triggerArea == registry.area(camera.entityId)) score += 50
        val a = triggerId.substringAfter('.').split('_')
        val b = camera.entityId.substringAfter('.').split('_')
        // Two shared leading words (front_door_*) are enough to surface it as a suggestion.
        score += 15 * a.zip(b).takeWhile { (x, y) -> x == y }.size.coerceAtMost(2)
        return score
    }

    private fun words(vararg texts: String?): Set<String> =
        texts.filterNotNull().flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }.filter { it.isNotEmpty() }.toSet()

    // ── Labels ─────────────────────────────────────────────────────────────────────

    private val AREALESS_DOMAINS = setOf("scene", "script", "automation", "input_boolean", "input_button")

    fun stateLabel(entity: HaEntity): String {
        if (entity.state == HaEntity.STATE_UNAVAILABLE) return "Unavailable"
        return when (entity.domain) {
            "scene", "script", "button", "input_button" -> domainLabel(entity.domain)
            "event" -> "Event"
            "light" -> entity.brightnessPercent?.let { "On · $it%" } ?: pretty(entity.state)
            else -> if (entity.state == HaEntity.STATE_UNKNOWN) "—" else pretty(entity.state)
        }
    }

    private fun pretty(state: String): String {
        val text = state.replace('_', ' ').replaceFirstChar { it.uppercase() }
        return if (text.length > 14) text.take(13) + "…" else text
    }

    fun domainLabel(domain: String): String = when (domain) {
        "input_boolean" -> "Toggle helper"
        "input_button" -> "Button helper"
        "binary_sensor" -> "Sensor"
        else -> domain.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun domainPlural(domain: String): String = when (domain) {
        "switch" -> "Switches"
        "input_boolean" -> "Toggle helpers"
        "input_button" -> "Button helpers"
        "binary_sensor" -> "Sensors"
        else -> domainLabel(domain) + "s"
    }
}
