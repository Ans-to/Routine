package app.routine.privilege.adb.discovery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import app.routine.privilege.adb.AdbBridgeConstants
import app.routine.privilege.adb.AdbBridgePrivilegedEngine
import app.routine.privilege.adb.transport.AdbTransportClient
import app.routine.privilege.api.PairingEndpointInfo
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.WirelessPairingPhase
import app.routine.privilege.api.WirelessPairingStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Discovered local Wireless Debugging endpoint (`_adb-tls-pairing._tcp` or `_adb-tls-connect._tcp`).
 */
data class DiscoveredAdbEndpoint(
    val host: String,
    val port: Int,
    val serviceName: String,
)

/**
 * Shared process-wide state tracker for single-device Wireless Debugging pairing sessions.
 */
object WirelessPairingSessionTracker {
    private val mutableState = MutableStateFlow(WirelessPairingStatus())
    val stateFlow: StateFlow<WirelessPairingStatus> = mutableState.asStateFlow()
    val latestConnectEndpoint: AtomicReference<DiscoveredAdbEndpoint?> = AtomicReference(null)

    fun update(status: WirelessPairingStatus) {
        mutableState.value = status
    }

    fun resetIdle() {
        mutableState.value = WirelessPairingStatus(phase = WirelessPairingPhase.IDLE)
    }
}

/**
 * Discovers local Wireless Debugging mDNS ports using Android's platform [NsdManager], serializing
 * `resolveService` calls to avoid `FAILURE_ALREADY_ACTIVE` drops and filtering strictly to this
 * device's own network interfaces.
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
        val resolveQueue = Channel<NsdServiceInfo>(Channel.BUFFERED)
        val workerJob = launch(Dispatchers.IO) {
            for (candidate in resolveQueue) {
                val resolved = resolveWithRetry(nsdManager, candidate) ?: continue
                @Suppress("DEPRECATION")
                val localHost = selectDeviceLocalHost(resolved.host) ?: continue
                if (resolved.port > 0) {
                    trySend(
                        DiscoveredAdbEndpoint(
                            host = localHost,
                            port = resolved.port,
                            serviceName = resolved.serviceName.orEmpty(),
                        ),
                    )
                }
            }
        }

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
                resolveQueue.trySend(serviceInfo)
            }
        }

        val started = runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        }.onFailure { err ->
            android.util.Log.w(TAG, "NSD discoverServices failed", err)
        }.getOrDefault(false)

        if (!started) {
            resolveQueue.close()
            workerJob.cancel()
            releaseMulticastLockSafely(multicastLock)
            close()
            return@callbackFlow
        }

        awaitClose {
            resolveQueue.close()
            workerJob.cancel()
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { err -> android.util.Log.d(TAG, "Stopped NSD discovery", err) }
            releaseMulticastLockSafely(multicastLock)
        }
    }

    fun observePairingEndpoints(): Flow<DiscoveredAdbEndpoint> =
        observeEndpoints(AdbBridgeConstants.PAIRING_SERVICE_TYPE)

    fun observeConnectEndpoints(): Flow<DiscoveredAdbEndpoint> =
        observeEndpoints(AdbBridgeConstants.CONNECT_SERVICE_TYPE)

    private suspend fun resolveWithRetry(nsdManager: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        repeat(3) { attempt ->
            val outcome = withTimeoutOrNull(2_500L) {
                resolveOnce(nsdManager, info)
            }
            when (outcome) {
                is ResolveOutcome.Resolved -> return outcome.info
                is ResolveOutcome.Failed -> {
                    if (outcome.errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 2) {
                        delay(180L)
                    } else {
                        return null
                    }
                }
                null -> return null
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveOnce(nsdManager: NsdManager, info: NsdServiceInfo): ResolveOutcome =
        suspendCancellableCoroutine { cont ->
            val ran = runCatching {
                nsdManager.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(ResolveOutcome.Failed(errorCode))
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(ResolveOutcome.Resolved(resolved))
                        }
                    },
                )
            }
            ran.onFailure { err ->
                android.util.Log.w(TAG, "NSD resolveService failed", err)
                if (cont.isActive) cont.resume(ResolveOutcome.Failed(-1))
            }
        }

    private sealed interface ResolveOutcome {
        data class Resolved(val info: NsdServiceInfo) : ResolveOutcome
        data class Failed(val errorCode: Int) : ResolveOutcome
    }

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

        internal fun selectDeviceLocalHost(resolvedHost: InetAddress?): String? {
            if (resolvedHost == null) return "127.0.0.1"
            val localAddresses = AdbTransportClient.enumerateLocalInterfaceAddresses()
            if (localAddresses.isEmpty()) {
                return if (resolvedHost is Inet4Address) resolvedHost.hostAddress ?: "127.0.0.1" else "127.0.0.1"
            }
            val belongsToThisDevice = resolvedHost.isLoopbackAddress ||
                localAddresses.any { local -> local.address.contentEquals(resolvedHost.address) }
            if (!belongsToThisDevice) {
                return null
            }
            if (resolvedHost is Inet4Address) {
                return resolvedHost.hostAddress ?: "127.0.0.1"
            }
            val localIpv4 = localAddresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            return localIpv4?.hostAddress ?: "127.0.0.1"
        }
    }
}

/**
 * Builds and posts Wireless Debugging notifications, including inline `RemoteInput` code entry
 * so the user never has to close Android Settings' pairing dialog on a single phone.
 */
object PairingNotificationController {
    private const val CHANNEL_ID = "routine_adb_pairing_channel"
    const val NOTIFICATION_ID: Int = 9411
    private const val RESULT_NOTIFICATION_ID: Int = 9412
    const val KEY_PAIRING_CODE: String = "key_wireless_adb_pairing_code"
    const val ACTION_SUBMIT_PAIRING_CODE: String = "app.routine.privilege.adb.ACTION_SUBMIT_PAIRING_CODE"
    const val ACTION_CANCEL_PAIRING_SCAN: String = "app.routine.privilege.adb.ACTION_CANCEL_PAIRING_SCAN"
    const val EXTRA_HOST: String = "extra_pairing_host"
    const val EXTRA_PORT: String = "extra_pairing_port"

    fun buildScanningNotification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Wireless Debugging: Ready to Pair")
            .setContentText("In Wireless debugging, tap 'Pair device with pairing code'.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Open Developer Options → Wireless debugging → Pair device with pairing code. " +
                        "Routine will pop up a 6-digit code box right here.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(buildCancelAction(context))
            .setOngoing(true)
            .build()
    }

    fun buildPairingCodeNotification(
        context: Context,
        host: String,
        port: Int,
        subtitleOverride: String? = null,
    ): Notification {
        ensureChannel(context)
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel("Enter 6-digit pairing code")
            .build()
        val submitIntent = Intent(context, PairingCodeSubmissionReceiver::class.java).apply {
            action = ACTION_SUBMIT_PAIRING_CODE
            setPackage(context.packageName)
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
        }
        val submitPi = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            submitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val pairAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Enter 6-Digit Code",
            submitPi,
        ).addRemoteInput(remoteInput).build()

        val text = subtitleOverride ?: "Tap 'Enter 6-Digit Code' below without closing Settings (port $port)."
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pairing Port Found ($port)")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(pairAction)
            .addAction(buildCancelAction(context))
            .setOngoing(true)
            .build()
    }

    fun showPairingCodePrompt(context: Context, host: String, port: Int, subtitleOverride: String? = null) {
        notifySafely(context, NOTIFICATION_ID, buildPairingCodeNotification(context, host, port, subtitleOverride))
    }

    fun showPairingProgress(context: Context, port: Int) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pairing Wireless Debugging ($port)…")
            .setContentText("Verifying 6-digit code and launching internal shell server…")
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
        notifySafely(context, NOTIFICATION_ID, notification)
    }

    fun showFinalOutcome(context: Context, succeeded: Boolean, detail: String) {
        ensureChannel(context)
        dismiss(context)
        val title = if (succeeded) "Wireless Debugging Connected" else "Wireless Debugging Pairing Failed"
        val icon = if (succeeded) {
            android.R.drawable.stat_sys_data_bluetooth
        } else {
            android.R.drawable.stat_notify_error
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notifySafely(context, RESULT_NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIFICATION_ID) }
            .onFailure { err -> android.util.Log.d("PairingNotifCtrl", "Dismiss ignored", err) }
    }

    private fun buildCancelAction(context: Context): NotificationCompat.Action {
        val cancelIntent = Intent(context, PairingCodeSubmissionReceiver::class.java).apply {
            action = ACTION_CANCEL_PAIRING_SCAN
            setPackage(context.packageName)
        }
        val cancelPi = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop Scan",
            cancelPi,
        ).build()
    }

    private fun ensureChannel(context: Context) {
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
    }

    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(id, notification) }
            .onFailure { err -> android.util.Log.w("PairingNotifCtrl", "Unable to post pairing notification", err) }
    }
}

/**
 * Foreground service keeping mDNS pairing + connect discovery alive while the user navigates into
 * Android Settings -> Developer Options -> Wireless Debugging on a single phone.
 */
class WirelessAdbPairingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SCAN) {
            stopScanInternal(resetState = true)
            return START_NOT_STICKY
        }

        if (!WirelessAdbDiscovery.hasInternetPermission(this)) {
            WirelessPairingSessionTracker.update(
                WirelessPairingStatus(
                    phase = WirelessPairingPhase.FAILED,
                    message = "Option A requires the wirelessAdb APK flavor.",
                ),
            )
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground(PairingNotificationController.buildScanningNotification(this))
        if (scanJob?.isActive != true) {
            startDiscoveryCollectors()
        }
        return START_NOT_STICKY
    }

    private fun startDiscoveryCollectors() {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            PairingReceiverEntryPoint::class.java,
        )
        val discovery = entryPoint.wirelessAdbDiscovery()
        val engine = entryPoint.adbBridgeEngine()
        val probedConnectPorts = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

        WirelessPairingSessionTracker.update(
            WirelessPairingStatus(
                phase = WirelessPairingPhase.SCANNING,
                message = "Waiting for Wireless Debugging pairing dialog…",
            ),
        )

        scanJob = serviceScope.launch {
            launch {
                try {
                    discovery.observeConnectEndpoints().collect { ep ->
                        WirelessPairingSessionTracker.latestConnectEndpoint.set(ep)
                        if (engine.hasPersistedIdentity() && probedConnectPorts.add(ep.port)) {
                            val currentPhase = WirelessPairingSessionTracker.stateFlow.value.phase
                            if (currentPhase == WirelessPairingPhase.SCANNING) {
                                val autoRes = engine.connectAndLaunchShellServer(
                                    host = ep.host,
                                    connectPort = ep.port,
                                    abortOnUnauthorised = true,
                                )
                                if (autoRes is PrivilegeOperationResult.Succeeded) {
                                    WirelessPairingSessionTracker.update(
                                        WirelessPairingStatus(
                                            phase = WirelessPairingPhase.CONNECTED,
                                            message = autoRes.detail,
                                        ),
                                    )
                                    PairingNotificationController.showFinalOutcome(
                                        context = this@WirelessAdbPairingService,
                                        succeeded = true,
                                        detail = autoRes.detail,
                                    )
                                    stopScanInternal(resetState = false)
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    android.util.Log.d(TAG, "Connect endpoint collector ended", err)
                }
            }

            launch {
                try {
                    discovery.observePairingEndpoints().collect { ep ->
                        val info = PairingEndpointInfo(host = ep.host, port = ep.port, label = ep.serviceName)
                        WirelessPairingSessionTracker.update(
                            WirelessPairingStatus(
                                phase = WirelessPairingPhase.PORT_FOUND,
                                endpoint = info,
                                message = "Pairing port ${ep.port} found! Enter the 6-digit code in the notification.",
                            ),
                        )
                        PairingNotificationController.showPairingCodePrompt(
                            context = this@WirelessAdbPairingService,
                            host = ep.host,
                            port = ep.port,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    android.util.Log.w(TAG, "Pairing endpoint collector failed", err)
                    WirelessPairingSessionTracker.update(
                        WirelessPairingStatus(
                            phase = WirelessPairingPhase.FAILED,
                            message = "Port scan failed: ${err.message ?: "NSD error"}",
                        ),
                    )
                }
            }
        }
    }

    private fun promoteToForeground(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    PairingNotificationController.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(PairingNotificationController.NOTIFICATION_ID, notification)
            }
        }.onFailure { err ->
            android.util.Log.w(TAG, "Unable to promote WirelessAdbPairingService to foreground", err)
        }
    }

    private fun stopScanInternal(resetState: Boolean) {
        scanJob?.cancel()
        scanJob = null
        if (resetState) {
            WirelessPairingSessionTracker.resetIdle()
            PairingNotificationController.dismiss(this)
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { err -> android.util.Log.d(TAG, "stopForeground ignored", err) }
        stopSelf()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WirelessAdbPairSvc"
        private const val ACTION_START_SCAN = "app.routine.privilege.adb.ACTION_START_PAIRING_SCAN"
        private const val ACTION_STOP_SCAN = "app.routine.privilege.adb.ACTION_STOP_PAIRING_SCAN"

        fun startScan(context: Context) {
            if (!WirelessAdbDiscovery.hasInternetPermission(context)) {
                WirelessPairingSessionTracker.update(
                    WirelessPairingStatus(
                        phase = WirelessPairingPhase.FAILED,
                        message = "Option A requires the wirelessAdb APK flavor.",
                    ),
                )
                return
            }
            WirelessPairingSessionTracker.update(
                WirelessPairingStatus(
                    phase = WirelessPairingPhase.SCANNING,
                    message = "Waiting for Wireless Debugging pairing dialog…",
                ),
            )
            val intent = Intent(context, WirelessAdbPairingService::class.java).apply {
                action = ACTION_START_SCAN
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { err ->
                android.util.Log.w(TAG, "Failed to start WirelessAdbPairingService", err)
                PairingNotificationController.notifySafelyScanFallback(context)
            }
        }

        fun stopScan(context: Context, resetState: Boolean = true) {
            if (resetState) {
                WirelessPairingSessionTracker.resetIdle()
                PairingNotificationController.dismiss(context)
            }
            val intent = Intent(context, WirelessAdbPairingService::class.java)
            runCatching { context.stopService(intent) }
                .onFailure { err -> android.util.Log.d(TAG, "stopService ignored", err) }
        }

        private fun PairingNotificationController.notifySafelyScanFallback(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(NOTIFICATION_ID, buildScanningNotification(context)) }
                .onFailure { err -> android.util.Log.d(TAG, "Fallback scan notification ignored", err) }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PairingReceiverEntryPoint {
    fun adbBridgeEngine(): AdbBridgePrivilegedEngine
    fun wirelessAdbDiscovery(): WirelessAdbDiscovery
}

/**
 * Receives the 6-digit pairing code or cancel action from [PairingNotificationController].
 */
class PairingCodeSubmissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PairingNotificationController.ACTION_CANCEL_PAIRING_SCAN -> {
                WirelessAdbPairingService.stopScan(context, resetState = true)
            }
            PairingNotificationController.ACTION_SUBMIT_PAIRING_CODE -> handleCodeSubmission(context, intent)
        }
    }

    private fun handleCodeSubmission(context: Context, intent: Intent) {
        val host = intent.getStringExtra(PairingNotificationController.EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(PairingNotificationController.EXTRA_PORT, -1)
        if (port <= 0) return

        val remoteResults = RemoteInput.getResultsFromIntent(intent)
        val digits = remoteResults?.getCharSequence(PairingNotificationController.KEY_PAIRING_CODE)
            ?.toString()
            ?.filter(Char::isDigit)
            .orEmpty()

        if (digits.length != 6) {
            PairingNotificationController.showPairingCodePrompt(
                context = context,
                host = host,
                port = port,
                subtitleOverride = "Please enter all 6 digits of the Wireless Debugging pairing code (port $port).",
            )
            return
        }

        PairingNotificationController.showPairingProgress(context, port)
        WirelessPairingSessionTracker.update(
            WirelessPairingStatus(
                phase = WirelessPairingPhase.PAIRING,
                endpoint = PairingEndpointInfo(host = host, port = port, label = "Wireless Debugging"),
                message = "Pairing with port $port and starting internal shell…",
            ),
        )

        val pending = goAsync()
        val appContext = context.applicationContext
        val engine = EntryPointAccessors.fromApplication(
            appContext,
            PairingReceiverEntryPoint::class.java,
        ).adbBridgeEngine()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val hinted = WirelessPairingSessionTracker.latestConnectEndpoint.get()
                val res = engine.pairAndBootstrap(
                    host = host,
                    pairingPort = port,
                    pairingCode = digits,
                    hintedConnectEndpoint = hinted,
                )
                when (res) {
                    is PrivilegeOperationResult.Succeeded -> {
                        WirelessPairingSessionTracker.update(
                            WirelessPairingStatus(
                                phase = WirelessPairingPhase.CONNECTED,
                                message = res.detail,
                            ),
                        )
                        WirelessAdbPairingService.stopScan(appContext, resetState = false)
                        PairingNotificationController.showFinalOutcome(
                            context = appContext,
                            succeeded = true,
                            detail = res.detail,
                        )
                    }
                    is PrivilegeOperationResult.Failed -> {
                        val retryMsg = "${res.reason} Reopen 'Pair device with pairing code' to try a fresh code."
                        WirelessPairingSessionTracker.update(
                            WirelessPairingStatus(
                                phase = WirelessPairingPhase.FAILED,
                                message = retryMsg,
                            ),
                        )
                        PairingNotificationController.showFinalOutcome(appContext, succeeded = false, detail = retryMsg)
                    }
                }
            } finally {
                pending?.finish()
            }
        }
    }
}
