# Permissions & Graceful Fallbacks

Routine follows two strict UX and privacy rules for every Android permission:

1. **Zero Silent Prompts**: Before launching any runtime permission dialog or Android Settings screen, Routine displays `PermissionExplanationBottomSheet` explaining **why** the permission is needed and **what happens if declined**.
2. **Zero Network Access by Default**: The default `offline` flavor strips `android.permission.INTERNET` via `tools:node="remove"` in `app/src/main/AndroidManifest.xml` (verified on every CI build).

---

## Complete Permission Matrix

| Permission / Special Access | Tier | Flavors | Used By (Triggers & Actions) | Rationale Shown to User | Behavior / Fallback If Declined |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `POST_NOTIFICATIONS` | Standard | All | `NOTIFY` action, circuit-breaker safety alerts, pairing prompt | Displays rule alerts, reminders, and safety notifications | Rules still execute; notification-only steps report a clear setup badge |
| `RECEIVE_BOOT_COMPLETED` | Standard | All | `BOOT_COMPLETED` trigger, alarm re-registration after reboot | Restores scheduled time alarms after the phone restarts | Install-time permission; granted automatically |
| `SCHEDULE_EXACT_ALARM` | Standard | All | `TIME` & `HOTSPOT_DURATION` triggers | Fires bedtime, morning briefing, and hotspot timers at the exact minute | Falls back to `AlarmManager.setAndAllowWhileIdle` inexact window |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Standard | All | Background trigger reliability | Prevents aggressive OEM Doze policies from delaying scheduled automations | Optional; WorkManager and standard broadcasts continue under OS Doze |
| `ACCESS_NOTIFICATION_POLICY` | Standard | All | `RINGER_MODE` (`SILENT`), `DO_NOT_DISTURB` | Toggles Do Not Disturb and silent ringer during meetings or bedtime | Falls back to `AudioManager.RINGER_MODE_VIBRATE` or opens DND Settings intent |
| `WRITE_SETTINGS` | Standard | All | `BRIGHTNESS`, `ROTATION_LOCK` | Adjusts screen brightness percentage and auto-rotation lock | Opens the system Display Settings panel so the user can adjust manually |
| `ACCESS_WIFI_STATE` & `ACCESS_FINE_LOCATION` | Standard | All | `WIFI_SSID` & `LOCATION_GEOFENCE` triggers | Android requires location permission to read connected Wi-Fi SSID names and proximity geofences | Template shows Amber `Setup` badge; manual/other triggers remain unaffected |
| `ACCESS_BACKGROUND_LOCATION` | Standard | All | `LOCATION_GEOFENCE` trigger | Detects arriving at a saved venue while Routine is in the background | Geofence templates stay marked `Setup` until granted |
| `BLUETOOTH_CONNECT` | Standard | All | `BLUETOOTH_CONNECTED` trigger (Car / Headphones) | Reads connected Bluetooth audio/car profile state on Android 12+ | Bluetooth templates show Amber `Setup` badge until granted |
| `READ_CALENDAR` | Standard | All | `CALENDAR_OR_MANUAL` trigger | Checks local device calendar `AVAILABILITY_BUSY` windows for meeting DND | Rule still supports one-tap manual trigger mode without calendar access |
| `SEND_SMS` | Standard | All | `SMS_AUTO_REPLY` action | Sends driving auto-reply SMS when `DIRECT` mode is explicitly chosen | Default `INTENT` mode pre-fills an `ACTION_SENDTO` compose intent without `SEND_SMS` |
| `PACKAGE_USAGE_STATS` | Standard | All | `PERIODIC_CHECK` (`MOBILE_DATA_MB`) | Reads local `NetworkStatsManager` monthly mobile data counters | Falls back to `TrafficStats.getMobileRxBytes() + getMobileTxBytes()` |
| `READ_CONTACTS` & `ROLE_CALL_SCREENING` | Standard | All | `CALL_RECEIVED` & `SILENCE_UNKNOWN_CALLER` | Checks locally whether an incoming caller is in Contacts via `RoutineCallScreeningService` | Call-screening template remains inactive until the user grants role & contacts |
| `BIND_ACCESSIBILITY_SERVICE` | Optional Accessibility | All | `APP_OPENED` trigger (`RoutineAccessibilityService`) | Observes `TYPE_WINDOW_STATE_CHANGED` package names deterministically (`canRetrieveWindowContent = false`, zero UI tapping or scraping) | Optional; only needed for foreground-app triggers (#11 & #17) |
| `WRITE_SECURE_SETTINGS` | Privileged (Option B / A) | All | `SECURE_SETTING` action (Night Light, system toggles) | Writes validated keys in `Settings.Secure` / `Settings.Global` via one-time ADB grant | Template displays Grey `Privileged` badge with setup guide link |
| `INTERNET` & `ACCESS_NETWORK_STATE` | Privileged (Option A only) | `wirelessAdb` **only** (absent in `offline`) | Loopback `127.0.0.1` TLS 1.3 socket + mDNS discovery to on-device `adbd` | Required by Linux/Android socket security to open a loopback TCP connection to Wireless Debugging | Omitted completely from the default `offline` APK; use Option B (`One-Time ADB Grant`) for offline privileged setups |
