package app.routine.privilege.api

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composite [PrivilegedEngine] coordinating Option A (`:privilege:adb-bridge`) and Option B
 * (`:privilege:one-time-grant`) transparently behind a single contract.
 */
@Singleton
class CompositePrivilegedEngine @Inject constructor(
    @AdbBridgeTier private val adbBridgeEngine: PrivilegedEngine,
    @OneTimeGrantTier private val oneTimeGrantEngine: PrivilegedEngine,
) : PrivilegedEngine {
    /**
     * Returns the highest currently active [PrivilegeAvailability] across Option A and Option B.
     *
     * Contract: Prefers [PrivilegeAvailability.AdbBridgeConnected] when active, otherwise returns
     * [PrivilegeAvailability.OneTimeGrantActive] if granted, else [PrivilegeAvailability.NotConfigured].
     * Failure modes: Never throws.
     * Thread safety: Safe for concurrent invocation across arbitrary coroutine dispatchers.
     */
    override suspend fun checkAvailability(): PrivilegeAvailability {
        val bridgeState = adbBridgeEngine.checkAvailability()
        if (bridgeState.isAvailable()) return bridgeState
        val grantState = oneTimeGrantEngine.checkAvailability()
        if (grantState.isAvailable()) return grantState
        return bridgeState
    }

    /**
     * Delegates shell command execution to the built-in ADB Bridge engine.
     *
     * Contract: Executes [command] via [adbBridgeEngine] when available, or returns [oneTimeGrantEngine]'s
     * explanatory fallback failure when only Option B is configured.
     * Failure modes: Returns [ShellCommandResult.Failed] on validation errors, timeouts, or unavailable bridge.
     * Thread safety: Safe for concurrent invocation across arbitrary coroutine dispatchers.
     */
    override suspend fun executeShellCommand(command: String, timeoutMs: Long): ShellCommandResult {
        if (command.isBlank()) {
            return ShellCommandResult.Failed("Shell command must not be blank.")
        }
        val bridgeState = adbBridgeEngine.checkAvailability()
        return if (bridgeState.isAvailable()) {
            adbBridgeEngine.executeShellCommand(command, timeoutMs)
        } else {
            oneTimeGrantEngine.executeShellCommand(command, timeoutMs)
        }
    }

    /**
     * Grants [permission] to [packageName] using the active ADB Bridge engine.
     *
     * Contract: Validates identifiers and delegates to [adbBridgeEngine] when connected, falling back to
     * [oneTimeGrantEngine] (which succeeds if the permission is already granted).
     * Failure modes: Returns [PrivilegeOperationResult.Failed] on invalid identifiers or disconnected bridge.
     * Thread safety: Safe for concurrent invocation across arbitrary coroutine dispatchers.
     */
    override suspend fun grantPermission(packageName: String, permission: String): PrivilegeOperationResult {
        if (!PrivilegedEngine.isSafeIdentifier(packageName) || !PrivilegedEngine.isSafeIdentifier(permission)) {
            return PrivilegeOperationResult.Failed("Invalid packageName or permission identifier.")
        }
        return if (adbBridgeEngine.checkAvailability().isAvailable()) {
            adbBridgeEngine.grantPermission(packageName, permission)
        } else {
            oneTimeGrantEngine.grantPermission(packageName, permission)
        }
    }

    /**
     * Writes [key]=[value] in [namespace], preferring zero-daemon direct `Settings` writes when Option B (or
     * self-granted `WRITE_SECURE_SETTINGS`) is active, and falling back to the ADB Bridge shell server otherwise.
     *
     * Contract: Validates [key] and persists the setting through the fastest available tier.
     * Failure modes: Returns [PrivilegeOperationResult.Failed] if [key] is invalid or neither tier is available.
     * Thread safety: Safe for concurrent invocation across arbitrary coroutine dispatchers.
     */
    override suspend fun writeSecureSetting(
        namespace: SecureSettingNamespace,
        key: String,
        value: String,
    ): PrivilegeOperationResult {
        if (!PrivilegedEngine.isSafeIdentifier(key)) {
            return PrivilegeOperationResult.Failed("Invalid setting key '$key'.")
        }
        if (oneTimeGrantEngine.checkAvailability().isAvailable()) {
            return oneTimeGrantEngine.writeSecureSetting(namespace, key, value)
        }
        if (adbBridgeEngine.checkAvailability().isAvailable()) {
            return adbBridgeEngine.writeSecureSetting(namespace, key, value)
        }
        return PrivilegeOperationResult.Failed(
            "Neither One-Time ADB Grant nor Wireless ADB Bridge is currently active.",
        )
    }
}
