package app.routine.di

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import app.routine.core.model.SystemPermission
import app.routine.core.permissions.PermissionSnapshot
import app.routine.core.permissions.PermissionStateProvider
import app.routine.privilege.api.AdbBridgeTier
import app.routine.privilege.api.OneTimeGrantTier
import app.routine.privilege.api.PrivilegedEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Queries the Android OS and built-in privilege tiers for current runtime, special-access, and privileged grant states.
 */
@Singleton
class AndroidPermissionStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @AdbBridgeTier private val adbBridgeEngine: PrivilegedEngine,
    @OneTimeGrantTier private val oneTimeGrantEngine: PrivilegedEngine,
) : PermissionStateProvider {
    private val mutableSnapshot = MutableStateFlow(computeBaseSnapshot(adbBridgeReady = false, oneTimeReady = false))
    override val snapshotFlow: StateFlow<PermissionSnapshot> = mutableSnapshot.asStateFlow()

    override suspend fun refresh(): PermissionSnapshot {
        val bridgeReady = adbBridgeEngine.checkAvailability().isAvailable()
        val grantReady = oneTimeGrantEngine.checkAvailability().isAvailable()
        val next = computeBaseSnapshot(adbBridgeReady = bridgeReady, oneTimeReady = grantReady)
        mutableSnapshot.value = next
        return next
    }

    private fun computeBaseSnapshot(adbBridgeReady: Boolean, oneTimeReady: Boolean): PermissionSnapshot {
        val granted = buildSet {
            if (isPostNotificationsGranted()) add(SystemPermission.POST_NOTIFICATIONS)
            if (isExactAlarmGranted()) add(SystemPermission.SCHEDULE_EXACT_ALARM)
            if (isBatteryOptimizationIgnored()) add(SystemPermission.IGNORE_BATTERY_OPTIMIZATIONS)
            if (isNotificationPolicyGranted()) add(SystemPermission.NOTIFICATION_POLICY_ACCESS)
            if (Settings.System.canWrite(context)) add(SystemPermission.WRITE_SYSTEM_SETTINGS)
            if (hasRuntime(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(SystemPermission.ACCESS_FINE_LOCATION)
            }
            if (isBackgroundLocationGranted()) add(SystemPermission.ACCESS_BACKGROUND_LOCATION)
            if (isBluetoothConnectGranted()) add(SystemPermission.BLUETOOTH_CONNECT)
            if (hasRuntime(android.Manifest.permission.READ_CALENDAR)) add(SystemPermission.READ_CALENDAR)
            if (hasRuntime(android.Manifest.permission.READ_CONTACTS)) add(SystemPermission.READ_CONTACTS)
            if (isUsageStatsGranted()) add(SystemPermission.PACKAGE_USAGE_STATS)
            if (hasRuntime(android.Manifest.permission.SEND_SMS)) add(SystemPermission.SEND_SMS)
            if (isAccessibilityServiceEnabled()) add(SystemPermission.ACCESSIBILITY_SERVICE)
            if (isCallScreeningRoleHeld()) add(SystemPermission.CALL_SCREENING_ROLE)
            if (adbBridgeReady) add(SystemPermission.ADB_SHELL_BRIDGE)
            if (oneTimeReady || hasRuntime("android.permission.WRITE_SECURE_SETTINGS")) {
                add(SystemPermission.WRITE_SECURE_SETTINGS)
            }
        }
        val secureGranted = SystemPermission.WRITE_SECURE_SETTINGS in granted
        val accessibilityEnabled = SystemPermission.ACCESSIBILITY_SERVICE in granted
        return PermissionSnapshot(
            grantedPermissions = granted,
            isAdbBridgeReady = adbBridgeReady,
            isOneTimeGrantReady = secureGranted,
            isAccessibilityEnabled = accessibilityEnabled,
        )
    }

    private fun hasRuntime(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isPostNotificationsGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        hasRuntime(android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.areNotificationsEnabled()
    }

    private fun isExactAlarmGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.canScheduleExactAlarms()
    } else {
        true
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isNotificationPolicyGranted(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isBackgroundLocationGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        hasRuntime(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        hasRuntime(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun isBluetoothConnectGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasRuntime(android.Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        true
    }

    @Suppress("DEPRECATION")
    private fun isUsageStatsGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { info ->
            info.resolveInfo?.serviceInfo?.packageName == context.packageName
        }
    }

    private fun isCallScreeningRoleHeld(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(RoleManager::class.java)
        rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
    } else {
        false
    }
}
