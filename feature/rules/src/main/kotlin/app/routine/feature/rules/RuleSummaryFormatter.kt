package app.routine.feature.rules

import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.Rule
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Produces concise human-readable summaries and default valid parameters for [Trigger] and [Action] steps.
 */
object RuleSummaryFormatter {
    fun summarize(rule: Rule): String {
        val triggerText = summarizeTrigger(rule.trigger)
        val actionsText = rule.actions.joinToString(" + ") { summarizeAction(it) }
        return "$triggerText -> $actionsText"
    }

    fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    fun summarizeTrigger(trigger: Trigger): String {
        val cfg = trigger.config
        return when (trigger.type) {
            TriggerType.TIME -> "At ${cfg.twoDigit("hour")}:${cfg.twoDigit("minute")}"
            TriggerType.BATTERY_LEVEL ->
                "Battery ${cfg.opt("direction", "BELOW")} ${cfg.opt("thresholdPercent", "20")}%"
            TriggerType.WIFI_SSID -> "Wi-Fi ${cfg.opt("transition", "CONNECTED")} (${cfg.opt("ssid", "Wi-Fi")})"
            TriggerType.CHARGER -> "Charger ${cfg.opt("state", "CONNECTED")}"
            TriggerType.BOOT_COMPLETED -> "Device boot completed"
            TriggerType.BLUETOOTH_CONNECTED -> "Bluetooth ${cfg.opt("deviceCategory", "AUDIO")} connected"
            TriggerType.CALENDAR_OR_MANUAL -> "Calendar / Manual (${cfg.opt("mode", "MANUAL")})"
            TriggerType.PERIODIC_CHECK -> "Periodic ${cfg.opt("metric", "FREE_STORAGE_PERCENT")}"
            TriggerType.LOCATION_GEOFENCE -> "Arrive at geofence"
            TriggerType.APP_OPENED -> "App opened (${cfg.opt("packageNames", "app")})"
            TriggerType.HOTSPOT_DURATION -> "Hotspot active ${cfg.opt("durationMinutes", "30")}m"
            TriggerType.CALL_RECEIVED -> "Incoming unknown call"
            TriggerType.ALARM_DISMISSED -> "Alarm dismissed"
        }
    }

    fun summarizeAction(action: Action): String {
        val cfg = action.config
        return when (action.type) {
            ActionType.NOTIFY -> "Notify '${cfg.opt("title", "Alert")}'"
            ActionType.TOAST -> "Toast '${cfg.opt("message", "Done")}'"
            ActionType.RINGER_MODE -> "Ringer ${cfg.opt("mode", "SILENT")}"
            ActionType.OPEN_APP -> "Open ${cfg.opt("packageName", cfg.opt("category", "App"))}"
            ActionType.OPEN_URL -> "Open URL"
            ActionType.BRIGHTNESS -> "Brightness ${cfg.opt("percent", "50")}%"
            ActionType.DO_NOT_DISTURB -> "DND ${onOffLabel(cfg["enabled"], defaultOn = true)}"
            ActionType.SMS_AUTO_REPLY -> "SMS Auto-Reply"
            ActionType.BATTERY_SAVER -> "Battery Saver"
            ActionType.ROTATION_LOCK -> "Rotation Lock"
            ActionType.SILENCE_UNKNOWN_CALLER -> "Silence Unknown Caller"
            ActionType.SHELL_COMMAND -> "Shell Command"
            ActionType.WIFI_TOGGLE -> "Wi-Fi ${onOffLabel(cfg["enabled"], defaultOn = false)}"
            ActionType.HOTSPOT_TOGGLE -> "Hotspot ${onOffLabel(cfg["enabled"], defaultOn = false)}"
            ActionType.SECURE_SETTING -> "Set ${cfg.opt("key", "setting")}"
        }
    }

    private fun Map<String, String>.opt(key: String, fallback: String): String = this[key] ?: fallback

    private fun Map<String, String>.twoDigit(key: String): String =
        (this[key]?.toIntOrNull() ?: 0).toString().padStart(2, '0')

    private fun onOffLabel(raw: String?, defaultOn: Boolean): String {
        val enabled = raw?.toBooleanStrictOrNull() ?: defaultOn
        return if (enabled) "On" else "Off"
    }

    fun defaultTriggerConfig(type: TriggerType): Map<String, String> = when (type) {
        TriggerType.TIME -> mapOf("hour" to "22", "minute" to "0")
        TriggerType.BATTERY_LEVEL -> mapOf("thresholdPercent" to "20", "direction" to "BELOW")
        TriggerType.WIFI_SSID -> mapOf("ssid" to "HomeWiFi", "transition" to "CONNECTED")
        TriggerType.CHARGER -> mapOf("state" to "CONNECTED")
        TriggerType.BOOT_COMPLETED -> emptyMap()
        TriggerType.BLUETOOTH_CONNECTED -> mapOf("deviceCategory" to "AUDIO")
        TriggerType.CALENDAR_OR_MANUAL -> mapOf("mode" to "MANUAL")
        TriggerType.PERIODIC_CHECK -> mapOf("metric" to "FREE_STORAGE_PERCENT", "threshold" to "10")
        TriggerType.LOCATION_GEOFENCE -> mapOf(
            "latitude" to "24.8607",
            "longitude" to "67.0011",
            "radiusMeters" to "150",
        )
        TriggerType.APP_OPENED -> mapOf("packageNames" to "org.videolan.vlc")
        TriggerType.HOTSPOT_DURATION -> mapOf("durationMinutes" to "30")
        TriggerType.CALL_RECEIVED -> mapOf("unknownOnly" to "true")
        TriggerType.ALARM_DISMISSED -> emptyMap()
    }

    fun defaultActionConfig(type: ActionType): Map<String, String> = when (type) {
        ActionType.NOTIFY -> mapOf("title" to "Routine Alert", "message" to "Automation executed")
        ActionType.TOAST -> mapOf("message" to "Routine triggered")
        ActionType.RINGER_MODE -> mapOf("mode" to "VIBRATE")
        ActionType.OPEN_APP -> mapOf("category" to "MUSIC")
        ActionType.OPEN_URL -> mapOf("url" to "https://f-droid.org")
        ActionType.BRIGHTNESS -> mapOf("percent" to "25")
        ActionType.DO_NOT_DISTURB -> mapOf("enabled" to "true")
        ActionType.SMS_AUTO_REPLY -> mapOf("message" to "Driving right now, will reply soon.", "mode" to "INTENT")
        ActionType.BATTERY_SAVER -> mapOf("enabled" to "true")
        ActionType.ROTATION_LOCK -> mapOf("locked" to "true")
        ActionType.SILENCE_UNKNOWN_CALLER -> mapOf("reject" to "false")
        ActionType.SHELL_COMMAND -> mapOf("command" to "input keyevent 85")
        ActionType.WIFI_TOGGLE -> mapOf("enabled" to "false")
        ActionType.HOTSPOT_TOGGLE -> mapOf("enabled" to "false")
        ActionType.SECURE_SETTING -> mapOf("namespace" to "SECURE", "key" to "night_display_activated", "value" to "1")
    }
}
