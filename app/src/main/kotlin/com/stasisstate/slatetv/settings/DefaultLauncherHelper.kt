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

    internal data class HomeStatus(
        val isActive: Boolean,
        val otherLauncherPackage: String?,
        val roleHeld: Boolean,
    ) {
        val isOverridden: Boolean get() = roleHeld && otherLauncherPackage != null
    }

    // ---- Pure, unit-testable helpers -------------------------------------------------------

    /** Settings actions to try, in order, when the role request is unavailable or cancelled. */
    internal fun homeSettingsFallbackActions(): List<String> = listOf(
        Settings.ACTION_HOME_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
    )

    internal fun buildHomeSettingsFallbackIntents(): List<Intent> =
        homeSettingsFallbackActions().map { Intent(it) }

    /** Returns the package if it names a real launcher, or null for empty / the "android" stub. */
    internal fun normalizeHomePackage(pkg: String?): String? =
        pkg?.takeIf { it.isNotBlank() && it != RESOLVER_STUB_PACKAGE }

    /**
     * Some TVs resolve CATEGORY_HOME to a higher-priority preinstalled launcher even after
     * granting Slate TV the Home role. Check both signals so the UI can explain the mismatch.
     */
    internal fun computeHomeStatus(resolvedHomePackage: String?, selfPackage: String, roleHeld: Boolean): HomeStatus {
        val resolved = normalizeHomePackage(resolvedHomePackage)
        return HomeStatus(resolved == selfPackage, resolved?.takeUnless { it == selfPackage }, roleHeld)
    }

    internal fun adbSetHomeCommand(selfPackage: String): String =
        "adb shell cmd package set-home-activity --user current $selfPackage/.ui.HomeActivity"

    internal fun adbDisableLauncherCommand(currentHomePackage: String): String =
        "adb shell pm disable-user --user current $currentHomePackage"

    private const val ADB_RESOLVE_HOME = "adb shell cmd package resolve-activity --brief --user current " +
        "--query-flags 65536 -a android.intent.action.MAIN -c android.intent.category.HOME"

    internal fun adbTipText(selfPackage: String, currentHomePackage: String?): String = buildString {
        append("Connect to your TV over ADB. Set Slate TV as Home and check which launcher will open.\n\n")
        append(adbSetHomeCommand(selfPackage))
        append("\n\n$ADB_RESOLVE_HOME")
        if (currentHomePackage != null) {
            append("\n\nIf the check still reports $currentHomePackage, disable that launcher and check Home again.\n\n")
            append(adbDisableLauncherCommand(currentHomePackage))
            append("\n\nTo re-enable it, run\n\nadb shell pm enable --user current $currentHomePackage")
        }
    }

    internal fun statusText(status: HomeStatus): String = when {
        status.isActive -> "Slate TV is your Default Home launcher."
        status.isOverridden -> "Android selected Slate TV. Home still opens ${status.otherLauncherPackage}. See the ADB instructions below."
        status.roleHeld -> "Android selected Slate TV. The Home activity could not be confirmed."
        status.otherLauncherPackage != null -> "Home currently opens ${status.otherLauncherPackage}."
        else -> "No default Home app is set."
    }

    // ---- Android-backed helpers ------------------------------------------------------------

    private fun roleManager(context: Context): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
        } else {
            null
        }

    private fun isRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            roleManager(context)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        }.getOrDefault(false)
    }

    private fun resolveHomeActivityPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
    }

    internal fun getHomeStatus(context: Context): HomeStatus =
        computeHomeStatus(resolveHomeActivityPackage(context), context.packageName, isRoleHeld(context))

    internal fun buildRequestHomeRoleIntent(context: Context): Intent? {
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

    // ---- Dialog -----------------------------------------------------------------------------

    internal fun showDefaultLauncherDialog(
        activity: AppCompatActivity,
        onSetDefault: () -> Boolean,
        onOpenHomeSettings: () -> Boolean,
    ) {
        val status = getHomeStatus(activity)
        val currentPkg = status.otherLauncherPackage
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
        root.addView(text(statusText(status), 14f, "#94A3B8", 14))

        val launchError = text("", 14f, "#F8FAFC", 8).apply { visibility = View.GONE }
        root.addView(launchError)

        if (!status.isActive && !status.roleHeld) {
            addButton("Set Slate TV as Default Home") {
                if (onSetDefault()) {
                    dlg.dismiss()
                } else {
                    launchError.text = "This TV could not open a Home picker. See the ADB instructions below."
                    launchError.visibility = View.VISIBLE
                }
            }
        }
        addButton("Open Default Home App Settings") {
            if (onOpenHomeSettings()) {
                dlg.dismiss()
            } else {
                launchError.text = "This TV could not open Home settings. See the ADB instructions below."
                launchError.visibility = View.VISIBLE
            }
        }
        if (currentPkg != null && !status.isOverridden) {
            addButton("Open Current Launcher App Info") {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$currentPkg"))
                if (runCatching { activity.startActivity(intent) }.isSuccess) {
                    dlg.dismiss()
                } else {
                    launchError.text = "This TV could not open the current launcher's App Info."
                    launchError.visibility = View.VISIBLE
                }
            }
            root.addView(text("Choose Clear defaults in App Info if your TV offers it.", 12f, "#64748B", 8))
        }

        val adbText = text(adbTipText(activity.packageName, currentPkg), 12f, "#94A3B8", 8).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            setTextIsSelectable(false)
        }
        val adbToggle = addButton("Show ADB Instructions  ▸") {}
        adbToggle.setOnClickListener {
            val show = adbText.visibility != View.VISIBLE
            adbText.visibility = if (show) View.VISIBLE else View.GONE
            adbToggle.text = if (show) "Hide ADB Instructions  ▾" else "Show ADB Instructions  ▸"
        }
        root.addView(adbText)

        addButton("Close") { dlg.dismiss() }

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(root)
        }
        dlg.setContentView(scroll)
        dlg.window?.setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
        (if (status.isOverridden) adbToggle else firstButton)?.requestFocus()
    }
}
