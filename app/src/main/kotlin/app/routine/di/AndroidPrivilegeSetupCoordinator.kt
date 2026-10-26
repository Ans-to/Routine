package app.routine.di

import android.content.Context
import app.routine.BuildConfig
import app.routine.privilege.adb.AdbBridgePrivilegedEngine
import app.routine.privilege.adb.discovery.PairingNotificationController
import app.routine.privilege.adb.discovery.WirelessAdbDiscovery
import app.routine.privilege.api.PairingEndpointInfo
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.PrivilegeSetupCoordinator
import app.routine.privilege.grant.OneTimeGrantSetupHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Bridges `:feature:settings` UI setup flows to `:privilege:one-time-grant` and `:privilege:adb-bridge`
 * without leaking platform ADB/NSD internals into feature modules.
 */
@Singleton
class AndroidPrivilegeSetupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discovery: WirelessAdbDiscovery,
    private val adbBridgeEngine: AdbBridgePrivilegedEngine,
) : PrivilegeSetupCoordinator {
    override fun isWirelessAdbSupported(): Boolean =
        BuildConfig.ALLOW_LOOPBACK_ADB_SOCKET && WirelessAdbDiscovery.hasInternetPermission(context)

    override fun buildOneTimeAdbCommand(): String = OneTimeGrantSetupHelper.buildAdbGrantCommand(context.packageName)

    override fun copyOneTimeCommandToClipboard(): String =
        runCatching { OneTimeGrantSetupHelper.copyCommandToClipboard(context) }
            .getOrElse { err ->
                android.util.Log.w("PrivilegeSetupCoord", "Clipboard copy failed", err)
                buildOneTimeAdbCommand()
            }

    override fun encodeQrMatrix(payload: String): Array<BooleanArray> = OneTimeGrantSetupHelper.encodeQrMatrix(payload)

    override fun observeWirelessPairingEndpoints(): Flow<PairingEndpointInfo> =
        discovery.observePairingEndpoints().map { ep ->
            PairingEndpointInfo(host = ep.host, port = ep.port, label = ep.serviceName)
        }

    override fun showPairingCodeNotification(host: String, port: Int) {
        runCatching {
            PairingNotificationController.showPairingCodePrompt(context, host, port)
        }.onFailure { err ->
            android.util.Log.w("PrivilegeSetupCoord", "Unable to show pairing notification", err)
        }
    }

    override suspend fun pairWirelessAdb(host: String, port: Int, pairingCode: String): PrivilegeOperationResult =
        adbBridgeEngine.pairAndBootstrap(host = host, pairingPort = port, pairingCode = pairingCode)
}
