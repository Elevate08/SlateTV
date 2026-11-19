package com.stasisstate.slatetv.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fast, lightweight PackageManager app loader for Android TV.
 * Loads leanback banners when present, falling back to crisp app icons.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val selfPackage: String = context.packageName

    suspend fun loadInstalledApps(includeSideloaded: Boolean): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val resultByPackage = LinkedHashMap<String, AppInfo>()

            val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val leanbackActivities = queryIntentActivitiesCompat(leanbackIntent)
            for (ri in leanbackActivities) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == selfPackage || resultByPackage.containsKey(pkg)) continue
                buildAppInfo(ri, isLeanback = true)?.let { resultByPackage[pkg] = it }
            }

            if (includeSideloaded) {
                val standardIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val standardActivities = queryIntentActivitiesCompat(standardIntent)
                for (ri in standardActivities) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (pkg == selfPackage || resultByPackage.containsKey(pkg)) continue
                    buildAppInfo(ri, isLeanback = false)?.let { resultByPackage[pkg] = it }
                }
            }

            resultByPackage.values.sortedBy { it.label.lowercase() }
        }

    private fun queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> {
        return runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
    }

    private fun buildAppInfo(ri: ResolveInfo, isLeanback: Boolean): AppInfo? {
        val activityInfo = ri.activityInfo ?: return null
        val pkg = activityInfo.packageName
        val launchIntent = if (isLeanback) {
            pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        } else {
            pm.getLaunchIntentForPackage(pkg)
        } ?: return null

        val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: pkg

        val banner = runCatching {
            activityInfo.loadBanner(pm) ?: pm.getApplicationBanner(pkg)
        }.getOrNull()

        val icon = runCatching {
            ri.loadIcon(pm)
        }.getOrNull()

        return AppInfo(
            packageName = pkg,
            label = label,
            launchIntent = launchIntent,
            banner = banner,
            icon = icon,
            isLeanback = isLeanback,
        )
    }

    fun isSlateDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        return resolved?.activityInfo?.packageName == selfPackage
    }
}
