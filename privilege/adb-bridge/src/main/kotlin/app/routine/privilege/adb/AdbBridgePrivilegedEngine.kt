package app.routine.privilege.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.routine.privilege.adb.binder.LoopbackRoutineShellClient
import app.routine.privilege.adb.binder.LoopbackShellCrypto
import app.routine.privilege.adb.binder.RoutineShellBinderProvider
import app.routine.privilege.adb.binder.RoutineShellServerMain
import app.routine.privilege.adb.binder.RoutineShellService
import app.routine.privilege.adb.crypto.AdbRsaKeyManager
import app.routine.privilege.adb.discovery.DiscoveredAdbEndpoint
import app.routine.privilege.adb.discovery.WirelessAdbDiscovery
import app.routine.privilege.adb.transport.AdbTransportClient
import app.routine.privilege.api.AdbBridgeTier
import app.routine.privilege.api.PrivilegeAvailability
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.PrivilegedEngine
import app.routine.privilege.api.SecureSettingNamespace
import app.routine.privilege.api.ShellCommandResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Option A implementation of [PrivilegedEngine]: Routine's 100% self-contained Wireless Debugging
 * ADB client and internal `app_process` shell server.
 *
 * Self-grants `WRITE_SECURE_SETTINGS` upon initial connection and supports automatic post-reboot
 * reconnection by re-enabling `adb_wifi_enabled` and launching [RoutineShellServerMain].
 */
@Singleton
class AdbBridgePrivilegedEngine(
    private val context: Context,
    private val discovery: WirelessAdbDiscovery?,
    private val shellServiceProvider: () -> RoutineShellService?,
    internal val customTransportClient: AdbTransportClient? = null,
) : PrivilegedEngine {
    private val keyManager: AdbRsaKeyManager by lazy { AdbRsaKeyManager(context) }
    private val transportClient: AdbTransportClient by lazy { customTransportClient ?: AdbTransportClient(keyManager) }

    @Inject
    constructor(
        @ApplicationContext context: Context,
        discovery: WirelessAdbDiscovery,
    ) : this(
        context = context,
        discovery = discovery,
        shellServiceProvider = {
            RoutineShellBinderProvider.BinderHolder.activeProxy.get()?.takeIf { it.isBinderAlive() }
                ?: LoopbackShellCrypto.resolveActiveOrRestored(context)
        },
    )

    fun hasPersistedIdentity(): Boolean = keyManager.hasPersistedIdentity()

    override suspend fun checkAvailability(): PrivilegeAvailability = withContext(Dispatchers.IO) {
        val activeService = shellServiceProvider() ?: return@withContext PrivilegeAvailability.NotConfigured(
            reason = "Internal ADB Bridge shell server is not currently connected.",
            setupHint = "Open Settings > Privileged Tier Setup to pair Wireless Debugging.",
        )
        runCatching {
            PrivilegeAvailability.AdbBridgeConnected(uid = activeService.getServerUid())
        }.getOrElse { error ->
            android.util.Log.w(TAG, "ADB Bridge shell check failed", error)
            PrivilegeAvailability.NotConfigured(reason = "Shell daemon disconnected (${error.message}).")
        }
    }

    override suspend fun executeShellCommand(command: String, timeoutMs: Long): ShellCommandResult =
        withContext(Dispatchers.IO) {
            if (command.isBlank()) {
                return@withContext ShellCommandResult.Failed("Shell command must not be blank.")
            }
            val service = shellServiceProvider()
                ?: return@withContext ShellCommandResult.Failed("Wireless ADB Bridge is not connected.")
            runCatching {
                service.executeCommand(command, timeoutMs)
            }.getOrElse { error ->
                android.util.Log.w(TAG, "Shell daemon command failed", error)
                ShellCommandResult.Failed("ADB Bridge execution error: ${error.message}")
            }
        }

    override suspend fun grantPermission(packageName: String, permission: String): PrivilegeOperationResult {
        if (!PrivilegedEngine.isSafeIdentifier(packageName) || !PrivilegedEngine.isSafeIdentifier(permission)) {
            return PrivilegeOperationResult.Failed("Invalid packageName or permission identifier.")
        }
        return when (val res = executeShellCommand("pm grant $packageName $permission")) {
            is ShellCommandResult.Succeeded -> PrivilegeOperationResult.Succeeded("Granted $permission to $packageName")
            is ShellCommandResult.Failed -> PrivilegeOperationResult.Failed(res.reason)
        }
    }

    override suspend fun writeSecureSetting(
        namespace: SecureSettingNamespace,
        key: String,
        value: String,
    ): PrivilegeOperationResult {
        if (!PrivilegedEngine.isSafeIdentifier(key)) {
            return PrivilegeOperationResult.Failed("Invalid setting key '$key'.")
        }
        val nsArg = namespace.name.lowercase()
        val escapedValue = "'" + value.replace("'", "'\\''") + "'"
        return when (val res = executeShellCommand("settings put $nsArg $key $escapedValue")) {
            is ShellCommandResult.Succeeded -> PrivilegeOperationResult.Succeeded("Wrote $nsArg:$key via ADB Bridge")
            is ShellCommandResult.Failed -> PrivilegeOperationResult.Failed(res.reason)
        }
    }

    /**
     * Pairs with Wireless Debugging on [host]:[pairingPort] using [pairingCode], connects to `_adb-tls-connect._tcp`,
     * starts [RoutineShellServerMain] from Routine's own APK, and self-grants `WRITE_SECURE_SETTINGS`.
     */
    suspend fun pairAndBootstrap(
        host: String,
        pairingPort: Int,
        pairingCode: String,
        hintedConnectEndpoint: DiscoveredAdbEndpoint? = null,
    ): PrivilegeOperationResult = withContext(Dispatchers.IO) {
        if (!WirelessAdbDiscovery.hasInternetPermission(context)) {
            return@withContext PrivilegeOperationResult.Failed(
                "Wireless Debugging requires the wirelessAdb APK flavor (loopback socket permission).",
            )
        }
        runCatching {
            transportClient.pairWithLocalDaemon(host, pairingPort, pairingCode)
            if (hintedConnectEndpoint != null) {
                val hintedResult = connectAndLaunchShellServer(hintedConnectEndpoint.host, hintedConnectEndpoint.port)
                if (hintedResult is PrivilegeOperationResult.Succeeded) {
                    return@withContext hintedResult
                }
            }
            val connectEndpoint = withTimeoutOrNull(15_000L) {
                discovery?.observeConnectEndpoints()?.first()
            } ?: return@withContext PrivilegeOperationResult.Failed(
                "Paired successfully, but timed out waiting for Wireless Debugging connect port.",
            )
            connectAndLaunchShellServer(connectEndpoint.host, connectEndpoint.port)
        }.getOrElse { error ->
            android.util.Log.w(TAG, "pairAndBootstrap failed", error)
            PrivilegeOperationResult.Failed("Wireless Debugging pairing failed: ${error.message}")
        }
    }

    /**
     * Connects to an already-paired Wireless Debugging port, launches Routine's internal `app_process`
     * shell daemon from Routine's APK, and self-grants `WRITE_SECURE_SETTINGS` for reboot persistence.
     */
    suspend fun connectAndLaunchShellServer(
        host: String,
        connectPort: Int,
        abortOnUnauthorised: Boolean = false,
    ): PrivilegeOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { readyServer ->
                readyServer.soTimeout = 6_000
                val callbackPort = readyServer.localPort
                val secretKey = LoopbackShellCrypto.generateSecret()
                val secretHex = LoopbackShellCrypto.toHex(secretKey)
                val apkPath = context.applicationInfo.sourceDir
                val mainClass = RoutineShellServerMain::class.java.name
                val pkg = context.packageName

                val acceptDeferred = async(Dispatchers.IO) {
                    awaitReadyCallback(readyServer, secretKey)
                }

                val launchCmd = "pm grant $pkg $WRITE_SECURE_SETTINGS </dev/null >/dev/null 2>&1; " +
                    "(trap '' HUP; CLASSPATH='$apkPath' app_process /system/bin " +
                    "--nice-name=$pkg:shell $mainClass --loopback $secretHex $callbackPort " +
                    "</dev/null >/dev/null 2>&1 &); echo ROUTINE_OK"
                transportClient.executeOneShotShell(
                    host = host,
                    port = connectPort,
                    command = launchCmd,
                    abortOnUnauthorised = abortOnUnauthorised,
                )

                val loopbackClient = acceptDeferred.await()
                if (loopbackClient != null) {
                    LoopbackShellCrypto.activeLoopbackClient.set(loopbackClient)
                    LoopbackShellCrypto.saveEndpoint(context, loopbackClient.port, secretKey)
                }

                repeat(20) {
                    if (shellServiceProvider() != null) {
                        grantPermission(pkg, WRITE_SECURE_SETTINGS)
                        return@withContext PrivilegeOperationResult.Succeeded(
                            "Wireless Debugging connected! Shell server active & WRITE_SECURE_SETTINGS granted.",
                        )
                    }
                    delay(120L)
                }
                PrivilegeOperationResult.Failed("Started app_process server, waiting for shell callback timed out.")
            }
        }.getOrElse { error ->
            android.util.Log.w(TAG, "connectAndLaunchShellServer failed", error)
            PrivilegeOperationResult.Failed("Failed to launch internal shell server: ${error.message}")
        }
    }

    /**
     * Connects to `_adb-tls-connect._tcp` without a pairing code when Routine's RSA key was already paired.
     */
    suspend fun tryConnectPairedDaemon(timeoutMs: Long = 3_500L): PrivilegeOperationResult =
        withContext(Dispatchers.IO) {
            if (shellServiceProvider() != null) {
                return@withContext PrivilegeOperationResult.Succeeded("Wireless ADB Bridge is already connected.")
            }
            if (!WirelessAdbDiscovery.hasInternetPermission(context)) {
                return@withContext PrivilegeOperationResult.Failed("Wireless ADB requires wirelessAdb flavor.")
            }
            if (!keyManager.hasPersistedIdentity()) {
                return@withContext PrivilegeOperationResult.Failed(
                    "No paired Wireless Debugging key yet — pair once with a 6-digit pairing code.",
                )
            }
            val endpoint = withTimeoutOrNull(timeoutMs) {
                discovery?.observeConnectEndpoints()?.first()
            } ?: return@withContext PrivilegeOperationResult.Failed(
                "No active Wireless Debugging port detected. Enable Wireless Debugging in Developer Options.",
            )
            connectAndLaunchShellServer(endpoint.host, endpoint.port, abortOnUnauthorised = true)
        }

    /**
     * Automatically re-enables `adb_wifi_enabled` after a device reboot (using the previously self-granted
     * `WRITE_SECURE_SETTINGS` permission), discovers the local Wireless Debugging port, and reconnects.
     */
    suspend fun reconnectAfterBoot(): PrivilegeOperationResult = withContext(Dispatchers.IO) {
        if (shellServiceProvider() != null) {
            return@withContext PrivilegeOperationResult.Succeeded("ADB Bridge already connected.")
        }
        if (!WirelessAdbDiscovery.hasInternetPermission(context)) {
            return@withContext PrivilegeOperationResult.Failed(
                "Offline flavor does not use loopback Wireless Debugging sockets.",
            )
        }
        val hasSecure = ContextCompat.checkSelfPermission(context, WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasSecure) {
            return@withContext PrivilegeOperationResult.Failed(
                "Cannot auto-enable Wireless Debugging after reboot without WRITE_SECURE_SETTINGS.",
            )
        }
        runCatching {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            val endpoint = withTimeoutOrNull(12_000L) {
                discovery?.observeConnectEndpoints()?.first()
            } ?: return@withContext PrivilegeOperationResult.Failed(
                "Enabled adb_wifi_enabled=1, but no _adb-tls-connect._tcp service appeared within 12s.",
            )
            connectAndLaunchShellServer(endpoint.host, endpoint.port)
        }.getOrElse { error ->
            android.util.Log.w(TAG, "reconnectAfterBoot failed", error)
            PrivilegeOperationResult.Failed("Post-reboot reconnection failed: ${error.message}")
        }
    }

    private fun awaitReadyCallback(readyServer: ServerSocket, secretKey: ByteArray): LoopbackRoutineShellClient? =
        runCatching {
            readyServer.accept().use { callbackSock ->
                callbackSock.soTimeout = 3_000
                val dis = DataInputStream(callbackSock.inputStream)
                val daemonPort = dis.readInt()
                val uid = dis.readInt()
                val mac = ByteArray(32).also { dis.readFully(it) }
                val expected = LoopbackShellCrypto.computeReadyMac(secretKey, daemonPort, uid)
                if (MessageDigest.isEqual(expected, mac) && daemonPort in 1..65_535) {
                    LoopbackRoutineShellClient(daemonPort, secretKey)
                } else {
                    null
                }
            }
        }.onFailure { err ->
            android.util.Log.d(TAG, "Ready callback timed out or closed", err)
        }.getOrNull()

    companion object {
        private const val TAG = "AdbBridgeEngine"
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    }
}

/**
 * Hilt binding exposing [AdbBridgePrivilegedEngine] under [@AdbBridgeTier][AdbBridgeTier].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AdbBridgeModule {
    @Binds
    @Singleton
    @AdbBridgeTier
    abstract fun bindAdbBridgeEngine(impl: AdbBridgePrivilegedEngine): PrivilegedEngine
}
