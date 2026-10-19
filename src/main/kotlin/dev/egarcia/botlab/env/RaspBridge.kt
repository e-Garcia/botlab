package dev.egarcia.botlab.env

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import app.talsec.rasp.security.api.MalwareScanScope
import app.talsec.rasp.security.api.ReasonMode
import app.talsec.rasp.security.api.ScopeType
import app.talsec.rasp.security.api.SuspiciousAppDetectionConfig
import app.talsec.rasp.security.api.SuspiciousAppInfo
import app.talsec.rasp.security.api.Talsec
import app.talsec.rasp.security.api.TalsecConfig
import app.talsec.rasp.security.api.TalsecMode
import app.talsec.rasp.security.api.ThreatListener
import dev.egarcia.botlab.analysis.EnvSignal
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs freeRASP (Talsec's community RASP SDK, the kind of library banks and social apps embed) and turns its
 * verdicts into [EnvSignal]s. Configured like a hardened app would: permission-based suspicious-app detection is on,
 * with the permission sets stalkerware and remote-access tools typically request.
 *
 * freeRASP is a third-party SDK with its own network activity, so this test app should only be used on a test
 * device or emulator, never with real accounts.
 */
object RaspBridge {
    val signals = CopyOnWriteArrayList<EnvSignal>()

    @Volatile
    var mode = "?"
        private set

    @Volatile
    var checksFinished = false
        private set

    /** Permission sets a hardened app might treat as suspicious in a *third-party* app. Each inner set must match in full. */
    private val SUSPICIOUS_PERMISSION_SETS = setOf(
        setOf("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        setOf("android.permission.SYSTEM_ALERT_WINDOW"),
        setOf("android.permission.READ_SMS"),
    )

    private const val PREFS = "botlab_rasp"

    /** Production mode enables the checks a shipped app runs (emulator, debugger, hooks, ...); debug mode skips many. */
    fun productionMode(app: Application): Boolean = app.getSharedPreferences(PREFS, 0).getBoolean("prod", true)

    fun setProductionMode(app: Application, prod: Boolean) {
        app.getSharedPreferences(PREFS, 0).edit().putBoolean("prod", prod).apply()
    }

    fun start(app: Application) {
        val config = TalsecConfig.Builder(app.packageName, arrayOf(signingCertHash(app)))
            .prod(productionMode(app))
            .killOnBypass(false)
            .suspiciousAppDetection(
                // Positional: the SDK's parameter names are obfuscated. (packages, hashes, requested perms, granted perms, scope, reasons)
                SuspiciousAppDetectionConfig(
                    emptySet(), emptySet(), SUSPICIOUS_PERMISSION_SETS, emptySet(),
                    MalwareScanScope(ScopeType.SIDELOADED_ONLY, emptyList()), ReasonMode.ALL,
                ),
            )
            .build()

        val threats = object : ThreatListener.ThreatDetected() {
            override fun onPrivilegedAccess() = add("root", "high", "Root / privileged access detected")
            override fun onDebug() = add("debugger", "medium", "Debugger attached or app is debuggable")
            override fun onSimulator() = add("emulator", "medium", "Emulator detected")
            override fun onAppIntegrity() = add("integrity", "high", "App integrity / tampering")
            override fun onUnofficialStore() = add("unofficial_store", "medium", "Installed from outside an official store")
            override fun onHooks() = add("hooks", "high", "Hooking framework detected (Frida, Xposed, ...)")
            override fun onDeviceBinding() = add("device_binding", "info", "Device binding changed")
            override fun onObfuscationIssues() = add("obfuscation", "info", "App code is not obfuscated")
            override fun onAutomation() = add("automation", "high", "UI automation detected")
            override fun onScreenshot() = add("screenshot", "info", "Screenshot taken")
            override fun onScreenRecording() = add("screen_recording", "info", "Screen recording active")
            override fun onMultiInstance() = add("multi_instance", "info", "App cloning / multiple instances")
            override fun onUnsecureWifi() = add("unsecure_wifi", "info", "Unsecure Wi-Fi")
            override fun onTimeSpoofing() = add("time_spoofing", "medium", "Device time looks manipulated")
            override fun onLocationSpoofing() = add("location_spoofing", "medium", "Location looks spoofed")
            override fun onBootloader() = add("bootloader", "medium", "Bootloader is unlocked")
            override fun onMalware(suspiciousApps: List<SuspiciousAppInfo>) {
                suspiciousApps.forEach {
                    add(
                        "malware:${it.packageInfo.packageName}", "high", "Suspicious app flagged: ${it.packageInfo.packageName}",
                        "reasons=${it.reasons} requested permissions=${it.permissions}",
                    )
                }
            }
        }
        val device = object : ThreatListener.DeviceState() {
            override fun onPasscode() = add("no_passcode", "info", "No screen lock set")
            override fun onSecureHardwareNotAvailable() = add("no_secure_hw", "info", "No secure hardware")
            override fun onDevMode() = add("dev_mode", "low", "Developer options enabled")
            override fun onAdbEnabled() = add("adb", "low", "USB debugging (ADB) enabled")
            override fun onSystemVpn() = add("vpn", "info", "System VPN active")
        }
        val execution = object : ThreatListener.RaspExecutionState() {
            override fun onAllChecksFinished() {
                checksFinished = true
            }
        }
        mode = if (productionMode(app)) "production" else "debug"
        ThreatListener(threats, device, execution).registerListener(app)
        Talsec.start(app, config, TalsecMode.BACKGROUND)
    }

    private fun add(id: String, severity: String, title: String, detail: String = "") {
        if (signals.none { it.id == "rasp:$id" }) signals += EnvSignal("rasp:$id", "freeRASP", severity, title, detail)
    }

    /** freeRASP verifies the app's own signature; use whichever key signed this build so debug builds work.
     * Three API tiers because minSdk 26 predates both `PackageInfoFlags` (33) and `GET_SIGNING_CERTIFICATES` (28). */
    private fun signingCertHash(app: Application): String {
        val pm = app.packageManager
        val cert: ByteArray = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val info = pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                info.signingInfo!!.apkContentsSigners.first().toByteArray()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo!!.apkContentsSigners.first().toByteArray()
            }
            else -> {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures!!.first().toByteArray()
            }
        }
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert), Base64.NO_WRAP)
    }
}
