# Self-Contained Privileged Engine

Routine never asks users to install Shizuku, Termux, Tasker, or LADB. All privileged system toggles are mediated through the `:privilege:api` contract and implemented strictly inside `:privilege:one-time-grant` and `:privilege:adb-bridge`.

## Contract (`:privilege:api`)

`PrivilegedEngine` exposes four narrow, validated operations:

- `suspend fun checkAvailability(): PrivilegeAvailability`
- `suspend fun executeShellCommand(command: String, timeoutMs: Long = 8_000L): ShellCommandResult`
- `suspend fun grantPermission(packageName: String, permission: String): PrivilegeOperationResult`
- `suspend fun writeSecureSetting(namespace: SecureSettingNamespace, key: String, value: String): PrivilegeOperationResult`

All package names, permission names, and setting keys are validated against `PrivilegedEngine.isSafeIdentifier` (`^[a-zA-Z0-9_.]+$`) before touching any platform API or shell command.

`CompositePrivilegedEngine` coordinates both built-in engines behind `@Singleton PrivilegedEngine`:
- For `writeSecureSetting(...)`, it prefers **Option B (`OneTimeGrantPrivilegedEngine`)** whenever `WRITE_SECURE_SETTINGS` is granted (zero daemon overhead, direct `ContentResolver` write).
- For `executeShellCommand(...)` and `grantPermission(...)` (such as `cmd wifi set-wifi-enabled` or `cmd connectivity stop-tethering`), it delegates to **Option A (`AdbBridgePrivilegedEngine`)**.

---

## Option B: Persistent One-Time ADB Grant (`:privilege:one-time-grant`)

Option B requires running a single `adb` command once from a computer:

```bash
adb shell pm grant app.routine android.permission.WRITE_SECURE_SETTINGS
```

- **Reboot Persistence**: Android persists development permission grants (`WRITE_SECURE_SETTINGS`) across app updates and device reboots with zero background process.
- **Built-in Setup UX**: `OneTimeGrantSetupHelper` + `QrVersion4Encoder` render the exact command, copy it to the clipboard, and draw a pure-Kotlin ISO/IEC 18004 Version 4-L (`33x33`, Reed-Solomon GF(256)) QR symbol directly onto a Jetpack Compose `Canvas` — requiring zero external barcode libraries.
- **Supported Templates**: Night Light (`SECURE:night_display_activated`), Shorten Screen Timeout (`SYSTEM:screen_off_timeout`), and all custom `SECURE_SETTING` rules.

---

## Option A: Built-in Wireless Debugging ADB Bridge (`:privilege:adb-bridge`)

Option A requires **no computer** on Android 11+ devices by implementing a pure-Kotlin local Wireless Debugging client and internal Binder server inside Routine itself:

1. **Loopback mDNS Discovery (`WirelessAdbDiscovery`)**: Discovers `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` endpoints on `127.0.0.1` / local interface via platform `NsdManager`.
2. **Notification-Shade Pairing (`PairingNotificationController`)**: Posts a high-priority `RemoteInput` notification so the user can enter Android's 6-digit pairing code directly from the notification shade without closing Developer Options -> *Pair device with pairing code* (preventing Android from randomizing the ephemeral pairing port).
3. **Pure-Kotlin SPAKE25519 + TLS 1.3 Pairing (`AdbSpake2Pairing`)**:
   - Establishes a mutual TLS 1.3 socket using a self-signed 2048-bit RSA certificate (`AdbRsaKeyManager`) and exports 64 bytes of keying material via `SSLSockets.exportKeyingMaterial(sslSocket, "adb-label\u0000", null, 64)`.
   - Runs BoringSSL's Ed25519 `SPAKE2` handshake (`adb pair client\u0000` / `adb pair server\u0000`, cofactor-cleared password scalar, HKDF-SHA256 `"adb pairing_auth aes-128-gcm key"`) and exchanges the `PeerInfo` struct encrypted with `AES/GCM/NoPadding` containing Routine's 524-byte Android `RSAPublicKey`.
4. **ADB Transport & Internal Binder Server (`AdbTransportClient` + `RoutineShellServerMain`)**:
   - Connects over ADB `CNXN` / `STLS` / `OPEN("shell:...")` and launches `RoutineShellServerMain` (`UID 2000 shell`) directly from Routine's own `applicationInfo.sourceDir` APK via `app_process`.
   - `RoutineShellServerMain` sends a live `IBinder` (`RoutineShellBinder`) back to the app process via `RoutineShellBinderProvider` (`ContentProvider.call`), gated by caller UID verification (`UID == 2000 || UID == myUid()`).
5. **Self-Grant & Post-Reboot Auto-Reconnection (`AdbBridgeBootReconnectReceiver`)**:
   - Immediately upon first connection, `AdbBridgePrivilegedEngine` executes `pm grant <package> android.permission.WRITE_SECURE_SETTINGS` on itself.
   - After a device reboot (when `wirelessDebuggingAutoReconnect` is enabled in Settings), `AdbBridgeBootReconnectReceiver` uses that persistent `WRITE_SECURE_SETTINGS` grant to write `Settings.Global.putInt(cr, "adb_wifi_enabled", 1)` when connected to trusted Wi-Fi, discovers `_adb-tls-connect._tcp`, and re-launches the internal Binder shell server automatically.

### Flavor Isolation (`offline` vs `wirelessAdb`)

Because Android's loopback TCP sockets require `android.permission.INTERNET` at the kernel socket layer, Routine isolates socket permissions into the explicit `wirelessAdb` product flavor while keeping the default F-Droid-ready `offline` flavor completely free of `android.permission.INTERNET` (supporting Standard Tier + Option B One-Time ADB Grant with zero network capability).
