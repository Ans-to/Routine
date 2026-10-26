package app.routine.core.model

import kotlinx.serialization.Serializable

/**
 * Exhaustive catalog of deterministic actions supported by Routine.
 */
@Serializable
enum class ActionType(
    val requiredTier: PrivilegeTier,
    val requiredPermissions: Set<SystemPermission>,
) {
    /** Posts a local system notification (`title`, `message`, optional `includeLocalWeatherSummary`). */
    NOTIFY(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.POST_NOTIFICATIONS),
    ),

    /** Displays a short user-visible toast message (`message`). */
    TOAST(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),

    /** Sets the audio ringer mode (`mode` = `SILENT`, `VIBRATE`, or `NORMAL`). */
    RINGER_MODE(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.NOTIFICATION_POLICY_ACCESS),
    ),

    /** Launches an installed app by package or category (`packageName` or `category` = `MUSIC` / `NEWS`). */
    OPEN_APP(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),

    /** Opens a URI via `Intent.ACTION_VIEW` (`url`). */
    OPEN_URL(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),

    /** Sets screen brightness percentage (`percent` = `0..100`). */
    BRIGHTNESS(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.WRITE_SYSTEM_SETTINGS),
    ),

    /** Enables or disables Do Not Disturb (`enabled` = `true` or `false`). */
    DO_NOT_DISTURB(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.NOTIFICATION_POLICY_ACCESS),
    ),

    /** Prepares or dispatches a deterministic SMS auto-reply (`message`, `mode` = `INTENT` or `DIRECT`). */
    SMS_AUTO_REPLY(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.SEND_SMS),
    ),

    /** Enables battery saver prompt or state (`enabled` = `true`). */
    BATTERY_SAVER(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = emptySet(),
    ),

    /** Locks or unlocks screen auto-rotation (`locked` = `true` or `false`). */
    ROTATION_LOCK(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(SystemPermission.WRITE_SYSTEM_SETTINGS),
    ),

    /** Silences or rejects incoming calls from numbers not present in contacts (`reject` = `true` or `false`). */
    SILENCE_UNKNOWN_CALLER(
        requiredTier = PrivilegeTier.STANDARD,
        requiredPermissions = setOf(
            SystemPermission.READ_CONTACTS,
            SystemPermission.CALL_SCREENING_ROLE,
        ),
    ),

    /** Executes a privileged shell command via Routine's internal privilege engine (`command`). */
    SHELL_COMMAND(
        requiredTier = PrivilegeTier.PRIVILEGED,
        requiredPermissions = setOf(SystemPermission.ADB_SHELL_BRIDGE),
    ),

    /** Enables or disables Wi-Fi via Routine's internal privilege engine (`enabled` = `true` or `false`). */
    WIFI_TOGGLE(
        requiredTier = PrivilegeTier.PRIVILEGED,
        requiredPermissions = setOf(SystemPermission.ADB_SHELL_BRIDGE),
    ),

    /** Enables or disables the Wi-Fi hotspot via Routine's internal privilege engine (`enabled` = `false`). */
    HOTSPOT_TOGGLE(
        requiredTier = PrivilegeTier.PRIVILEGED,
        requiredPermissions = setOf(SystemPermission.ADB_SHELL_BRIDGE),
    ),

    /** Writes a secure, global, or system setting (`namespace`, `key`, `value`). */
    SECURE_SETTING(
        requiredTier = PrivilegeTier.PRIVILEGED,
        requiredPermissions = setOf(SystemPermission.WRITE_SECURE_SETTINGS),
    ),
}

/**
 * Immutable action step belonging to a [Rule].
 */
@Serializable
data class Action(
    val type: ActionType,
    val config: Map<String, String> = emptyMap(),
)
