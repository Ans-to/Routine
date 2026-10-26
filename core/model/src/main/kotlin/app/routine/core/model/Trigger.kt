package app.routine.core.model

import kotlinx.serialization.Serializable

/**
 * Exhaustive catalog of deterministic event-driven triggers supported by Routine.
 */
@Serializable
enum class TriggerType(
    val requiredTier: PrivilegeTier,
    val requiredPermissions: Set<SystemPermission>,
) {
    /** Daily or weekly exact-time alarm trigger (`hour`, `minute`, optional `daysOfWeek`, `requireCharging`). */
    TIME(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.SCHEDULE_EXACT_ALARM),
    ),

    /** Battery percentage threshold trigger (`thresholdPercent`, `direction` = `BELOW` or `ABOVE`, `chargingOnly`). */
    BATTERY_LEVEL(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),

    /** Wi-Fi SSID connection or disconnection trigger (`ssid`, `transition` = `CONNECTED` or `DISCONNECTED`). */
    WIFI_SSID(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(
            SystemPermission.ACCESS_WIFI_STATE,
            SystemPermission.ACCESS_FINE_LOCATION,
        ),
    ),

    /** Charger connected or disconnected trigger (`state` = `CONNECTED` or `DISCONNECTED`). */
    CHARGER(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),

    /** System boot completed trigger (`ACTION_BOOT_COMPLETED`). */
    BOOT_COMPLETED(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),

    /**
     * Bluetooth device or audio profile connection trigger (`deviceCategory` = `CAR` or `AUDIO`,
     * optional `deviceName`).
     */
    BLUETOOTH_CONNECTED(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.BLUETOOTH_CONNECT),
    ),

    /** Calendar busy meeting start or manual trigger (`mode` = `CALENDAR_BUSY` or `MANUAL`). */
    CALENDAR_OR_MANUAL(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.READ_CALENDAR),
    ),

    /** WorkManager periodic background check (`metric` = `MOBILE_DATA_MB` or `FREE_STORAGE_PERCENT`, `threshold`). */
    PERIODIC_CHECK(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),

    /** Framework `LocationManager` geofence trigger (`latitude`, `longitude`, `radiusMeters`). */
    LOCATION_GEOFENCE(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(
            SystemPermission.ACCESS_FINE_LOCATION,
            SystemPermission.ACCESS_BACKGROUND_LOCATION,
        ),
    ),

    /** Deterministic Accessibility foreground package match (`packageNames` comma-separated). */
    APP_OPENED(
        requiredTier = PrivilegeTier.ACCESSIBILITY,
        requiredPermissions = setOf(SystemPermission.ACCESSIBILITY_SERVICE),
    ),

    /** Wi-Fi hotspot active for configured duration (`durationMinutes`, default `30`). */
    HOTSPOT_DURATION(
        requiredTier = PrivilegeTier.PRIVILEGED,
        requiredPermissions = setOf(SystemPermission.SCHEDULE_EXACT_ALARM),
    ),

    /** Incoming call screening trigger (`unknownOnly` = `true`). */
    CALL_RECEIVED(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(
            SystemPermission.READ_CONTACTS,
            SystemPermission.CALL_SCREENING_ROLE,
        ),
    ),

    /** System clock alarm dismissed trigger. */
    ALARM_DISMISSED(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),
}

/**
 * Immutable trigger specification belonging to a [Rule].
 */
@Serializable
data class Trigger(
    val type: TriggerType,
    val config: Map<String, String> = emptyMap(),
)
