package dev.egarcia.botlab.env

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.egarcia.botlab.analysis.EnvSignal

/**
 * What an ordinary app can learn about its surroundings with no special access, plus the freeRASP verdicts.
 * Nothing here is exotic: these are the first things an anti-abuse check reads.
 */
object EnvironmentProbe {

    fun collect(context: Context): List<EnvSignal> =
        enabledAccessibilityServices(context) + installedAccessibilityApps(context) + deviceSettings(context) +
            emulatorHints() + installSources(context) + listOf(EnvSignal("rasp_mode", "freeRASP", "info", "freeRASP mode: ${RaspBridge.mode}", "checks finished=${RaspBridge.checksFinished}")) + RaspBridge.signals

    /** `AccessibilityManager` lists every enabled accessibility service to any app. */
    private fun enabledAccessibilityServices(context: Context): List<EnvSignal> {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (services.isEmpty()) return listOf(EnvSignal("a11y_none", "platform", "info", "No accessibility service is enabled"))
        return services.map { info ->
            val pkg = info.resolveInfo.serviceInfo.packageName
            val readsScreen = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
            val gestures = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
            val screenshots = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
            val tool = if (Build.VERSION.SDK_INT >= 34) info.isAccessibilityTool else null
            val severity = when {
                tool == true -> "info"
                readsScreen || gestures -> "high"
                else -> "medium"
            }
            EnvSignal(
                "a11y_enabled:$pkg", "platform", severity, "Accessibility service enabled: $pkg",
                "reads screen=$readsScreen, performs gestures=$gestures, takes screenshots=$screenshots, declared accessibility tool=$tool",
            )
        }
    }

    /** A package-visibility query for the accessibility-service intent reveals installed services even when they are off. */
    private fun installedAccessibilityApps(context: Context): List<EnvSignal> {
        val services = context.packageManager.queryIntentServices(Intent("android.accessibilityservice.AccessibilityService"), 0)
        return services.mapNotNull { it.serviceInfo }
            .filter { it.applicationInfo != null && it.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }.distinct()
            .map { EnvSignal("a11y_installed:$it", "platform", "medium", "Installed app declares an accessibility service: $it", "visible with a package query, no special permission, even if the service is switched off") }
    }

    private fun deviceSettings(context: Context): List<EnvSignal> = buildList {
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1) add(EnvSignal("adb", "platform", "low", "USB debugging (ADB) is enabled"))
        if (Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) add(EnvSignal("dev_options", "platform", "low", "Developer options are enabled"))
    }

    private fun emulatorHints(): List<EnvSignal> {
        val hints = listOf(
            "fingerprint" to Build.FINGERPRINT.contains("generic", true),
            "model" to (Build.MODEL.contains("sdk", true) || Build.MODEL.contains("emulator", true)),
            "hardware" to (Build.HARDWARE.equals("goldfish", true) || Build.HARDWARE.equals("ranchu", true)),
            "product" to Build.PRODUCT.contains("sdk", true),
        ).filter { it.second }.map { it.first }
        return if (hints.isEmpty()) emptyList() else listOf(EnvSignal("emulator_build", "platform", "medium", "Build properties look like an emulator", hints.joinToString()))
    }

    /** Who installed us, and (if visible) the harness: sideloaded and debuggable builds are common flags. */
    private fun installSources(context: Context): List<EnvSignal> = buildList {
        val pm = context.packageManager
        fun describe(pkg: String) = runCatching {
            val source = pm.getInstallSourceInfo(pkg).installingPackageName
            val debuggable = pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            "installer=${source ?: "none (sideloaded)"}, debuggable=$debuggable"
        }.getOrNull()
        describe(context.packageName)?.let { add(EnvSignal("self_install", "platform", "info", "This app: $it")) }
        describe("dev.egarcia.harness")?.let { add(EnvSignal("harness_visible", "platform", "medium", "The harness app is visible to other apps: dev.egarcia.harness", it)) }
    }
}
