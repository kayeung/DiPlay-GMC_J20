package com.shilapi.xcertplay

/**
 * Runs a permission grant inside a very short SELinux permissive window.
 *
 * On head units that run adbd as root every command DiPlay sends over the car's own ADB runs in the
 * `su` domain. Some of those firmwares never allowed `system_server` to use a descriptor owned by
 * that domain, so the `cmd`-routed shell commands (`appops`, `settings`, `pm`) fail while SELinux is
 * enforcing, with `avc: denied { use } ... tcontext=u:r:su:s0 tclass=fd`.
 *
 * A permission written while the kernel only audits those denials is stored exactly as usual and
 * persists, so the window is reopened only when a permission is actually missing. It is closed in a
 * separate command, which runs even when the grant aborted, and only when the device was enforcing
 * beforehand. On every other device the state is left untouched and the body runs as it used to.
 */
internal object AdbPermissiveGrant {
    /** Runs [body] between `setenforce 0` and `setenforce 1` when the device was enforcing. */
    fun <T> run(shell: (String) -> String?, body: () -> T): T {
        val state = runCatching { shell("getenforce") }.getOrNull()?.trim()
        if (state != ENFORCING) return body()
        runCatching { shell("setenforce 0") }
        return try {
            body()
        } finally {
            runCatching { shell("setenforce 1") }
        }
    }

    private const val ENFORCING = "Enforcing"
}
