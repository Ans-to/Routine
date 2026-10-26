package app.routine.privilege.api

/**
 * Target Android settings table namespace for privileged setting writes.
 */
enum class SecureSettingNamespace {
    SECURE,
    GLOBAL,
    SYSTEM,
}

/**
 * Availability state of Routine's self-contained privilege layer.
 */
sealed interface PrivilegeAvailability {
    /**
     * Returns `true` when the engine can currently perform privileged operations.
     *
     * Contract: True for [AdbBridgeConnected] and [OneTimeGrantActive]; false for [NotConfigured].
     * Failure modes: Pure boolean check; never throws.
     * Thread safety: Immutable value hierarchy; completely thread-safe.
     */
    fun isAvailable(): Boolean = this !is NotConfigured

    /** Neither Wireless ADB Bridge nor One-Time ADB Grant is currently active. */
    data class NotConfigured(
        val reason: String = "Privilege tier is not configured.",
        val setupHint: String = "Enable One-Time ADB Grant or Wireless Debugging ADB Bridge in Settings.",
    ) : PrivilegeAvailability

    /** Internal ADB Bridge Binder server is active and connected (typically UID 2000 `shell`). */
    data class AdbBridgeConnected(val uid: Int) : PrivilegeAvailability

    /** Persistent One-Time ADB permission grant (`WRITE_SECURE_SETTINGS`) is active. */
    data object OneTimeGrantActive : PrivilegeAvailability
}

/**
 * Structured outcome of executing a shell command through [PrivilegedEngine.executeShellCommand].
 */
sealed interface ShellCommandResult {
    data class Succeeded(
        val stdout: String,
        val stderr: String = "",
        val exitCode: Int = 0,
    ) : ShellCommandResult

    data class Failed(
        val reason: String,
        val exitCode: Int? = null,
    ) : ShellCommandResult
}

/**
 * Structured outcome of granting a permission or writing a secure setting via [PrivilegedEngine].
 */
sealed interface PrivilegeOperationResult {
    data class Succeeded(
        val detail: String,
    ) : PrivilegeOperationResult

    data class Failed(
        val reason: String,
    ) : PrivilegeOperationResult
}
