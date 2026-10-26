package app.routine.privilege.api

import kotlinx.coroutines.flow.Flow

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
     * Binder shell server, and self-grants `WRITE_SECURE_SETTINGS`.
     * Failure modes: Returns [PrivilegeOperationResult.Failed] on invalid code, timeout, or handshake failure.
     * Thread safety: Safe to invoke concurrently from any coroutine dispatcher.
     */
    suspend fun pairWirelessAdb(host: String, port: Int, pairingCode: String): PrivilegeOperationResult
}
