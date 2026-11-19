package com.stasisstate.slatetv.settings

import android.app.Dialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.stasisstate.slatetv.R

/**
 * Helpers for making Slate TV the Default Home launcher on Android TV, including a
 * 10-foot help dialog for devices that hide or silently cancel the Home role request.
 */
object DefaultLauncherHelper {

    /** Package reported by the resolver stub when no default Home app is chosen. */
    private const val RESOLVER_STUB_PACKAGE = "android"

    // ---- Pure, unit-testable helpers -------------------------------------------------------

    /** Settings actions to try, in order, when the role request is unavailable or cancelled. */
    internal fun homeSettingsFallbackActions(): List<String> = listOf(
        Settings.ACTION_HOME_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
    )

    fun buildHomeSettingsFallbackIntents(): List<Intent> =
        homeSettingsFallbackActions().map { Intent(it) }

    /** Returns the package if it names a real launcher, or null for empty / the "android" stub. */
    internal fun normalizeHomePackage(pkg: String?): String? =
        pkg?.takeIf { it.isNotBlank() && it != RESOLVER_STUB_PACKAGE }

    /**
     * Resolution of CATEGORY_HOME is what actually decides where the Home button goes, so it is
     * authoritative. A held role only counts when resolution gives no concrete answer.
     */
    internal fun computeIsDefaultHome(resolvedHomePackage: String?, selfPackage: String, roleHeld: Boolean): Boolean {
        val resolved = normalizeHomePackage(resolvedHomePackage)
        return if (resolved != null) resolved == selfPackage else roleHeld
    }

    internal fun adbSetHomeCommand(selfPackage: String): String =
        "adb shell cmd package set-home-activity $selfPackage/.ui.HomeActivity"

    internal fun adbDisableLauncherCommand(currentHomePackage: String): String =
        "adb shell pm disable-user --user 0 $currentHomePackage"

    internal fun adbTipText(selfPackage: String, currentHomePackage: String?): String = buildString {
        append("If your TV hides the Home picker or cancels the request, connect over ADB and run:\n\n")
        append(adbSetHomeCommand(selfPackage))
        append("\n\nIf the current launcher still takes over, disable it for your user:\n\n")
        append(adbDisableLauncherCommand(currentHomePackage ?: "<current_launcher_package>"))
        append("\n\nRe-enable it any time with: adb shell pm enable <package>")
    }

    internal fun statusText(isDefault: Boolean, currentHomePackage: String?): String = when {
        isDefault -> "Slate TV is your Default Home launcher."
        currentHomePackage != null -> "Current Home app: $currentHomePackage"
        else -> "No default Home app is set."
    }

    // ---- Android-backed helpers ------------------------------------------------------------

    private fun roleManager(context: Context): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
        } else {
            null
        }

    private fun isRoleHeld(context: Context): Boolean = runCatching {
        roleManager(context)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    }.getOrDefault(false)

    private fun resolveHomeActivityPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
    }

    fun isDefaultHome(context: Context): Boolean =
        computeIsDefaultHome(resolveHomeActivityPackage(context), context.packageName, isRoleHeld(context))

    /** The package currently handling Home (excluding the unset "android" chooser stub). */
    fun resolveCurrentHomePackage(context: Context): String? =
        normalizeHomePackage(resolveHomeActivityPackage(context))

    fun buildRequestHomeRoleIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val rm = roleManager(context) ?: return null
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun launchFirstResolvable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
            if (!resolvable) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    fun openHomeSettingsFallback(context: Context): Boolean =
        launchFirstResolvable(context, buildHomeSettingsFallbackIntents())

    fun openCurrentLauncherAppInfo(context: Context): Boolean {
        val pkg = resolveCurrentHomePackage(context)?.takeIf { it != context.packageName } ?: return false
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        return launchFirstResolvable(context, listOf(intent))
    }

    // ---- Dialog -----------------------------------------------------------------------------

    fun showDefaultLauncherDialog(
        activity: AppCompatActivity,
        onRequestRole: (Intent) -> Unit,
        onStatusChanged: () -> Unit,
    ) {
        val isDefault = isDefaultHome(activity)
        val currentPkg = resolveCurrentHomePackage(activity)?.takeIf { it != activity.packageName }
        val dp = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()

        val dlg = Dialog(activity, R.style.Theme_SlateTV_Dialog)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dialog_surface)
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        fun text(value: String, size: Float, color: String, bottom: Int = 0) = TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            setPadding(0, 0, 0, dp(bottom))
        }

        var firstButton: View? = null
        fun addButton(title: String, onClick: () -> Unit): TextView {
            val btn = TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_row)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener { onClick() }
            }
            if (firstButton == null) firstButton = btn
            root.addView(btn)
            return btn
        }

        root.addView(text("Default Home Launcher", 20f, "#F8FAFC", 6))
        root.addView(text(statusText(isDefault, currentPkg), 14f, "#94A3B8", 14))

        if (!isDefault) {
            addButton("Set Slate TV as Default Home") {
                dlg.dismiss()
                val roleIntent = buildRequestHomeRoleIntent(activity)
                if (roleIntent != null) onRequestRole(roleIntent) else openHomeSettingsFallback(activity)
            }
        }
        addButton("Open Default Home App Settings") {
            dlg.dismiss()
            openHomeSettingsFallback(activity)
        }
        if (currentPkg != null) {
            addButton("Open Current Launcher App Info") {
                dlg.dismiss()
                openCurrentLauncherAppInfo(activity)
            }
            root.addView(text("Tip: in App Info, choose \"Clear defaults\" so Slate TV can be picked next time Home is pressed.", 12f, "#64748B", 8))
        }

        val adbText = text(adbTipText(activity.packageName, currentPkg), 12f, "#94A3B8", 8).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            setTextIsSelectable(false)
        }
        val adbToggle = addButton("Optional ADB Tip  ▸") {}
        adbToggle.setOnClickListener {
            val show = adbText.visibility != View.VISIBLE
            adbText.visibility = if (show) View.VISIBLE else View.GONE
            adbToggle.text = if (show) "Optional ADB Tip  ▾" else "Optional ADB Tip  ▸"
        }
        root.addView(adbText)

        addButton("Close") { dlg.dismiss() }

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(root)
        }
        dlg.setContentView(scroll)
        dlg.window?.setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.setOnDismissListener { onStatusChanged() }
        dlg.show()
        firstButton?.requestFocus()
    }
}
