package app.routine.privilege.adb.discovery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import app.routine.privilege.adb.AdbBridgeConstants
import app.routine.privilege.adb.AdbBridgePrivilegedEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Discovered local Wireless Debugging endpoint (`_adb-tls-pairing._tcp` or `_adb-tls-connect._tcp`).
 */
data class DiscoveredAdbEndpoint(
    val host: String,
    val port: Int,
    val serviceName: String,
)

/**
 * Discovers local Wireless Debugging mDNS ports using Android's platform [NsdManager].
 *
 * On Android 13+ (`ConnectivityFrameworkInitializerTiramisu`), simply retrieving `Context.NSD_SERVICE`
 * enforces `android.permission.INTERNET` inside `NsdService.connect()`. In the strict zero-`INTERNET`
 * `offline` flavor, [observeEndpoints] returns an empty completed [Flow] without touching `NSD_SERVICE`.
 */
@Singleton
class WirelessAdbDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun observeEndpoints(serviceType: String): Flow<DiscoveredAdbEndpoint> = callbackFlow {
        if (!hasInternetPermission(context)) {
            android.util.Log.i(TAG, "Skipping NSD discovery: INTERNET permission absent in offline flavor")
            close()
            return@callbackFlow
        }

        val nsdManager = runCatching {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.onFailure { err ->
            android.util.Log.w(TAG, "Unable to obtain NsdManager", err)
        }.getOrNull()

        if (nsdManager == null) {
            close()
            return@callbackFlow
        }

        val multicastLock = acquireMulticastLockSafely()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val hostAddr = resolved.host?.hostAddress ?: "127.0.0.1"
                                if (resolved.port > 0) {
                                    trySend(
                                        DiscoveredAdbEndpoint(
                                            host = hostAddr,
                                            port = resolved.port,
                                            serviceName = resolved.serviceName.orEmpty(),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }.onFailure { err ->
                    android.util.Log.w(TAG, "NSD resolveService failed", err)
                }
            }
        }

        val started = runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        }.onFailure { err ->
            android.util.Log.w(TAG, "NSD discoverServices failed", err)
        }.getOrDefault(false)

        if (!started) {
            releaseMulticastLockSafely(multicastLock)
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { err -> android.util.Log.d(TAG, "Stopped NSD discovery", err) }
            releaseMulticastLockSafely(multicastLock)
        }
    }

    fun observePairingEndpoints(): Flow<DiscoveredAdbEndpoint> =
        observeEndpoints(AdbBridgeConstants.PAIRING_SERVICE_TYPE)

    fun observeConnectEndpoints(): Flow<DiscoveredAdbEndpoint> =
        observeEndpoints(AdbBridgeConstants.CONNECT_SERVICE_TYPE)

    private fun acquireMulticastLockSafely(): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("routine_adb_mdns")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.onFailure { err ->
        android.util.Log.d(TAG, "MulticastLock unavailable (continuing with standard NSD)", err)
    }.getOrNull()

    private fun releaseMulticastLockSafely(lock: WifiManager.MulticastLock?) {
        if (lock == null) return
        runCatching {
            if (lock.isHeld) lock.release()
        }.onFailure { err ->
            android.util.Log.d(TAG, "MulticastLock release ignored", err)
        }
    }

    companion object {
        private const val TAG = "WirelessAdbDiscovery"

        fun hasInternetPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.INTERNET) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Posts a `RemoteInput` notification so the user can submit Android's 6-digit Wireless Debugging
 * pairing code directly from the notification shade while keeping Settings open.
 */
object PairingNotificationController {
    private const val CHANNEL_ID = "routine_adb_pairing_channel"
    private const val NOTIFICATION_ID = 9411
    const val KEY_PAIRING_CODE = "key_wireless_adb_pairing_code"
    const val ACTION_SUBMIT_PAIRING_CODE = "app.routine.privilege.adb.ACTION_SUBMIT_PAIRING_CODE"
    const val EXTRA_HOST = "extra_pairing_host"
    const val EXTRA_PORT = "extra_pairing_port"

    fun showPairingCodePrompt(context: Context, host: String, port: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Wireless Debugging Setup",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel("6-digit pairing code")
            .build()
        val submitIntent = Intent(context, PairingCodeSubmissionReceiver::class.java).apply {
            action = ACTION_SUBMIT_PAIRING_CODE
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            submitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Pair",
            pi,
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Routine Wireless Debugging Pairing")
            .setContentText("Found pairing port $port. Tap Pair to enter your 6-digit code.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(action)
            .setOngoing(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
            .onFailure { err -> android.util.Log.w("PairingNotifCtrl", "Unable to post pairing notification", err) }
    }

    fun dismiss(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIFICATION_ID) }
            .onFailure { err -> android.util.Log.d("PairingNotifCtrl", "Dismiss ignored", err) }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PairingReceiverEntryPoint {
    fun adbBridgeEngine(): AdbBridgePrivilegedEngine
}

/**
 * Receives the 6-digit pairing code from [PairingNotificationController] and pairs with `adbd`.
 */
class PairingCodeSubmissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingNotificationController.ACTION_SUBMIT_PAIRING_CODE) return
        val remoteResults = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = remoteResults.getCharSequence(PairingNotificationController.KEY_PAIRING_CODE)
            ?.toString()
            ?.trim()
            ?.takeIf { it.length == 6 && it.all(Char::isDigit) }
            ?: return
        val host = intent.getStringExtra(PairingNotificationController.EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(PairingNotificationController.EXTRA_PORT, -1)
        if (port <= 0) return

        val pending = goAsync()
        val engine = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PairingReceiverEntryPoint::class.java,
        ).adbBridgeEngine()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                engine.pairAndBootstrap(host = host, pairingPort = port, pairingCode = code)
                PairingNotificationController.dismiss(context)
            } finally {
                pending?.finish()
            }
        }
    }
}
