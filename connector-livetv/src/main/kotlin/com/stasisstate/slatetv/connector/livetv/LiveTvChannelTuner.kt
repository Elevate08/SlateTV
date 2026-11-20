package com.stasisstate.slatetv.connector.livetv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri

/** Live Channels on Google-certified Android TV / Google TV devices (e.g. NVIDIA Shield). */
internal const val GOOGLE_LIVE_CHANNELS_PACKAGE = "com.google.android.tv"

/** Live Channels on AOSP Android TV builds. */
internal const val AOSP_LIVE_CHANNELS_PACKAGE = "com.android.tv"

/** Explicit Live Channels packages tried, in order, after the generic `ACTION_VIEW` for a channel URI. */
internal val LIVE_CHANNELS_PACKAGES = listOf(GOOGLE_LIVE_CHANNELS_PACKAGE, AOSP_LIVE_CHANNELS_PACKAGE)

/** Longest `intent_uri` / `app_link_intent_uri` Slate TV will parse. */
internal const val MAX_APP_INTENT_URI_LENGTH = 4096

private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** URI permission grants that a third-party `intent_uri` may never carry out of Slate TV. */
internal const val URI_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

/** The only `android.*` actions an app-channel deep link may use; every other platform action is rejected. */
private val ALLOWED_PLATFORM_ACTIONS = setOf(Intent.ACTION_VIEW, Intent.ACTION_MAIN)

/** Extras that could carry nested intents or URI stream references across an app-channel launch boundary. */
internal val UNSAFE_INTENT_EXTRAS = setOf(
    Intent.EXTRA_STREAM,
    Intent.EXTRA_INTENT,
    Intent.EXTRA_ALTERNATE_INTENTS,
    Intent.EXTRA_INITIAL_INTENTS,
    Intent.EXTRA_REPLACEMENT_EXTRAS,
    Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER,
)

/** Platform-free description of an activity launch, so launch plans can be verified in JVM unit tests. */
internal data class IntentSpec(
    val action: String? = null,
    val data: String? = null,
    val type: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val extras: Map<String, Any> = emptyMap(),
)

/** One attempt in the System Live Channels tuning chain, in the order they are tried. */
internal sealed interface SystemTuneStep {
    data class View(val spec: IntentSpec) : SystemTuneStep

    /** The channel row's `app_link_intent_uri`, only ever confined to [ownerPackage] (the channel's input app). */
    data class AppLink(val rawIntentUri: String, val ownerPackage: String) : SystemTuneStep

    data class LaunchPackage(val packageName: String) : SystemTuneStep
}

/** The security-relevant parts of a parsed third-party intent (see [validateAppChannelIntent]). */
internal data class AppChannelIntentSnapshot(
    val action: String?,
    val dataScheme: String?,
    val dataAuthority: String?,
    val componentPackage: String?,
    val targetPackage: String?,
    val flags: Int,
    val hasSelector: Boolean,
)

internal sealed interface AppChannelIntentVerdict {
    data class Rejected(val reason: String) : AppChannelIntentVerdict

    /** [sanitized] is what the launched intent must look like: owner package, no selector, no URI grants, new task. */
    data class Accepted(val sanitized: AppChannelIntentSnapshot) : AppChannelIntentVerdict
}

/**
 * Pure security policy for deep links published by other apps (`PreviewProgram.intent_uri`,
 * `WatchNextProgram.intent_uri`, `Channel.app_link_intent_uri`):
 * - the owner must be a valid package that is not Slate TV itself;
 * - an explicit component / package must belong to the owner;
 * - only `ACTION_VIEW` / `ACTION_MAIN` among platform (`android.*`) actions, custom app actions allowed;
 * - no `file:` / `javascript:` data, and no `content:` URI served by Slate TV;
 * - the selector and all URI-permission grant flags are stripped, the package is pinned to the owner and
 *   `FLAG_ACTIVITY_NEW_TASK` is added.
 */
internal fun validateAppChannelIntent(
    snapshot: AppChannelIntentSnapshot,
    ownerPackage: String,
    hostPackage: String,
): AppChannelIntentVerdict {
    if (!LiveTvChannelTuner.isValidPackageName(ownerPackage)) return AppChannelIntentVerdict.Rejected("invalid owner package")
    if (ownerPackage == hostPackage) return AppChannelIntentVerdict.Rejected("owner is the host app")
    if (snapshot.componentPackage != null && snapshot.componentPackage != ownerPackage) {
        return AppChannelIntentVerdict.Rejected("component outside owner package")
    }
    if (snapshot.targetPackage != null && snapshot.targetPackage != ownerPackage) {
        return AppChannelIntentVerdict.Rejected("package outside owner package")
    }
    val action = snapshot.action
    if (action != null && action.startsWith("android.") && action !in ALLOWED_PLATFORM_ACTIONS) {
        return AppChannelIntentVerdict.Rejected("platform action not allowed")
    }
    when (snapshot.dataScheme?.lowercase()) {
        "file", "javascript", "jar" -> return AppChannelIntentVerdict.Rejected("data scheme not allowed")
        "content" -> {
            val authority = snapshot.dataAuthority.orEmpty().substringAfterLast('@')
            if (authority == hostPackage || authority.startsWith("$hostPackage.")) {
                return AppChannelIntentVerdict.Rejected("host content provider")
            }
        }
    }
    return AppChannelIntentVerdict.Accepted(
        snapshot.copy(
            targetPackage = ownerPackage,
            flags = (snapshot.flags and URI_GRANT_FLAGS.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK,
            hasSelector = false,
        ),
    )
}

/** System Live Channels tuning fallback chain for one channel (pure; see [LiveTvChannelTuner.tuneSystemChannel]). */
internal fun systemTuneSteps(
    channelUri: String,
    inputPackage: String?,
    appLinkIntentUri: String?,
    hostPackage: String,
): List<SystemTuneStep> = buildList {
    add(SystemTuneStep.View(IntentSpec(action = Intent.ACTION_VIEW, data = channelUri)))
    LIVE_CHANNELS_PACKAGES.forEach { pkg ->
        add(
            SystemTuneStep.View(
                IntentSpec(
                    action = Intent.ACTION_VIEW,
                    data = channelUri,
                    type = TvContract.Channels.CONTENT_ITEM_TYPE,
                    packageName = pkg,
                ),
            ),
        )
    }
    val owner = inputPackage?.takeIf { LiveTvChannelTuner.isValidPackageName(it) && it != hostPackage } ?: return@buildList
    appLinkIntentUri?.takeIf { it.isNotBlank() && it.length <= MAX_APP_INTENT_URI_LENGTH }?.let {
        add(SystemTuneStep.AppLink(it, owner))
    }
    add(SystemTuneStep.LaunchPackage(owner))
}

/** TV Provider program row re-read at click time (package + deep link), or `null` when the row is gone. */
internal data class ProgramLaunchRow(val packageName: String?, val intentUri: String?)

internal sealed interface ProgramLaunchPlan {
    data object Reject : ProgramLaunchPlan
    data class IntentUri(val rawIntentUri: String, val ownerPackage: String) : ProgramLaunchPlan
    data class OwnerLauncher(val ownerPackage: String) : ProgramLaunchPlan
}

/**
 * Decides how to open a preview / watch-next program. A row that now belongs to a different package than the card
 * expected cannot be launched. Require a verified owner even when falling back to the app launcher.
 */
internal fun planProgramLaunch(row: ProgramLaunchRow?, expectedPackage: String, hostPackage: String): ProgramLaunchPlan {
    if (!LiveTvChannelTuner.isValidPackageName(expectedPackage) || expectedPackage == hostPackage) return ProgramLaunchPlan.Reject
    if (row?.packageName != expectedPackage) return ProgramLaunchPlan.Reject
    val raw = row.intentUri?.takeIf { it.isNotBlank() && it.length <= MAX_APP_INTENT_URI_LENGTH }
    return if (raw != null) ProgramLaunchPlan.IntentUri(raw, expectedPackage) else ProgramLaunchPlan.OwnerLauncher(expectedPackage)
}

/** In-process launching of System Live Channels and installed apps' TV home-screen programs (App Channels). */
object LiveTvChannelTuner {

    /** True for a syntactically valid Android package name (at least two segments, ≤ 255 chars). */
    fun isValidPackageName(value: String?): Boolean =
        value != null && value.length <= 255 && PACKAGE_NAME_PATTERN.matches(value)

    /**
     * Tunes a System Live Channels channel. Tries, in order: `ACTION_VIEW` on the channel URI, Live Channels
     * (`com.google.android.tv`, then `com.android.tv`), the channel's validated `app_link_intent_uri`, and finally the
     * channel's input app. Returns true when an activity was started.
     */
    fun tuneSystemChannel(context: Context, channelId: Long): Boolean {
        if (channelId < 0) return false
        val uri: Uri = TvContract.buildChannelUri(channelId) ?: return false
        val (inputPackage, appLink) = queryChannelLaunchInfo(context, uri)
        val host = context.packageName
        for (step in systemTuneSteps(uri.toString(), inputPackage, appLink, host)) {
            val launched = when (step) {
                is SystemTuneStep.View -> start(context, step.spec.toIntent())
                is SystemTuneStep.AppLink ->
                    parseAppChannelIntent(step.rawIntentUri, step.ownerPackage, host)?.let { start(context, it) } == true
                is SystemTuneStep.LaunchPackage -> launcherIntent(context, step.packageName)?.let { start(context, it) } == true
            }
            if (launched) return true
        }
        return false
    }

    /** Opens a preview program published by [expectedPackage], re-verified against the system TV Provider. */
    fun launchPreviewProgram(context: Context, rowId: Long, expectedPackage: String): Boolean =
        launchProgram(context, rowId, expectedPackage) { TvContract.buildPreviewProgramUri(it) }

    /** Opens a Watch Next program published by [expectedPackage], re-verified against the system TV Provider. */
    fun launchWatchNextProgram(context: Context, rowId: Long, expectedPackage: String): Boolean =
        launchProgram(context, rowId, expectedPackage) { TvContract.buildWatchNextProgramUri(it) }

    private fun launchProgram(context: Context, rowId: Long, expectedPackage: String, uriFor: (Long) -> Uri?): Boolean {
        val host = context.packageName
        if (rowId < 0 || expectedPackage == host || !isValidPackageName(expectedPackage)) return false
        val row = uriFor(rowId)?.let { queryProgramLaunchRow(context, it) }
        return when (val plan = planProgramLaunch(row, expectedPackage, host)) {
            ProgramLaunchPlan.Reject -> false
            is ProgramLaunchPlan.IntentUri ->
                parseAppChannelIntent(plan.rawIntentUri, plan.ownerPackage, host)?.let { start(context, it) } == true ||
                    launcherIntent(context, plan.ownerPackage)?.let { start(context, it) } == true
            is ProgramLaunchPlan.OwnerLauncher -> launcherIntent(context, plan.ownerPackage)?.let { start(context, it) } == true
        }
    }

    // TvProvider forbids selection / sortOrder for non-system callers: every query passes null for both.
    private fun queryChannelLaunchInfo(context: Context, uri: Uri): Pair<String?, String?> = try {
        context.contentResolver.query(
            uri,
            arrayOf(TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_APP_LINK_INTENT_URI),
            null, null, null,
        )?.use {
            if (it.moveToFirst()) {
                it.string(TvContract.Channels.COLUMN_PACKAGE_NAME) to it.string(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI)
            } else {
                null
            }
        } ?: (null to null)
    } catch (_: Exception) {
        null to null // Includes SecurityException when READ_TV_LISTINGS was revoked.
    }

    private fun queryProgramLaunchRow(context: Context, uri: Uri): ProgramLaunchRow? = try {
        context.contentResolver.query(
            uri,
            arrayOf(TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_INTENT_URI),
            null, null, null,
        )?.use {
            if (it.moveToFirst()) {
                ProgramLaunchRow(
                    it.string(TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME),
                    it.string(TvContract.PreviewPrograms.COLUMN_INTENT_URI),
                )
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Parses and sanitizes a third-party deep link; `null` when it fails [validateAppChannelIntent]. */
    internal fun parseAppChannelIntent(raw: String, ownerPackage: String, hostPackage: String): Intent? {
        val intent = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        val snapshot = AppChannelIntentSnapshot(
            action = intent.action,
            dataScheme = intent.data?.scheme,
            dataAuthority = intent.data?.authority,
            componentPackage = intent.component?.packageName,
            targetPackage = intent.`package`,
            flags = intent.flags,
            hasSelector = intent.selector != null,
        )
        val verdict = validateAppChannelIntent(snapshot, ownerPackage, hostPackage) as? AppChannelIntentVerdict.Accepted
            ?: return null
        return intent.apply {
            selector = null
            clipData = null
            UNSAFE_INTENT_EXTRAS.forEach(::removeExtra)
            flags = verdict.sanitized.flags
            setPackage(ownerPackage)
        }
    }

    private fun launcherIntent(context: Context, packageName: String): Intent? {
        if (!isValidPackageName(packageName) || packageName == context.packageName) return null
        return runCatching {
            context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        val activity = context as? android.app.Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            false
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (_: RuntimeException) {
        false // ActivityNotFoundException, SecurityException, or a malformed third-party intent.
    }
}

internal fun IntentSpec.toIntent(): Intent = Intent().also { intent ->
    action?.let { intent.action = it }
    val uri = data?.let(Uri::parse)
    when {
        uri != null && type != null -> intent.setDataAndType(uri, type)
        uri != null -> intent.data = uri
        type != null -> intent.type = type
    }
    if (packageName != null && className != null) {
        intent.component = ComponentName(packageName, className)
    } else if (packageName != null) {
        intent.setPackage(packageName)
    }
    extras.forEach { (key, value) ->
        when (value) {
            is String -> intent.putExtra(key, value)
            is Boolean -> intent.putExtra(key, value)
            is Int -> intent.putExtra(key, value)
            is Long -> intent.putExtra(key, value)
        }
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
