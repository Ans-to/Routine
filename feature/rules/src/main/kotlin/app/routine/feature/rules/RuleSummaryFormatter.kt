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

    fun summarizeTrigger(trigger: Trigger): String = when (trigger.type) {
        TriggerType.TIME -> {
            val h = (trigger.config["hour"]?.toIntOrNull() ?: 0).toString().padStart(2, '0')
            val m = (trigger.config["minute"]?.toIntOrNull() ?: 0).toString().padStart(2, '0')
            "At $h:$m"
        }
        TriggerType.BATTERY_LEVEL -> {
            val dir = trigger.config["direction"] ?: "BELOW"
            val pct = trigger.config["thresholdPercent"] ?: "20"
            "Battery $dir $pct%"
        }
        TriggerType.WIFI_SSID -> {
            val tr = trigger.config["transition"] ?: "CONNECTED"
            val ssid = trigger.config["ssid"] ?: "Wi-Fi"
            "Wi-Fi $tr ($ssid)"
        }
        TriggerType.CHARGER -> "Charger ${trigger.config["state"] ?: "CONNECTED"}"
        TriggerType.BOOT_COMPLETED -> "Device boot completed"
        TriggerType.BLUETOOTH_CONNECTED -> "Bluetooth ${trigger.config["deviceCategory"] ?: "AUDIO"} connected"
        TriggerType.CALENDAR_OR_MANUAL -> "Calendar / Manual (${trigger.config["mode"] ?: "MANUAL"})"
        TriggerType.PERIODIC_CHECK -> "Periodic ${trigger.config["metric"] ?: "FREE_STORAGE_PERCENT"}"
        TriggerType.LOCATION_GEOFENCE -> "Arrive at geofence"
        TriggerType.APP_OPENED -> "App opened (${trigger.config["packageNames"] ?: "app"})"
        TriggerType.HOTSPOT_DURATION -> "Hotspot active ${trigger.config["durationMinutes"] ?: "30"}m"
        TriggerType.CALL_RECEIVED -> "Incoming unknown call"
        TriggerType.ALARM_DISMISSED -> "Alarm dismissed"
    }

    fun summarizeAction(action: Action): String = when (action.type) {
        ActionType.NOTIFY -> "Notify '${action.config["title"] ?: "Alert"}'"
        ActionType.TOAST -> "Toast '${action.config["message"] ?: "Done"}'"
        ActionType.RINGER_MODE -> "Ringer ${action.config["mode"] ?: "SILENT"}"
        ActionType.OPEN_APP -> "Open ${action.config["packageName"] ?: action.config["category"] ?: "App"}"
        ActionType.OPEN_URL -> "Open URL"
        ActionType.BRIGHTNESS -> "Brightness ${action.config["percent"] ?: "50"}%"
        ActionType.DO_NOT_DISTURB -> "DND ${if (action.config["enabled"] == "false") "Off" else "On"}"
        ActionType.SMS_AUTO_REPLY -> "SMS Auto-Reply"
        ActionType.BATTERY_SAVER -> "Battery Saver"
        ActionType.ROTATION_LOCK -> "Rotation Lock"
        ActionType.SILENCE_UNKNOWN_CALLER -> "Silence Unknown Caller"
        ActionType.SHELL_COMMAND -> "Shell Command"
        ActionType.WIFI_TOGGLE -> "Wi-Fi ${if (action.config["enabled"] == "true") "On" else "Off"}"
        ActionType.HOTSPOT_TOGGLE -> "Hotspot ${if (action.config["enabled"] == "true") "On" else "Off"}"
        ActionType.SECURE_SETTING -> "Set ${action.config["key"] ?: "setting"}"
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
