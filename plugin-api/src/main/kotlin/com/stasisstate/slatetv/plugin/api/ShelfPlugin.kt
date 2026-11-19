package com.stasisstate.slatetv.plugin.api

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ShelfPluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val settingsActivityClassName: String? = null,
    val guideActivityClassName: String? = null,
) : Parcelable

@Parcelize
data class ShelfItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val channelNumber: String? = null,
    val badge: String? = null,
    val imageUri: String? = null,
    val imageHeaders: Bundle = Bundle(),
    val progressPercent: Int = -1,
    val startTimeEpochMs: Long = 0,
    val endTimeEpochMs: Long = 0,
    val launchPackageName: String? = null,
    val launchActivityClassName: String? = null,
    val launchUri: String? = null,
    val launchExtras: Bundle = Bundle(),
) : Parcelable

@Parcelize
data class ShelfPayload(
    val available: Boolean,
    val shelfTitle: String,
    val items: List<ShelfItem>,
    val emptyReason: String? = null,
    val guideActivityClassName: String? = null,
) : Parcelable

interface ShelfPlugin {
    val metadata: ShelfPluginMetadata
    suspend fun fetchShelf(): ShelfData?
}

data class ShelfPluginMetadata(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val packageName: String,
    val settingsActivityClassName: String? = null,
    val guideActivityClassName: String? = null,
)

data class ShelfData(
    val available: Boolean,
    val shelfTitle: String,
    val items: List<ShelfCard>,
    val emptyReason: String? = null,
    val guideActivityClassName: String? = null,
)

data class ShelfCard(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val channelNumber: String? = null,
    val badge: String? = null,
    val imageUri: String? = null,
    val imageHeaders: Map<String, String> = emptyMap(),
    val progressPercent: Int = -1,
    val startTimeEpochMs: Long = 0,
    val endTimeEpochMs: Long = 0,
    val launchPackageName: String? = null,
    val launchActivityClassName: String? = null,
    val launchUri: String? = null,
    val launchExtras: Map<String, Any> = emptyMap(),
)
