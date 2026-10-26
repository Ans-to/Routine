package app.routine.trigger.standard

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar

/**
 * Event-friendly WorkManager periodic worker evaluating battery thresholds, storage space,
 * mobile data consumption, and active calendar meetings without a persistent foreground service.
 */
@HiltWorker
class PeriodicCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dispatcher: RuleTriggerDispatcher,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        checkBattery()
        checkStorage()
        checkMobileData()
        checkCalendarMeeting()
        return Result.success()
    }

    private suspend fun checkBattery() {
        val snapshot = TriggerScheduler.readBatterySnapshot(applicationContext) ?: return
        dispatcher.dispatch(
            TriggerEvent(
                type = TriggerType.BATTERY_LEVEL,
                attributes = mapOf(
                    "levelPercent" to snapshot.first.toString(),
                    "isCharging" to snapshot.second.toString(),
                ),
            ),
        )
    }

    private suspend fun checkStorage() {
        val statFs = StatFs(Environment.getDataDirectory().path)
        val total = statFs.totalBytes.coerceAtLeast(1L)
        val freePercent = (statFs.availableBytes.toDouble() * 100.0) / total.toDouble()
        dispatcher.dispatch(
            TriggerEvent(
                type = TriggerType.PERIODIC_CHECK,
                attributes = mapOf(
                    "metric" to "FREE_STORAGE_PERCENT",
                    "value" to String.format(java.util.Locale.US, "%.2f", freePercent),
                ),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun checkMobileData() {
        val statsManager = applicationContext.getSystemService(NetworkStatsManager::class.java) ?: return
        val startOfMonth = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        val bucket = runCatching {
            statsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                null,
                startOfMonth,
                System.currentTimeMillis(),
            )
        }.getOrNull() ?: return
        val totalMb = (bucket.rxBytes + bucket.txBytes).toDouble() / (1024.0 * 1024.0)
        dispatcher.dispatch(
            TriggerEvent(
                type = TriggerType.PERIODIC_CHECK,
                attributes = mapOf(
                    "metric" to "MOBILE_DATA_MB",
                    "value" to String.format(java.util.Locale.US, "%.1f", totalMb),
                ),
            ),
        )
    }

    private suspend fun checkCalendarMeeting() {
        val hasCalendar = ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_CALENDAR,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCalendar) return

        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, now - 60_000L)
        android.content.ContentUris.appendId(builder, now + 60_000L)
        val selection = "${CalendarContract.Instances.AVAILABILITY} = ${CalendarContract.Instances.AVAILABILITY_BUSY}"
        val inBusyEvent = runCatching {
            val cursor: Cursor? = applicationContext.contentResolver.query(
                builder.build(),
                arrayOf(CalendarContract.Instances._ID),
                selection,
                null,
                null,
            )
            cursor.use { it != null && it.count > 0 }
        }.getOrDefault(false)

        if (inBusyEvent) {
            dispatcher.dispatch(
                TriggerEvent(
                    type = TriggerType.CALENDAR_OR_MANUAL,
                    attributes = mapOf("inMeeting" to "true"),
                ),
            )
        }
    }
}
