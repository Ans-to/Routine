package app.routine.core.engine

import app.routine.core.model.Trigger
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerMatcherTest {
    private val matcher = TriggerMatcher()

    @Test
    fun `matches time trigger including optional dayOfWeek and requireCharging`() {
        val trigger = Trigger(
            type = TriggerType.TIME,
            config = mapOf(
                "hour" to "7",
                "minute" to "30",
                "daysOfWeek" to "MON,TUE,WED,THU,FRI",
                "requireCharging" to "true",
            ),
        )
        assertTrue(
            matcher.matches(
                trigger,
                TriggerEvent(
                    type = TriggerType.TIME,
                    attributes = mapOf(
                        "hour" to "7",
                        "minute" to "30",
                        "dayOfWeek" to "MON",
                        "isCharging" to "true",
                    ),
                ),
            ),
        )
        assertFalse(
            matcher.matches(
                trigger,
                TriggerEvent(
                    type = TriggerType.TIME,
                    attributes = mapOf(
                        "hour" to "7",
                        "minute" to "30",
                        "dayOfWeek" to "SUN",
                        "isCharging" to "true",
                    ),
                ),
            ),
        )
        assertFalse(
            matcher.matches(
                trigger,
                TriggerEvent(
                    type = TriggerType.TIME,
                    attributes = mapOf(
                        "hour" to "7",
                        "minute" to "30",
                        "dayOfWeek" to "MON",
                        "isCharging" to "false",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `matches battery level below and above with charging guard`() {
        val below20 = Trigger(
            TriggerType.BATTERY_LEVEL,
            mapOf("thresholdPercent" to "20", "direction" to "BELOW"),
        )
        assertTrue(matcher.matches(below20, TriggerEvent(TriggerType.BATTERY_LEVEL, mapOf("levelPercent" to "18"))))
        assertFalse(matcher.matches(below20, TriggerEvent(TriggerType.BATTERY_LEVEL, mapOf("levelPercent" to "45"))))

        val above80Charging = Trigger(
            TriggerType.BATTERY_LEVEL,
            mapOf("thresholdPercent" to "80", "direction" to "ABOVE", "chargingOnly" to "true"),
        )
        assertTrue(
            matcher.matches(
                above80Charging,
                TriggerEvent(TriggerType.BATTERY_LEVEL, mapOf("levelPercent" to "82", "isCharging" to "true")),
            ),
        )
        assertFalse(
            matcher.matches(
                above80Charging,
                TriggerEvent(TriggerType.BATTERY_LEVEL, mapOf("levelPercent" to "85", "isCharging" to "false")),
            ),
        )
    }

    @Test
    fun `matches wifi ssid connect and disconnect transitions`() {
        val leaveHome = Trigger(
            TriggerType.WIFI_SSID,
            mapOf("ssid" to "HomeNet", "transition" to "DISCONNECTED"),
        )
        assertTrue(
            matcher.matches(
                leaveHome,
                TriggerEvent(TriggerType.WIFI_SSID, mapOf("ssid" to "\"HomeNet\"", "transition" to "DISCONNECTED")),
            ),
        )
        assertFalse(
            matcher.matches(
                leaveHome,
                TriggerEvent(TriggerType.WIFI_SSID, mapOf("ssid" to "HomeNet", "transition" to "CONNECTED")),
            ),
        )
    }

    @Test
    fun `matches charger, boot, bluetooth, calendar, periodic, geofence, app, hotspot, call, and alarm`() {
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.CHARGER, mapOf("state" to "CONNECTED")),
                TriggerEvent(TriggerType.CHARGER, mapOf("state" to "CONNECTED")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.BOOT_COMPLETED),
                TriggerEvent(TriggerType.BOOT_COMPLETED),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.BLUETOOTH_CONNECTED, mapOf("deviceCategory" to "CAR", "deviceName" to "Subaru")),
                TriggerEvent(
                    TriggerType.BLUETOOTH_CONNECTED,
                    mapOf("deviceCategory" to "CAR", "deviceName" to "Subaru Outback"),
                ),
            ),
        )
        assertFalse(
            matcher.matches(
                Trigger(TriggerType.BLUETOOTH_CONNECTED, mapOf("deviceCategory" to "CAR")),
                TriggerEvent(TriggerType.BLUETOOTH_CONNECTED, mapOf("deviceCategory" to "AUDIO")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.CALENDAR_OR_MANUAL, mapOf("mode" to "CALENDAR_BUSY")),
                TriggerEvent(TriggerType.CALENDAR_OR_MANUAL, mapOf("inMeeting" to "true")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.PERIODIC_CHECK, mapOf("metric" to "FREE_STORAGE_PERCENT", "threshold" to "10")),
                TriggerEvent(TriggerType.PERIODIC_CHECK, mapOf("metric" to "FREE_STORAGE_PERCENT", "value" to "7.5")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.PERIODIC_CHECK, mapOf("metric" to "MOBILE_DATA_MB", "threshold" to "5000")),
                TriggerEvent(TriggerType.PERIODIC_CHECK, mapOf("metric" to "MOBILE_DATA_MB", "value" to "5120")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(
                    TriggerType.LOCATION_GEOFENCE,
                    mapOf("latitude" to "24.86", "longitude" to "67.00", "transition" to "ENTER"),
                ),
                TriggerEvent(TriggerType.LOCATION_GEOFENCE, mapOf("transition" to "ENTER")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.APP_OPENED, mapOf("packageNames" to "org.videolan.vlc,com.google.android.youtube")),
                TriggerEvent(TriggerType.APP_OPENED, mapOf("packageName" to "org.videolan.vlc")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.HOTSPOT_DURATION, mapOf("durationMinutes" to "30")),
                TriggerEvent(TriggerType.HOTSPOT_DURATION, mapOf("activeMinutes" to "30")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.CALL_RECEIVED, mapOf("unknownOnly" to "true")),
                TriggerEvent(TriggerType.CALL_RECEIVED, mapOf("isKnownContact" to "false")),
            ),
        )
        assertFalse(
            matcher.matches(
                Trigger(TriggerType.CALL_RECEIVED, mapOf("unknownOnly" to "true")),
                TriggerEvent(TriggerType.CALL_RECEIVED, mapOf("isKnownContact" to "true")),
            ),
        )
        assertTrue(
            matcher.matches(
                Trigger(TriggerType.ALARM_DISMISSED),
                TriggerEvent(TriggerType.ALARM_DISMISSED),
            ),
        )
        assertFalse(
            matcher.matches(
                Trigger(TriggerType.ALARM_DISMISSED),
                TriggerEvent(TriggerType.BOOT_COMPLETED),
            ),
        )
    }
}
