package app.routine.trigger.standard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lightweight event-driven foreground service keeping dynamic system broadcast receivers and
 * Wi-Fi network callbacks registered on Android 9+ ONLY while at least one enabled rule needs
 * live broadcast monitoring (`CHARGER`, `BATTERY_LEVEL`, `WIFI_SSID`, `BLUETOOTH_CONNECTED`,
 * `HOTSPOT_DURATION`, or `ALARM_DISMISSED`). Performs zero background polling.
 */
@AndroidEntryPoint
class RoutineTriggerMonitorService : Service() {
    @Inject
    lateinit var dispatcher: RuleTriggerDispatcher

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiversRegistered = false
    private var networkCallbackRegistered = false
    private var lastBatterySample: Pair<Int, Boolean>? = null

    private val dynamicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleDynamicBroadcast(context, intent)
        }
    }

    private val wifiNetworkCallback = createPlatformWifiCallback()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerDynamicReceivers()
        registerWifiNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_RULE_COUNT, 1)?.coerceAtLeast(1) ?: 1
        promoteToForeground(activeCount)
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterDynamicReceivers()
        unregisterWifiNetworkCallback()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(activeCount: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Routine Active Automations",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps event-driven triggers active without background polling"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val notification = buildMonitorNotification(activeCount)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { err ->
            android.util.Log.w(TAG, "Unable to promote monitor service to foreground", err)
        }
    }

    private fun buildMonitorNotification(activeCount: Int): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val ruleWord = if (activeCount == 1) "rule" else "rules"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Routine monitoring $activeCount active $ruleWord")
            .setContentText("Event-driven mode — zero background polling")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun registerDynamicReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(HotspotStateReceiver.ACTION_WIFI_AP_STATE_CHANGED)
            AlarmDismissedReceiver.SUPPORTED_ACTIONS.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(this, dynamicReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiversRegistered = true
    }

    private fun unregisterDynamicReceivers() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(dynamicReceiver) }
            .onFailure { err -> android.util.Log.d(TAG, "Receiver already unregistered", err) }
        receiversRegistered = false
    }

    private fun handleDynamicBroadcast(context: Context, intent: Intent) {
        when (val action = intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            -> {
                val event = ChargerTriggerReceiver.parseChargerIntent(intent) ?: return
                serviceScope.launch { dispatcher.dispatch(event) }
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                val sample = TriggerScheduler.parseBatteryIntent(intent) ?: return
                if (sample == lastBatterySample) return
                lastBatterySample = sample
                serviceScope.launch {
                    dispatcher.dispatch(
                        TriggerEvent(
                            type = TriggerType.BATTERY_LEVEL,
                            attributes = mapOf(
                                "levelPercent" to sample.first.toString(),
                                "isCharging" to sample.second.toString(),
                            ),
                        ),
                    )
                }
            }
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                val event = WifiTriggerReceiver.evaluateWifiStateChange(context, intent) ?: return
                serviceScope.launch { dispatcher.dispatch(event) }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val event = BluetoothTriggerReceiver.parseBluetoothIntent(intent) ?: return
                serviceScope.launch { dispatcher.dispatch(event) }
            }
            HotspotStateReceiver.ACTION_WIFI_AP_STATE_CHANGED -> {
                HotspotStateReceiver().onReceive(context, intent)
            }
            else -> if (action in AlarmDismissedReceiver.SUPPORTED_ACTIONS) {
                serviceScope.launch { dispatcher.dispatch(TriggerEvent(type = TriggerType.ALARM_DISMISSED)) }
            }
        }
    }

    private fun createPlatformWifiCallback(): ConnectivityManager.NetworkCallback {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    handleWifiCapabilities(capabilities)
                }

                override fun onLost(network: Network) {
                    val event = WifiTriggerReceiver.recordDisconnected(applicationContext) ?: return
                    serviceScope.launch { dispatcher.dispatch(event) }
                }
            }
        }
        return object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                handleWifiCapabilities(capabilities)
            }

            override fun onLost(network: Network) {
                val event = WifiTriggerReceiver.recordDisconnected(applicationContext) ?: return
                serviceScope.launch { dispatcher.dispatch(event) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWifiCapabilities(capabilities: NetworkCapabilities) {
        val transportSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (capabilities.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connSsid = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        val ssid = TriggerScheduler.normalizeSsid(transportSsid)
            ?: TriggerScheduler.normalizeSsid(connSsid)
            ?: return
        val event = WifiTriggerReceiver.recordConnectedSsid(applicationContext, ssid) ?: return
        serviceScope.launch { dispatcher.dispatch(event) }
    }

    private fun registerWifiNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, wifiNetworkCallback)
            networkCallbackRegistered = true
        }.onFailure { err ->
            android.util.Log.w(TAG, "Unable to register Wi-Fi NetworkCallback", err)
        }
    }

    private fun unregisterWifiNetworkCallback() {
        if (!networkCallbackRegistered) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(wifiNetworkCallback) }
            .onFailure { err -> android.util.Log.d(TAG, "Wi-Fi NetworkCallback already unregistered", err) }
        networkCallbackRegistered = false
    }

    companion object {
        private const val TAG = "RoutineMonitorService"
        const val CHANNEL_ID = "routine_monitor_channel"
        const val NOTIFICATION_ID = 7001
        const val EXTRA_ACTIVE_RULE_COUNT = "extra_active_rule_count"
    }
}
