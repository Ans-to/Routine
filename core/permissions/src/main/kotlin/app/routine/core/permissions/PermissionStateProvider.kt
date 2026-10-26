package app.routine.core.permissions

import app.routine.core.model.SystemPermission
import kotlinx.coroutines.flow.StateFlow

/**
 * Snapshot of currently granted [SystemPermission]s and active privilege capabilities on the device.
 */
data class PermissionSnapshot(
    val grantedPermissions: Set<SystemPermission>,
    val isAdbBridgeReady: Boolean = false,
    val isOneTimeGrantReady: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
) {
    /**
     * Checks whether a given [permission] is satisfied by the current device state.
     *
     * Contract: Evaluates runtime permissions as well as synthesized privilege capabilities
     * (`ADB_SHELL_BRIDGE`, `WRITE_SECURE_SETTINGS`, `ACCESSIBILITY_SERVICE`).
     * Failure modes: Pure in-memory lookup; never throws.
     * Thread safety: Immutable value type; completely thread-safe.
     */
    fun isSatisfied(permission: SystemPermission): Boolean = when (permission) {
        SystemPermission.ADB_SHELL_BRIDGE -> isAdbBridgeReady || permission in grantedPermissions
        SystemPermission.WRITE_SECURE_SETTINGS ->
            isAdbBridgeReady || isOneTimeGrantReady || permission in grantedPermissions
        SystemPermission.ACCESSIBILITY_SERVICE ->
            isAccessibilityEnabled || permission in grantedPermissions
        else -> permission in grantedPermissions
    }
}

/**
 * Supplies current and reactive [PermissionSnapshot] values to `:core:permissions` and `:core:engine`.
 */
interface PermissionStateProvider {
    /**
     * Reactive stream of the latest [PermissionSnapshot].
     *
     * Contract: Always emits the most recent permission state immediately to new subscribers.
     * Failure modes: Never completes with an error.
     * Thread safety: [StateFlow] is safe for concurrent collection across all dispatchers.
     */
    val snapshotFlow: StateFlow<PermissionSnapshot>

    /**
     * Refreshes and returns an up-to-date [PermissionSnapshot] from the host OS.
     *
     * Contract: Queries OS permission managers and privilege engines, updates [snapshotFlow], and returns the snapshot.
     * Failure modes: Any underlying Binder/OS exception must be caught by implementations and logged without crashing.
     * Thread safety: Safe to invoke concurrently from any coroutine dispatcher.
     */
    suspend fun refresh(): PermissionSnapshot
}
