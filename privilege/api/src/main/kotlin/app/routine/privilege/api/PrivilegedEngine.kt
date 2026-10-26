package app.routine.privilege.api

import javax.inject.Qualifier

/**
 * Qualifier selecting the built-in Wireless Debugging ADB Bridge engine (`:privilege:adb-bridge`).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AdbBridgeTier

/**
 * Qualifier selecting the persistent One-Time ADB Grant engine (`:privilege:one-time-grant`).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OneTimeGrantTier

/**
 * Self-contained privileged execution boundary for Routine.
 *
 * Implementations reside exclusively in `:privilege:adb-bridge` and `:privilege:one-time-grant`.
 */
interface PrivilegedEngine {
    /**
     * Inspects whether this privilege engine is currently ready to execute privileged operations.
     *
     * Contract: Queries live Binder liveness or OS permission state and returns a [PrivilegeAvailability] value.
     * Failure modes: Never throws; Binder dead-object or security errors resolve to
     * [PrivilegeAvailability.NotConfigured] with an explanatory reason.
     * Thread safety: Safe for concurrent invocation from any coroutine dispatcher.
     */
    suspend fun checkAvailability(): PrivilegeAvailability

    /**
     * Executes a shell [command] with an explicit [timeoutMs] deadline and returns its structured output.
     *
     * Contract: Runs [command] via the internal `shell` UID Binder server (Option A) and captures stdout, stderr,
     * and exit code within [timeoutMs].
     * Failure modes: Rejects blank commands, timeouts, disconnected bridges, or unsupported tiers (such as Option B
     * without shell access) as [ShellCommandResult.Failed]; propagates coroutine cancellation.
     * Thread safety: Safe for concurrent invocation across arbitrary coroutine dispatchers.
     */
    suspend fun executeShellCommand(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): ShellCommandResult

    /**
     * Grants an Android runtime or development [permission] to [packageName].
     *
     * Contract: Executes `pm grant` (or no-ops with success if already granted) for validated package and permission
     * identifiers.
     * Failure modes: Rejects malformed identifiers or unavailable privilege tiers via
     * [PrivilegeOperationResult.Failed]; never throws uncaught domain exceptions.
     * Thread safety: Safe for concurrent invocation across arbitrary coroutine dispatchers.
     */
    suspend fun grantPermission(packageName: String, permission: String): PrivilegeOperationResult

    /**
     * Writes [value] to [key] inside the specified Android settings [namespace].
     *
     * Contract: Persists the key-value pair directly via `WRITE_SECURE_SETTINGS` when granted, or via the `shell`
     * `settings put` command when connected through the ADB Bridge.
     * Failure modes: Rejects blank keys or missing privileges as [PrivilegeOperationResult.Failed]; propagates
     * coroutine cancellation cleanly.
     * Thread safety: Safe for concurrent invocation across arbitrary coroutine dispatchers.
     */
    suspend fun writeSecureSetting(
        namespace: SecureSettingNamespace,
        key: String,
        value: String,
    ): PrivilegeOperationResult

    companion object {
        const val DEFAULT_SHELL_TIMEOUT_MS: Long = 8_000L
        private val SAFE_IDENTIFIER_REGEX = Regex("^[a-zA-Z0-9_.]+$")

        /**
         * Validates that [identifier] is a safe Android package name, permission name, or setting key.
         *
         * Contract: Returns `true` only when [identifier] is non-blank and matches `^[a-zA-Z0-9_.]+$`, preventing
         * shell metacharacter injection.
         * Failure modes: Never throws.
         * Thread safety: Pure stateless regex check; completely thread-safe.
         */
        fun isSafeIdentifier(identifier: String): Boolean =
            identifier.isNotBlank() && SAFE_IDENTIFIER_REGEX.matches(identifier)
    }
}
