package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarPlayVpnService
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit settings action; connection startup never grants these permissions. */
internal object UsbPermissionSetup {
    // Keep wheel-service rebinding and explicit USB setup from overwriting each other's service list.
    internal val accessibilityLock = Any()
    enum class Permission {
        ACCESSIBILITY, USAGE, OVERLAY, VPN;

        fun granted(context: Context): Boolean = runCatching {
            when (this) {
                ACCESSIBILITY -> UsbAutoConfirmService.isEnabled(context) &&
                    Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                USAGE -> HomeScreenMonitor.hasAccess(context)
                OVERLAY -> Settings.canDrawOverlays(context)
                // Android 8 head units whose firmware ships the VPN consent dialog broken or missing
                // reach the "VPN authorization unavailable" status and are told to check connection
                // permissions here. Wired CarPlay still needs that authorization, so the one button that
                // can deliver it must offer it: [CarPlayVpnService.prepare] returns null once the
                // ACTIVATE_VPN app-op is allowed, and the dialog is never opened again.
                VPN -> CarPlayVpnService.prepare(context) == null
            }
        }.getOrDefault(false)
    }

    fun snapshot(context: Context): Map<Permission, Boolean> = Permission.entries.associateWith { it.granted(context) }

    internal interface Client : Closeable {
        fun connect(): LocalAdb.Access
        fun shell(command: String): String?
        fun cancel()
    }

    private class AdbClient(context: Context) : Client {
        private val adb = LocalAdb(AdbKeys.load(context))
        override fun connect() = adb.connect(mayAsk = true)
        override fun shell(command: String) = synchronized(accessibilityLock) { adb.shell(command) }
        override fun cancel() = adb.cancelPendingOperations()
        override fun close() = adb.close()
    }

    data class Result(
        val access: LocalAdb.Access,
        val verified: Map<Permission, Boolean>,
        val commandsFailed: Set<Permission> = emptySet(),
    ) {
        val complete: Boolean get() = access == LocalAdb.Access.READY && commandsFailed.isEmpty() &&
            verified.size == Permission.entries.size && verified.values.all { it }
    }

    class Operation(
        private val context: Context,
        private val factory: () -> Client = { AdbClient(context) },
        private val granted: (Permission) -> Boolean = { it.granted(context) },
    ) {
        private val cancelled = AtomicBoolean(false)
        private val lock = Any()
        private var client: Client? = null
        val isCancelled: Boolean get() = cancelled.get()

        fun cancel() {
            cancelled.set(true)
            // Cancellation closes the socket without waiting for a synchronized ADB read.
            synchronized(lock) { client }?.cancel()
        }

        fun run(): Result {
            var access = LocalAdb.Access.UNREACHABLE
            val failed = mutableSetOf<Permission>()
            if (isCancelled) return Result(access, emptyMap())
            runCatching {
                factory().use { adb ->
                    synchronized(lock) {
                        client = adb
                        if (isCancelled) adb.cancel()
                    }
                    if (isCancelled) return@use
                    access = adb.connect()
                    if (access != LocalAdb.Access.READY || isCancelled) return@use
                    for (permission in Permission.entries) {
                        if (isCancelled) break
                        if (granted(permission)) continue
                        val command = command(permission, context.packageName)
                        if (isCancelled) break
                        val output = adb.shell(withExitStatus(command))
                        if (isCancelled) break
                        if (output?.lineSequence()?.lastOrNull() != "$EXIT_MARKER:0") failed += permission
                    }
                }
            }.onFailure { failed += Permission.entries }
            synchronized(lock) { client = null }
            val verified = if (isCancelled) emptyMap() else Permission.entries.associateWith {
                runCatching { granted(it) }.getOrDefault(false)
            }
            return Result(access, verified, failed)
        }
    }

    private const val EXIT_MARKER = "DIPLAY_PERMISSION_EXIT"

    private fun withExitStatus(command: String): String =
        "( $command ); result=\$?; printf '\\n$EXIT_MARKER:%s\\n' \"\$result\""

    /**
     * Host terminal the copied command is pasted into. There is no single form every terminal
     * accepts, so both are offered:
     *
     * - `cmd.exe` keeps single quotes literal and only double quotes start a quoted argument.
     * - PowerShell expands `$current` inside double quotes and has no `&&` before 7.0.
     *
     * Both variants run the same device-side script; only the host-side quoting differs.
     */
    enum class Terminal { POSIX, WINDOWS }

    /**
     * Device-side script for one permission.
     *
     * The package name is validated below and the service name is built from it, so neither is
     * quoted here. An inner quote would have to be re-escaped once per host terminal, and the
     * POSIX-only `'\''` form that produced made the copied command fail in `cmd.exe`.
     */
    internal fun command(permission: Permission, packageName: String): String {
        require(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName))
        val service = "$packageName/com.shilapi.xcertplay.UsbAutoConfirmService"
        return when (permission) {
            Permission.USAGE -> "appops set $packageName GET_USAGE_STATS allow"
            Permission.OVERLAY -> "appops set $packageName SYSTEM_ALERT_WINDOW allow"
            Permission.VPN -> "appops set $packageName ACTIVATE_VPN allow"
            Permission.ACCESSIBILITY -> {
                """current=${'$'}(settings get secure enabled_accessibility_services) || exit 1;
                    case "${'$'}current" in null|"") current="";; esac;
                    case "${'$'}current" in *[!a-zA-Z0-9_./:${'$'}]*) exit 1;; esac;
                    service=$service;
                    case ":${'$'}current:" in *":${'$'}service:"*) ;; *)
                        if [ -n "${'$'}current" ]; then current="${'$'}current:${'$'}service"; else current="${'$'}service"; fi;;
                    esac;
                    settings put secure enabled_accessibility_services "${'$'}current" &&
                    settings put secure accessibility_enabled 1""".trimIndent().replace("\n", " ")
            }
        }
    }

    /** Command for sh/bash/zsh, an Android terminal or Windows PowerShell. */
    fun manualCommand(packageName: String): String = manualCommand(packageName, Terminal.POSIX)

    /**
     * Command for [terminal]. Each permission is granted independently, so the statements are
     * separated by `;` where the host understands it and `&&` where it does not.
     */
    fun manualCommand(packageName: String, terminal: Terminal): String {
        val separator = if (terminal == Terminal.WINDOWS) " && " else " ; "
        return Permission.entries.joinToString(separator) {
            wrapForTerminal(command(it, packageName), terminal)
        }
    }

    private fun wrapForTerminal(script: String, terminal: Terminal): String = when (terminal) {
        Terminal.POSIX -> "adb shell '$script'"
        // cmd.exe keeps the single quotes literal, so hand the script over inside double quotes and
        // escape every inner double quote for adb's own argument parser.
        Terminal.WINDOWS -> "adb shell \"" + script.replace("\"", "\\\"") + "\""
    }
}
