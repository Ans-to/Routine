package app.routine.trigger.standard

import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hilt entry point used by manifest-registered [BroadcastReceiver] components.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TriggerReceiverEntryPoint {
    fun dispatcher(): RuleTriggerDispatcher
    fun scheduler(): TriggerScheduler
}

private fun BroadcastReceiver.runTriggerAsync(block: suspend () -> Unit) {
    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pendingResult?.finish()
        }
    }
}

private fun entryPoint(context: Context): TriggerReceiverEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, TriggerReceiverEntryPoint::class.java)

/**
 * Fires scheduled exact-time rules and immediately schedules the next day's alarm.
 */
class TimeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TriggerScheduler.ACTION_ROUTINE_TIME_ALARM) return
        val event = buildTimeEvent(context, intent)
        val ruleId = intent.getStringExtra(TriggerScheduler.EXTRA_RULE_ID)
        runTriggerAsync {
            val ep = entryPoint(context)
            if (ruleId != null) {
                ep.dispatcher().dispatchSingleRule(ruleId, event)
            } else {
                ep.dispatcher().dispatch(event)
            }
            ep.scheduler().syncAllEnabledRules()
        }
    }

    companion object {
        fun buildTimeEvent(context: Context, intent: Intent, nowMs: Long = System.currentTimeMillis()): TriggerEvent {
            val hour = intent.getIntExtra(TriggerScheduler.EXTRA_HOUR, 0)
            val minute = intent.getIntExtra(TriggerScheduler.EXTRA_MINUTE, 0)
            val isCharging = TriggerScheduler.readBatterySnapshot(context)?.second ?: false
            return TriggerEvent(
                type = TriggerType.TIME,
                attributes = mapOf(
                    "hour" to hour.toString(),
                    "minute" to minute.toString(),
                    "dayOfWeek" to TriggerScheduler.currentDayCode(nowMs),
                    "isCharging" to isCharging.toString(),
                ),
                timestampEpochMs = nowMs,
            )
        }
    }
}

/**
 * Handles charger connect/disconnect broadcasts and samples battery percentage thresholds.
 */
class ChargerTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chargerEvent = parseChargerIntent(intent) ?: return
        val batterySnapshot = TriggerScheduler.readBatterySnapshot(context)
        runTriggerAsync {
            val dispatcher = entryPoint(context).dispatcher()
            dispatcher.dispatch(chargerEvent)
            if (batterySnapshot != null) {
                dispatcher.dispatch(
                    TriggerEvent(
                        type = TriggerType.BATTERY_LEVEL,
                        attributes = mapOf(
                            "levelPercent" to batterySnapshot.first.toString(),
                            "isCharging" to batterySnapshot.second.toString(),
                        ),
                    ),
                )
            }
        }
    }

    companion object {
        fun parseChargerIntent(intent: Intent): TriggerEvent? = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> TriggerEvent(
                type = TriggerType.CHARGER,
                attributes = mapOf("state" to "CONNECTED"),
            )
            Intent.ACTION_POWER_DISCONNECTED -> TriggerEvent(
                type = TriggerType.CHARGER,
                attributes = mapOf("state" to "DISCONNECTED"),
            )
            else -> null
        }
    }
}

/**
 * Tracks Wi-Fi SSID connection and disconnection transitions without polling.
 */
class WifiTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
        val event = evaluateWifiStateChange(context, intent) ?: return
        runTriggerAsync {
            entryPoint(context).dispatcher().dispatch(event)
        }
    }

    companion object {
        private const val PREFS_WIFI = "routine_wifi_state_tracker"
        private const val KEY_LAST_SSID = "last_connected_ssid"

        @Suppress("DEPRECATION")
        fun evaluateWifiStateChange(context: Context, intent: Intent): TriggerEvent? {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_WIFI, Context.MODE_PRIVATE)
            val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
            val isConnected = networkInfo?.isConnected == true

            if (isConnected) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ssid = intent.getStringExtra("ssid")?.let { TriggerScheduler.normalizeSsid(it) }
                    ?: TriggerScheduler.normalizeSsid(wifiManager?.connectionInfo?.ssid)
                    ?: return null
                prefs.edit().putString(KEY_LAST_SSID, ssid).apply()
                return TriggerEvent(
                    type = TriggerType.WIFI_SSID,
                    attributes = mapOf("ssid" to ssid, "transition" to "CONNECTED"),
                )
            } else if (networkInfo?.state == NetworkInfo.State.DISCONNECTED) {
                val previousSsid = prefs.getString(KEY_LAST_SSID, null) ?: return null
                prefs.edit().remove(KEY_LAST_SSID).apply()
                return TriggerEvent(
                    type = TriggerType.WIFI_SSID,
                    attributes = mapOf("ssid" to previousSsid, "transition" to "DISCONNECTED"),
                )
            }
            return null
        }
    }
}

/**
 * Re-arms exact alarms and background workers after reboot and dispatches `BOOT_COMPLETED` rules.
 */
class BootTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runTriggerAsync {
            val ep = entryPoint(context)
            ep.scheduler().syncAllEnabledRules()
            ep.dispatcher().dispatch(TriggerEvent(type = TriggerType.BOOT_COMPLETED))
        }
    }
}

/**
 * Handles Bluetooth device connection events (Car hands-free vs Audio headphones).
 */
class BluetoothTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val event = parseBluetoothIntent(intent) ?: return
        runTriggerAsync {
            entryPoint(context).dispatcher().dispatch(event)
        }
    }

    companion object {
        @Suppress("DEPRECATION", "MissingPermission")
        fun parseBluetoothIntent(intent: Intent): TriggerEvent? {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return null
            val explicitCategory = intent.getStringExtra("routine_bt_category")
            val explicitName = intent.getStringExtra("routine_bt_name")
            if (explicitCategory != null) {
                return TriggerEvent(
                    type = TriggerType.BLUETOOTH_CONNECTED,
                    attributes = mapOf(
                        "deviceCategory" to explicitCategory,
                        "deviceName" to explicitName.orEmpty(),
                    ),
                )
            }
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return null
            val deviceName = runCatching { device.name.orEmpty() }.getOrDefault("")
            val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            val deviceClass = runCatching { device.bluetoothClass?.deviceClass }.getOrNull()
            val isCar = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                deviceName.contains("car", ignoreCase = true) ||
                deviceName.contains("handsfree", ignoreCase = true)
            val category = when {
                isCar -> "CAR"
                majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO -> "AUDIO"
                else -> "AUDIO"
            }
            return TriggerEvent(
                type = TriggerType.BLUETOOTH_CONNECTED,
                attributes = mapOf(
                    "deviceCategory" to category,
                    "deviceName" to deviceName,
                ),
            )
        }
    }
}

/**
 * Handles LocationManager proximity alerts for geofence triggers.
 */
class GeofenceProximityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TriggerScheduler.ACTION_ROUTINE_GEOFENCE) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
        val transition = if (entering) "ENTER" else "EXIT"
        val ruleId = intent.getStringExtra(TriggerScheduler.EXTRA_RULE_ID)
        val event = TriggerEvent(
            type = TriggerType.LOCATION_GEOFENCE,
            attributes = mapOf("transition" to transition),
        )
        runTriggerAsync {
            val dispatcher = entryPoint(context).dispatcher()
            if (ruleId != null) {
                dispatcher.dispatchSingleRule(ruleId, event)
            } else {
                dispatcher.dispatch(event)
            }
        }
    }
}

/**
 * Schedules or cancels the hotspot auto-off timer when Android Wi-Fi tethering state changes.
 */
class HotspotStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIFI_AP_STATE_CHANGED) return
        val apState = intent.getIntExtra("wifi_state", -1)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val timeoutIntent = Intent(context, HotspotTimeoutReceiver::class.java).apply {
            action = TriggerScheduler.ACTION_ROUTINE_HOTSPOT_TIMEOUT
        }
        val pi = PendingIntent.getBroadcast(
            context,
            HOTSPOT_REQUEST_CODE,
            timeoutIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (apState == WIFI_AP_STATE_ENABLED) {
            val triggerAt = System.currentTimeMillis() + DEFAULT_HOTSPOT_TIMEOUT_MS
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else if (apState == WIFI_AP_STATE_DISABLED) {
            alarmManager.cancel(pi)
        }
    }

    companion object {
        const val ACTION_WIFI_AP_STATE_CHANGED: String = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val WIFI_AP_STATE_DISABLED: Int = 11
        const val WIFI_AP_STATE_ENABLED: Int = 13
        private const val HOTSPOT_REQUEST_CODE: Int = 7013
        private const val DEFAULT_HOTSPOT_TIMEOUT_MS: Long = 30L * 60L * 1_000L
    }
}

/**
 * Fires `HOTSPOT_DURATION` rules when the hotspot has remained active for 30 minutes.
 */
class HotspotTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TriggerScheduler.ACTION_ROUTINE_HOTSPOT_TIMEOUT) return
        runTriggerAsync {
            entryPoint(context).dispatcher().dispatch(
                TriggerEvent(
                    type = TriggerType.HOTSPOT_DURATION,
                    attributes = mapOf("activeMinutes" to "30"),
                ),
            )
        }
    }
}

/**
 * Dispatches `ALARM_DISMISSED` rules when the user dismisses a clock alarm.
 */
class AlarmDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        runTriggerAsync {
            entryPoint(context).dispatcher().dispatch(TriggerEvent(type = TriggerType.ALARM_DISMISSED))
        }
    }

    companion object {
        val SUPPORTED_ACTIONS: Set<String> = setOf(
            "android.intent.action.ALARM_DISMISS",
            "com.android.deskclock.ALARM_DISMISS",
        )
    }
}
