package com.stasisstate.slatetv.shelf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.stasisstate.slatetv.plugin.api.ShelfCard

internal fun launchPluginActivity(context: Context, packageName: String, activity: String) {
    launchSafely(context) { Intent().setComponent(pluginComponent(packageName, activity)) }
}

internal fun launchShelfCard(context: Context, owner: String, card: ShelfCard) {
    launchSafely(context) {
        val packageName = card.launchPackageName ?: owner
        val intent = when {
            card.launchUri != null -> Intent(Intent.ACTION_VIEW, Uri.parse(card.launchUri)).setPackage(packageName)
            card.launchActivityClassName != null -> Intent(Intent.ACTION_MAIN)
            card.launchPackageName != null -> context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: context.packageManager.getLaunchIntentForPackage(packageName)
            else -> null
        } ?: return@launchSafely null
        card.launchActivityClassName?.let { intent.component = pluginComponent(packageName, it) }
        card.launchExtras.forEach { (key, value) ->
            when (value) {
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
            }
        }
        intent
    }
}

private fun pluginComponent(packageName: String, activity: String): ComponentName =
    ComponentName(packageName, if (activity.startsWith(".")) packageName + activity else activity)

private inline fun launchSafely(context: Context, buildIntent: () -> Intent?) {
    val launched = runCatching {
        val intent = buildIntent() ?: return@runCatching false
        // Do not let a remote provider invoke an activity under the launcher's UID.
        if ((intent.component?.packageName ?: intent.`package`) == context.packageName) return@runCatching false
        context.startActivity(intent)
        true
    }.getOrDefault(false)
    if (!launched) Toast.makeText(context, "Unable to open plugin destination", Toast.LENGTH_SHORT).show()
}
