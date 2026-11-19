package com.stasisstate.slatetv.shelf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.stasisstate.slatetv.connector.extensions.ExtensionsManagerActivity
import com.stasisstate.slatetv.connector.immich.ImmichSettingsActivity
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin
import com.stasisstate.slatetv.connector.livetv.LiveTvChannelTuner
import com.stasisstate.slatetv.connector.livetv.LiveTvGuideActivity
import com.stasisstate.slatetv.connector.livetv.LiveTvSettingsActivity
import com.stasisstate.slatetv.plugin.api.ShelfCard

/**
 * Only these built-in connector activities may be launched under Slate TV's own package name.
 * JSON extensions can never target arbitrary host activities.
 */
internal val ALLOWED_BUILT_IN_ACTIVITIES: Set<String> = setOf(
    ImmichSettingsActivity::class.java.name,
    LiveTvSettingsActivity::class.java.name,
    LiveTvGuideActivity::class.java.name,
    ExtensionsManagerActivity::class.java.name,
)

internal val ALLOWED_PUBLIC_LAUNCH_SCHEMES: Set<String> = setOf("https", "http", "market")

internal sealed interface ShelfLaunchDecision {
    data class TuneSystemChannel(val channelId: Long) : ShelfLaunchDecision
    data class BuiltInActivity(val className: String) : ShelfLaunchDecision
    data class PublicUri(val uri: String) : ShelfLaunchDecision
    data class ExternalPackage(val packageName: String) : ShelfLaunchDecision
    data object Blocked : ShelfLaunchDecision
}

/** Pure launch-policy decision helper so unit tests verify every built-in and JSON extension case. */
internal fun decideShelfCardLaunch(
    hostPackage: String,
    card: ShelfCard,
): ShelfLaunchDecision {
    extractTifChannelId(card.launchExtras)?.let { return ShelfLaunchDecision.TuneSystemChannel(it) }

    val activity = card.launchActivityClassName
    if (activity != null) {
        val targetPackage = card.launchPackageName ?: hostPackage
        val resolved = resolveActivityClassName(targetPackage, activity)
        return if (targetPackage == hostPackage && resolved in ALLOWED_BUILT_IN_ACTIVITIES) {
            ShelfLaunchDecision.BuiltInActivity(resolved)
        } else {
            ShelfLaunchDecision.Blocked
        }
    }

    val explicitPkg = card.launchPackageName
    if (explicitPkg != null) {
        return if (explicitPkg == hostPackage) {
            ShelfLaunchDecision.Blocked
        } else {
            ShelfLaunchDecision.ExternalPackage(explicitPkg)
        }
    }

    val uri = card.launchUri
    if (uri != null) {
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        return if (scheme in ALLOWED_PUBLIC_LAUNCH_SCHEMES) {
            ShelfLaunchDecision.PublicUri(uri)
        } else {
            ShelfLaunchDecision.Blocked
        }
    }
    return ShelfLaunchDecision.Blocked
}

internal fun isAllowedConnectorActivityLaunch(hostPackage: String, packageName: String, activity: String): Boolean {
    if (packageName != hostPackage) return false
    return resolveActivityClassName(packageName, activity) in ALLOWED_BUILT_IN_ACTIVITIES
}

internal fun launchPluginActivity(context: Context, packageName: String, activity: String) {
    if (!isAllowedConnectorActivityLaunch(context.packageName, packageName, activity)) {
        Toast.makeText(context, "Unable to open destination", Toast.LENGTH_SHORT).show()
        return
    }
    val launched = runCatching {
        val resolved = resolveActivityClassName(context.packageName, activity)
        context.startActivity(Intent().setComponent(ComponentName(context.packageName, resolved)))
        true
    }.getOrDefault(false)
    if (!launched) Toast.makeText(context, "Unable to open destination", Toast.LENGTH_SHORT).show()
}

internal fun launchShelfCard(context: Context, card: ShelfCard) {
    val launched = when (val decision = decideShelfCardLaunch(context.packageName, card)) {
        is ShelfLaunchDecision.TuneSystemChannel -> LiveTvChannelTuner.tuneSystemChannel(context, decision.channelId)

        is ShelfLaunchDecision.BuiltInActivity -> runCatching {
            val intent = Intent().setComponent(ComponentName(context.packageName, decision.className))
            applyTypedExtras(intent, card.launchExtras)
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        is ShelfLaunchDecision.PublicUri -> runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(decision.uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        is ShelfLaunchDecision.ExternalPackage -> runCatching {
            val intent = context.packageManager.getLeanbackLaunchIntentForPackage(decision.packageName)
                ?: context.packageManager.getLaunchIntentForPackage(decision.packageName)
                ?: return@runCatching false
            applyTypedExtras(intent, card.launchExtras)
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        ShelfLaunchDecision.Blocked -> false
    }
    if (!launched) Toast.makeText(context, "Unable to open destination", Toast.LENGTH_SHORT).show()
}

private fun extractTifChannelId(extras: Map<String, Any>): Long? {
    val raw = extras[BuiltInLiveTvShelfPlugin.EXTRA_TIF_CHANNEL_ID] ?: return null
    val id = when (raw) {
        is Long -> raw
        is Int -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }
    return id?.takeIf { it >= 0 }
}

private fun applyTypedExtras(intent: Intent, extras: Map<String, Any>) {
    extras.forEach { (key, value) ->
        when (value) {
            is String -> intent.putExtra(key, value)
            is Boolean -> intent.putExtra(key, value)
            is Int -> intent.putExtra(key, value)
            is Long -> intent.putExtra(key, value)
            is Double -> intent.putExtra(key, value)
            is Float -> intent.putExtra(key, value)
        }
    }
}

private fun resolveActivityClassName(packageName: String, activity: String): String =
    if (activity.startsWith(".")) packageName + activity else activity
