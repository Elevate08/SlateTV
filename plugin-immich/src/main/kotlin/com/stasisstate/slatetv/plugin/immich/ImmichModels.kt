package com.stasisstate.slatetv.plugin.immich

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
    val allowUntrustedTls: Boolean = false,
) {
    val normalizedServerUrl: String
        get() = normalizeUrl(serverUrl)

    val isConfigured: Boolean
        get() = normalizedServerUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }
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
    ) : ImmichConnectionResult()
}
