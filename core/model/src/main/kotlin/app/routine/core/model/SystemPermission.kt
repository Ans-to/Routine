package app.routine.core.model

import kotlinx.serialization.Serializable

/**
 * Built-in Android permissions and special access capabilities tracked by Routine's capability checker.
 */
@Serializable
enum class SystemPermission(
    val androidPermissionName: String,
    val tier: PrivilegeTier,
) {
    POST_NOTIFICATIONS("android.permission.POST_NOTIFICATIONS", PrivilegeTier.STANDARD),
    SCHEDULE_EXACT_ALARM("android.permission.SCHEDULE_EXACT_ALARM", PrivilegeTier.STANDARD),
    IGNORE_BATTERY_OPTIMIZATIONS("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", PrivilegeTier.STANDARD),
    NOTIFICATION_POLICY_ACCESS("android.permission.ACCESS_NOTIFICATION_POLICY", PrivilegeTier.STANDARD),
    NOTIFICATION_LISTENER("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", PrivilegeTier.STANDARD),
    WRITE_SYSTEM_SETTINGS("android.permission.WRITE_SETTINGS", PrivilegeTier.STANDARD),
    SEND_SMS("android.permission.SEND_SMS", PrivilegeTier.STANDARD),
    PACKAGE_USAGE_STATS("android.permission.PACKAGE_USAGE_STATS", PrivilegeTier.STANDARD),
    ACCESS_WIFI_STATE("android.permission.ACCESS_WIFI_STATE", PrivilegeTier.STANDARD),
    ACCESS_FINE_LOCATION("android.permission.ACCESS_FINE_LOCATION", PrivilegeTier.STANDARD),
    ACCESS_BACKGROUND_LOCATION("android.permission.ACCESS_BACKGROUND_LOCATION", PrivilegeTier.STANDARD),
    BLUETOOTH_CONNECT("android.permission.BLUETOOTH_CONNECT", PrivilegeTier.STANDARD),
    READ_CALENDAR("android.permission.READ_CALENDAR", PrivilegeTier.STANDARD),
    READ_CONTACTS("android.permission.READ_CONTACTS", PrivilegeTier.STANDARD),
    CALL_SCREENING_ROLE("android.app.role.CALL_SCREENING", PrivilegeTier.STANDARD),
    ACCESSIBILITY_SERVICE("android.permission.BIND_ACCESSIBILITY_SERVICE", PrivilegeTier.ACCESSIBILITY),
    WRITE_SECURE_SETTINGS("android.permission.WRITE_SECURE_SETTINGS", PrivilegeTier.PRIVILEGED),
    ADB_SHELL_BRIDGE("routine.internal.ADB_SHELL_BRIDGE", PrivilegeTier.PRIVILEGED),
}
