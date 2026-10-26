package app.routine.core.engine

import app.routine.core.model.Trigger
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic evaluator matching incoming [TriggerEvent] payloads against static [Trigger] definitions.
 */
@Singleton
class TriggerMatcher @Inject constructor() {
    /**
     * Evaluates whether [event] satisfies [trigger].
     *
     * Contract: Returns `true` if and only if [event]'s [TriggerType] equals [trigger]'s type and all configured
     * predicates (thresholds, SSIDs, days of week, package names, charging state) match deterministically.
     * Failure modes: Malformed numeric attributes evaluate safely to `false`; never throws.
     * Thread safety: Pure stateless evaluation; completely thread-safe.
     */
    fun matches(trigger: Trigger, event: TriggerEvent): Boolean {
        if (trigger.type != event.type) return false
        return when (trigger.type) {
            TriggerType.TIME -> matchesTime(trigger.config, event.attributes)
            TriggerType.BATTERY_LEVEL -> matchesBattery(trigger.config, event.attributes)
            TriggerType.WIFI_SSID -> matchesWifi(trigger.config, event.attributes)
            TriggerType.CHARGER -> matchesCharger(trigger.config, event.attributes)
            TriggerType.BOOT_COMPLETED -> true
            TriggerType.BLUETOOTH_CONNECTED -> matchesBluetooth(trigger.config, event.attributes)
            TriggerType.CALENDAR_OR_MANUAL -> matchesCalendarOrManual(trigger.config, event.attributes)
            TriggerType.PERIODIC_CHECK -> matchesPeriodicCheck(trigger.config, event.attributes)
            TriggerType.LOCATION_GEOFENCE -> matchesGeofence(trigger.config, event.attributes)
            TriggerType.APP_OPENED -> matchesAppOpened(trigger.config, event.attributes)
            TriggerType.HOTSPOT_DURATION -> matchesHotspotDuration(trigger.config, event.attributes)
            TriggerType.CALL_RECEIVED -> matchesCallReceived(trigger.config, event.attributes)
            TriggerType.ALARM_DISMISSED -> true
        }
    }

    private fun matchesTime(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val expectedHour = config["hour"]?.toIntOrNull() ?: return false
        val expectedMinute = config["minute"]?.toIntOrNull() ?: return false
        val actualHour = attrs["hour"]?.toIntOrNull() ?: expectedHour
        val actualMinute = attrs["minute"]?.toIntOrNull() ?: expectedMinute
        if (expectedHour != actualHour || expectedMinute != actualMinute) return false

        val daysCsv = config["daysOfWeek"]?.trim().orEmpty()
        if (daysCsv.isNotEmpty()) {
            val allowedDays = daysCsv.split(",").map { it.trim().uppercase() }.toSet()
            val actualDay = attrs["dayOfWeek"]?.trim()?.uppercase() ?: return false
            if (actualDay !in allowedDays) return false
        }

        val requireCharging = config["requireCharging"]?.toBooleanStrictOrNull() ?: false
        if (requireCharging) {
            val isCharging = attrs["isCharging"]?.toBooleanStrictOrNull() ?: false
            if (!isCharging) return false
        }
        return true
    }

    private fun matchesBattery(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val threshold = config["thresholdPercent"]?.toIntOrNull() ?: return false
        val level = attrs["levelPercent"]?.toIntOrNull() ?: return false
        val direction = config["direction"]?.uppercase() ?: "BELOW"
        val chargingOnly = config["chargingOnly"]?.toBooleanStrictOrNull() ?: false
        if (chargingOnly && attrs["isCharging"]?.toBooleanStrictOrNull() != true) {
            return false
        }
        return when (direction) {
            "ABOVE" -> level >= threshold
            else -> level <= threshold
        }
    }

    private fun matchesWifi(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val expectedSsid = config["ssid"]?.trim()?.removeSurrounding("\"") ?: return false
        val actualSsid = attrs["ssid"]?.trim()?.removeSurrounding("\"") ?: return false
        val expectedTransition = config["transition"]?.uppercase() ?: "CONNECTED"
        val actualTransition = attrs["transition"]?.uppercase() ?: "CONNECTED"
        return expectedSsid.equals(actualSsid, ignoreCase = false) && expectedTransition == actualTransition
    }

    private fun matchesCharger(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val expectedState = config["state"]?.uppercase() ?: "CONNECTED"
        val actualState = attrs["state"]?.uppercase() ?: "CONNECTED"
        return expectedState == actualState
    }

    private fun matchesBluetooth(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val expectedCategory = config["deviceCategory"]?.uppercase()
        val actualCategory = attrs["deviceCategory"]?.uppercase()
        if (expectedCategory != null && actualCategory != null && expectedCategory != actualCategory) {
            return false
        }
        val expectedName = config["deviceName"]?.trim().orEmpty()
        if (expectedName.isNotEmpty()) {
            val actualName = attrs["deviceName"]?.trim().orEmpty()
            if (!actualName.contains(expectedName, ignoreCase = true)) return false
        }
        return true
    }

    private fun matchesCalendarOrManual(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val mode = config["mode"]?.uppercase() ?: "MANUAL"
        return if (mode == "MANUAL") {
            true
        } else {
            attrs["inMeeting"]?.toBooleanStrictOrNull() ?: true
        }
    }

    private fun matchesPeriodicCheck(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val metric = config["metric"]?.uppercase() ?: return false
        val actualMetric = attrs["metric"]?.uppercase() ?: return false
        if (metric != actualMetric) return false
        val threshold = config["threshold"]?.toDoubleOrNull() ?: return false
        val observed = attrs["value"]?.toDoubleOrNull() ?: return false
        return when (metric) {
            "FREE_STORAGE_PERCENT" -> observed <= threshold
            else -> observed >= threshold
        }
    }

    private fun matchesGeofence(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val expectedTransition = config["transition"]?.uppercase() ?: "ENTER"
        val actualTransition = attrs["transition"]?.uppercase() ?: "ENTER"
        return expectedTransition == actualTransition
    }

    private fun matchesAppOpened(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val allowedPackages = config["packageNames"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        val openedPackage = attrs["packageName"]?.trim() ?: return false
        return openedPackage in allowedPackages
    }

    private fun matchesHotspotDuration(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val requiredMinutes = config["durationMinutes"]?.toIntOrNull() ?: 30
        val activeMinutes = attrs["activeMinutes"]?.toIntOrNull() ?: requiredMinutes
        return activeMinutes >= requiredMinutes
    }

    private fun matchesCallReceived(config: Map<String, String>, attrs: Map<String, String>): Boolean {
        val unknownOnly = config["unknownOnly"]?.toBooleanStrictOrNull() ?: true
        return if (unknownOnly) {
            attrs["isKnownContact"]?.toBooleanStrictOrNull() == false
        } else {
            true
        }
    }
}
