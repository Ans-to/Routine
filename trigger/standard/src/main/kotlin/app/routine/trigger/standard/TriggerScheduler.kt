package app.routine.trigger.standard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.routine.core.data.repository.RuleRepository
import app.routine.core.engine.RuleEngine
import app.routine.core.model.ExecutionResult
import app.routine.core.model.Rule
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch

/**
 * Dispatches incoming [TriggerEvent]s to all matching enabled [Rule]s through [RuleEngine].
 */
@Singleton
class RuleTriggerDispatcher @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val ruleEngine: RuleEngine,
) {
    suspend fun dispatch(event: TriggerEvent): List<ExecutionResult> {
        val candidates = ruleRepository.getEnabledRulesForTrigger(event.type)
        return candidates.map { rule ->
            ruleEngine.execute(rule = rule, event = event)
        }
    }

    suspend fun dispatchSingleRule(ruleId: String, event: TriggerEvent): ExecutionResult? {
        val rule = ruleRepository.getRuleById(ruleId) ?: return null
        return ruleEngine.execute(rule = rule, event = event)
    }
}

/**
 * Schedules exact alarms for `TIME` triggers and manages WorkManager / Proximity registrations.
 */
@Singleton
class TriggerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleRepository: RuleRepository,
) {
    private val schedulerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    suspend fun syncAllEnabledRules() {
        val allRules = ruleRepository.getAllRules()
        allRules.forEach { rule ->
            if (rule.enabled) {
                registerDirectRuleAlarmOrGeofence(rule)
            } else {
                unregisterDirectRuleAlarmOrGeofence(rule)
            }
        }
        val enabledRules = allRules.filter { it.enabled }
        syncPeriodicWork(enabledRules)
        syncLiveMonitorService(enabledRules)
    }

    fun registerRuleTrigger(rule: Rule) {
        registerDirectRuleAlarmOrGeofence(rule)
        schedulerScope.launch {
            val enabledRules = ruleRepository.getAllRules().filter { it.enabled }
            syncPeriodicWork(enabledRules)
            syncLiveMonitorService(enabledRules)
        }
    }

    fun unregisterRuleTrigger(rule: Rule) {
        unregisterDirectRuleAlarmOrGeofence(rule)
        schedulerScope.launch {
            val enabledRules = ruleRepository.getAllRules().filter { it.enabled }
            syncPeriodicWork(enabledRules)
            syncLiveMonitorService(enabledRules)
        }
    }

    private fun registerDirectRuleAlarmOrGeofence(rule: Rule) {
        when (rule.trigger.type) {
            TriggerType.TIME -> scheduleNextExactTimeAlarm(rule)
            TriggerType.LOCATION_GEOFENCE -> registerProximityGeofence(rule)
            else -> Unit
        }
    }

    private fun unregisterDirectRuleAlarmOrGeofence(rule: Rule) {
        when (rule.trigger.type) {
            TriggerType.TIME -> cancelTimeAlarm(rule.id)
            TriggerType.LOCATION_GEOFENCE -> unregisterProximityGeofence(rule.id)
            else -> Unit
        }
    }

    fun scheduleNextExactTimeAlarm(rule: Rule, nowMs: Long = System.currentTimeMillis()): Long? {
        val hour = rule.trigger.config["hour"]?.toIntOrNull() ?: return null
        val minute = rule.trigger.config["minute"]?.toIntOrNull() ?: return null
        val triggerAtMs = computeNextAlarmEpochMs(hour, minute, nowMs)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildTimeAlarmPendingIntent(rule.id, hour, minute)

        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
        return triggerAtMs
    }

    fun cancelTimeAlarm(ruleId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildTimeAlarmPendingIntent(ruleId, 0, 0)
        alarmManager.cancel(pendingIntent)
    }

    private fun buildTimeAlarmPendingIntent(ruleId: String, hour: Int, minute: Int): PendingIntent {
        val intent = Intent(context, TimeAlarmReceiver::class.java).apply {
            action = ACTION_ROUTINE_TIME_ALARM
            putExtra(EXTRA_RULE_ID, ruleId)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
        }
        val requestCode = ruleId.hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun registerProximityGeofence(rule: Rule) {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine) return
        val lat = rule.trigger.config["latitude"]?.toDoubleOrNull() ?: return
        val lon = rule.trigger.config["longitude"]?.toDoubleOrNull() ?: return
        val radius = rule.trigger.config["radiusMeters"]?.toFloatOrNull() ?: 150f
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pendingIntent = buildGeofencePendingIntent(rule.id)
        try {
            locationManager.addProximityAlert(lat, lon, radius, -1L, pendingIntent)
        } catch (error: SecurityException) {
            android.util.Log.w(TAG, "Skipped proximity registration: location permission revoked", error)
        }
    }

    private fun unregisterProximityGeofence(ruleId: String) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pendingIntent = buildGeofencePendingIntent(ruleId)
        try {
            locationManager.removeProximityAlert(pendingIntent)
        } catch (error: SecurityException) {
            android.util.Log.w(TAG, "Skipped proximity removal: location permission revoked", error)
        }
    }

    private fun buildGeofencePendingIntent(ruleId: String): PendingIntent {
        val intent = Intent(context, GeofenceProximityReceiver::class.java).apply {
            action = ACTION_ROUTINE_GEOFENCE
            putExtra(EXTRA_RULE_ID, ruleId)
        }
        val requestCode = ("geo_$ruleId").hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun syncPeriodicWork(enabledRules: List<Rule>) {
        val needsPeriodic = enabledRules.any { rule ->
            rule.trigger.type in setOf(
                TriggerType.PERIODIC_CHECK,
                TriggerType.BATTERY_LEVEL,
                TriggerType.CALENDAR_OR_MANUAL,
            )
        }
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (needsPeriodic) {
            val request = PeriodicWorkRequestBuilder<PeriodicCheckWorker>(15, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    private fun syncLiveMonitorService(enabledRules: List<Rule>) {
        if (Build.FINGERPRINT == "robolectric") return
        val liveRules = enabledRules.filter { it.trigger.type in LIVE_MONITOR_TRIGGERS }
        val serviceIntent = Intent(context, RoutineTriggerMonitorService::class.java).apply {
            putExtra(RoutineTriggerMonitorService.EXTRA_ACTIVE_RULE_COUNT, liveRules.size)
        }
        runCatching {
            if (liveRules.isNotEmpty()) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.stopService(serviceIntent)
            }
        }.onFailure { err ->
            android.util.Log.w(TAG, "Unable to sync RoutineTriggerMonitorService", err)
        }
    }

    companion object {
        private const val TAG: String = "TriggerScheduler"
        private val LIVE_MONITOR_TRIGGERS = setOf(
            TriggerType.CHARGER,
            TriggerType.BATTERY_LEVEL,
            TriggerType.WIFI_SSID,
            TriggerType.BLUETOOTH_CONNECTED,
            TriggerType.HOTSPOT_DURATION,
            TriggerType.ALARM_DISMISSED,
        )
        const val ACTION_ROUTINE_TIME_ALARM: String = "app.routine.trigger.ACTION_TIME_ALARM"
        const val ACTION_ROUTINE_GEOFENCE: String = "app.routine.trigger.ACTION_GEOFENCE"
        const val ACTION_ROUTINE_HOTSPOT_TIMEOUT: String = "app.routine.trigger.ACTION_HOTSPOT_TIMEOUT"
        const val EXTRA_RULE_ID: String = "extra_rule_id"
        const val EXTRA_HOUR: String = "extra_hour"
        const val EXTRA_MINUTE: String = "extra_minute"
        const val PERIODIC_WORK_NAME: String = "routine_periodic_background_checks"

        fun computeNextAlarmEpochMs(hour: Int, minute: Int, nowMs: Long): Long {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMs) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            return calendar.timeInMillis
        }

        fun currentDayCode(epochMs: Long = System.currentTimeMillis()): String {
            val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
            return when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "MON"
                Calendar.TUESDAY -> "TUE"
                Calendar.WEDNESDAY -> "WED"
                Calendar.THURSDAY -> "THU"
                Calendar.FRIDAY -> "FRI"
                Calendar.SATURDAY -> "SAT"
                else -> "SUN"
            }
        }

        fun readBatterySnapshot(context: Context): Pair<Int, Boolean>? {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            return parseBatteryIntent(sticky)
        }

        fun parseBatteryIntent(intent: Intent): Pair<Int, Boolean>? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return null
            val pct = ((level * 100) / scale).coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return pct to isCharging
        }

        fun normalizeSsid(rawSsid: String?): String? = rawSsid
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() && !it.equals("<unknown ssid>", ignoreCase = true) }
            ?.let { String.format(Locale.US, "%s", it) }
    }
}
