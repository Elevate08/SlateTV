package com.stasisstate.slatetv.connector.extensions

import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private fun parseJson(body: String): Any = try {
    JSONTokener(body.trim()).nextValue()
} catch (e: Exception) {
    throw ExtensionException("The server did not return valid JSON")
}

/** `{"items":[...]}` or a bare array of items / URL strings. */
internal object BackgroundFeedParser {
    const val MAX_ITEMS = 200

    fun parse(body: String): List<FeedItem> {
        val array = when (val root = parseJson(body)) {
            is JSONArray -> root
            is JSONObject -> root.opt("items") as? JSONArray ?: throw ExtensionException("Feed has no items array")
            else -> throw ExtensionException("Unexpected feed format")
        }
        val seen = HashSet<String>()
        val out = ArrayList<FeedItem>()
        for (i in 0 until minOf(array.length(), MAX_ITEMS)) {
            val item = parseItem(array.opt(i), i) ?: continue
            if (seen.add(item.id)) out += item
        }
        return out
    }

    fun parseItem(raw: Any?, index: Int): FeedItem? {
        val obj = when (raw) {
            is JSONObject -> raw
            is String -> JSONObject().put("imageUrl", raw)
            else -> return null
        }
        val imageUrl = ExtensionUrlPolicy.imageUrl((obj.opt("imageUrl") as? String) ?: (obj.opt("url") as? String))
            ?: return null
        val id = ExtensionUrlPolicy.text(obj.opt("id")?.takeIf { it is String || it is Number }?.toString(), 128)
            ?: ExtensionUrlPolicy.shortHash(imageUrl)
        fun dim(key: String) = ((obj.opt(key) as? Number)?.toInt() ?: 0).coerceIn(0, 20_000)
        return FeedItem(
            id = id,
            imageUrl = imageUrl,
            title = ExtensionUrlPolicy.text(obj.opt("title") as? String, 120),
            subtitle = ExtensionUrlPolicy.text(obj.opt("subtitle") as? String, 160),
            width = dim("width"),
            height = dim("height"),
        )
    }
}

internal object ShelfFeedParser {
    const val MAX_CARDS = 24

    fun parse(body: String, fallbackTitle: String): ShelfData {
        val root = parseJson(body) as? JSONObject ?: throw ExtensionException("Unexpected shelf format")
        val array = root.opt("items") as? JSONArray ?: JSONArray()
        val cards = ArrayList<ShelfCard>()
        val seen = HashSet<String>()
        for (i in 0 until minOf(array.length(), MAX_CARDS)) {
            val card = parseCard(array.opt(i)) ?: continue
            if (seen.add(card.id)) cards += card
        }
        val available = (root.opt("available") as? Boolean) ?: cards.isNotEmpty()
        return ShelfData(
            available = available,
            shelfTitle = ExtensionUrlPolicy.text(root.opt("shelfTitle") as? String, 120) ?: fallbackTitle,
            items = if (available) cards else emptyList(),
            emptyReason = ExtensionUrlPolicy.text(root.opt("emptyReason") as? String, 200),
            guideActivityClassName = null,
        )
    }

    private fun parseCard(raw: Any?): ShelfCard? {
        val obj = raw as? JSONObject ?: return null
        val title = ExtensionUrlPolicy.text(obj.opt("title") as? String, 120) ?: return null
        val id = ExtensionUrlPolicy.text(obj.opt("id")?.takeIf { it is String || it is Number }?.toString(), 128)
            ?: ExtensionUrlPolicy.shortHash(title + (obj.opt("launchUri") ?: ""))
        val progress = (obj.opt("progressPercent") as? Number)?.toInt()?.takeIf { it in 0..100 } ?: -1
        // launchActivityClassName is deliberately never read: cards can only name a package or a safe URI.
        return ShelfCard(
            id = id,
            title = title,
            subtitle = ExtensionUrlPolicy.text(obj.opt("subtitle") as? String, 160),
            channelNumber = ExtensionUrlPolicy.text(obj.opt("channelNumber") as? String
                ?: (obj.opt("channelNumber") as? Number)?.toString(), 16),
            badge = ExtensionUrlPolicy.text(obj.opt("badge") as? String, 24),
            imageUri = ExtensionUrlPolicy.imageUrl(obj.opt("imageUrl") as? String),
            progressPercent = progress,
            launchPackageName = ExtensionUrlPolicy.packageName(obj.opt("launchPackageName") as? String),
            launchActivityClassName = null,
            launchUri = ExtensionUrlPolicy.launchUri(obj.opt("launchUri") as? String),
        )
    }
}
