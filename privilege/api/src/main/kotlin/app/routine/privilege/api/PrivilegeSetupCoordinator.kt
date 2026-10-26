package app.routine.privilege.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Discovered local Wireless Debugging port endpoint exposed to UI setup wizards without leaking
 * platform network service discovery details outside `:privilege:adb-bridge`.
 */
data class PairingEndpointInfo(
    val host: String,
    val port: Int,
    val label: String,
)

/**
 * Lifecycle phases of an on-device Option A Wireless Debugging pairing session.
 */
enum class WirelessPairingPhase {
    IDLE,
    SCANNING,
    PORT_FOUND,
    PAIRING,
    CONNECTED,
    FAILED,
}

/**
 * Snapshot of the background/foreground Wireless Debugging pairing session observed by Settings UI.
 */
data class WirelessPairingStatus(
    val phase: WirelessPairingPhase = WirelessPairingPhase.IDLE,
    val endpoint: PairingEndpointInfo? = null,
    val message: String? = null,
)

/**
 * UI-safe facade for configuring Option A (Wireless Debugging ADB Bridge) and Option B
 * (One-Time ADB Grant) from Settings and Onboarding screens.
 */
interface PrivilegeSetupCoordinator {
    /**
     * Indicates whether this APK build includes local loopback socket support for Option A
     * (Wireless Debugging). Returns `false` in the strict zero-`INTERNET` `offline` flavor.
     *
     * Contract: Synchronously checks build capability and manifest permission state.
     * Failure modes: Never throws; returns `false` if permission lookup fails.
     * Thread safety: Stateless check; safe to invoke from any thread.
     */
    fun isWirelessAdbSupported(): Boolean = true

    /**
     * Returns the exact single-line `adb shell pm grant` command for Option B setup.
     *
     * Contract: Produces a deterministic command targeting the host application's package name.
     * Failure modes: Never throws.
     * Thread safety: Stateless string formatting; completely thread-safe.
     */
    fun buildOneTimeAdbCommand(): String

    /**
     * Copies [buildOneTimeAdbCommand] to the system clipboard and returns the copied string.
     *
     * Contract: Writes plain-text clip data to Android's clipboard service.
     * Failure modes: Captures platform clipboard errors and still returns the command text.
     * Thread safety: Must be invoked from the main thread or coroutine context safe for system services.
     */
    fun copyOneTimeCommandToClipboard(): String

    /**
     * Encodes [payload] into a square QR code boolean matrix (`true` = dark module) for PC/camera scanning.
     *
     * Contract: Returns a non-empty `N x N` boolean grid representing an ISO/IEC 18004 QR symbol.
     * Failure modes: Truncates payloads exceeding QR Version 4 capacity; never throws.
     * Thread safety: Pure computation; completely thread-safe.
     */
    fun encodeQrMatrix(payload: String): Array<BooleanArray>

    /**
     * Streams discovered local Wireless Debugging pairing endpoints over loopback mDNS.
     *
     * Contract: Emits [PairingEndpointInfo] items while active and stops discovery when cancelled.
     * Failure modes: Completes cleanly if platform service discovery is unavailable on the device.
     * Thread safety: Cold [Flow] safe to collect from any coroutine scope.
     */
    fun observeWirelessPairingEndpoints(): Flow<PairingEndpointInfo>

    /**
     * Exposes the live [WirelessPairingStatus] state flow maintained across foreground and background transitions.
     *
     * Contract: Always holds the latest [WirelessPairingStatus] for Option A setup.
     * Failure modes: Never throws; defaults to an idle [MutableStateFlow] when unimplemented in tests.
     * Thread safety: Hot [StateFlow]; completely thread-safe across all dispatchers.
     */
    fun observeWirelessPairingStatus(): StateFlow<WirelessPairingStatus> =
        MutableStateFlow(WirelessPairingStatus())

    /**
     * Starts the foreground Wireless Debugging scan service so discovery continues while Android Settings is open,
     * automatically posting a `RemoteInput` notification as soon as a pairing port appears.
     *
     * Contract: Launches background-resilient local port discovery and updates [observeWirelessPairingStatus].
     * Failure modes: Sets status to [WirelessPairingPhase.FAILED] if unsupported or blocked by OS restrictions.
     * Thread safety: Safe to invoke from any thread.
     */
    fun startWirelessPairingScan() = Unit

    /**
     * Stops any active foreground Wireless Debugging scan service and dismisses transient scan notifications.
     *
     * Contract: Cancels active discovery and resets transient scan state to [WirelessPairingPhase.IDLE].
     * Failure modes: Logs and ignores stop errors if the service is already stopped.
     * Thread safety: Safe to invoke from any thread.
     */
    fun stopWirelessPairingScan() = Unit

    /**
     * Posts the notification-shade `RemoteInput` prompt so the user can enter the 6-digit pairing code
     * without leaving Android's Wireless Debugging screen.
     *
     * Contract: Displays a high-priority ongoing notification bound to [host] and [port].
     * Failure modes: Logs and ignores notification errors if `POST_NOTIFICATIONS` is denied.
     * Thread safety: Safe to invoke from any thread.
     */
    fun showPairingCodeNotification(host: String, port: Int)

    /**
     * Pairs directly with [host]:[port] using the 6-digit [pairingCode] and launches the internal shell server.
     *
     * Contract: Performs TLS 1.3 + SPAKE2 pairing, connects to the local Wireless Debugging connect port, starts the
     * internal shell server, and self-grants `WRITE_SECURE_SETTINGS`.
     * Failure modes: Returns [PrivilegeOperationResult.Failed] on invalid code, timeout, or handshake failure.
     * Thread safety: Safe to invoke concurrently from any coroutine dispatcher.
     */
    suspend fun pairWirelessAdb(host: String, port: Int, pairingCode: String): PrivilegeOperationResult

    /**
     * Attempts to connect to an already-paired local Wireless Debugging session without requiring a new pairing code.
     *
     * Contract: Discovers an active local connect port and bootstraps the internal shell server if already paired.
     * Failure modes: Returns [PrivilegeOperationResult.Failed] quickly when unpaired or Wireless Debugging is off.
     * Thread safety: Safe to invoke concurrently from any coroutine dispatcher.
     */
    suspend fun tryConnectPairedWirelessAdb(): PrivilegeOperationResult =
        PrivilegeOperationResult.Failed("Not paired yet.")
}
