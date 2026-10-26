package app.routine.privilege.api

/**
 * Availability state of Routine's self-contained privilege layer.
 */
sealed interface PrivilegeAvailability {
    /** Neither Wireless ADB Bridge nor One-Time ADB Grant has been configured yet. */
    data object NotConfigured : PrivilegeAvailability

    /** Internal ADB Bridge Binder server is active and connected. */
    data class AdbBridgeConnected(val uid: Int) : PrivilegeAvailability

    /** Persistent One-Time ADB permission grant (`WRITE_SECURE_SETTINGS`) is active. */
    data object OneTimeGrantActive : PrivilegeAvailability
}
