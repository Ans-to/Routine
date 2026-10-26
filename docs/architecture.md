# Routine Architecture

Routine is a local-first, 100% self-contained Android automation application written in Kotlin and Jetpack Compose, targeting Android 9+ (`minSdk = 28`, `targetSdk = 35`).

## Design Principles

1. **Zero External Companion Apps**: No Shizuku, Tasker, Termux, LADB, or third-party plugin protocols. Every capability tier lives inside Routine's own codebase.
2. **Linear, Comprehensible Rules**: Each `Rule` binds **1 `Trigger`** to **1..3 ordered `Action`s** with an optional per-rule cooldown (`cooldownSeconds`) and a deterministic 5-failure circuit breaker.
3. **UI-Independent Core Engine**: `:core:engine` is a pure JVM Kotlin module (`> 80%` JaCoCo line coverage) with zero Android UI dependencies, communicating with OS layers exclusively through injected ports (`CapabilityChecker`, `ActionExecutorRegistry`, `ExecutionLogSink`, `RuleExecutionStateStore`, `CircuitBreakerListener`).
4. **Strict Privilege Isolation**: Only `:privilege:adb-bridge` and `:privilege:one-time-grant` may touch ADB wire/pairing code or `Settings.Secure` / `Settings.Global` writes — enforced automatically on every CI run by `ArchitectureBoundaryTest`.
5. **Privacy by Default**: The default `offline` product flavor strips `android.permission.INTERNET` at manifest merge time (`tools:node="remove"`), contains zero analytics or crash SDKs, and encrypts local Room tables via SQLCipher + Android Keystore AES-256-GCM.

---

## 15-Module Gradle Graph

```text
                             :app
       ┌───────────┬──────────┼──────────┬───────────┐
       ▼           ▼          ▼          ▼           ▼
:feature:templates :feature:rules :feature:onboarding :feature:settings
       │           │          │          │
       └───────────┴────┬─────┴──────────┘
                        ▼
    ┌──────────────┬──────────────┬─────────────────┐
    ▼              ▼              ▼                 ▼
:trigger:standard :action:standard :action:privileged :core:data (SQLCipher)
    │              │              │                 │
    └──────────────┴──────┬───────┴─────────────────┘
                          ▼
                     :core:engine
                          │
              ┌───────────┴───────────┐
              ▼                       ▼
      :core:permissions         :privilege:api
              │                   ▲        ▲
              ▼                   │        │
         :core:model   :privilege:adb-bridge  :privilege:one-time-grant
```

| Module | Responsibility |
| :--- | :--- |
| `:app` | Hilt composition root, `RoutineTheme` (Material 3 dynamic color), bottom navigation shell, `offline` & `wirelessAdb` flavors |
| `:core:model` | `@Serializable` immutable domain types (`Rule`, `Trigger`, `Action`, `Template`, `PrivilegeTier`, `SystemPermission`, `ExecutionResult`) |
| `:core:permissions` | `Capability` state machine (`Ready`, `NeedsSetup`, `RequiresPrivilegeTier`, `Blocked`) and `DefaultCapabilityChecker` |
| `:core:engine` | `RuleEngine`, `TriggerMatcher`, `LogRedactor`, `ActionExecutor` & `ActionExecutorRegistry` ports |
| `:core:data` | SQLCipher-encrypted `RoutineDatabase`, `SqlCipherKeyManager` (Android Keystore AES-256-GCM), `RuleRepository`, `LogRepository`, `UserPreferencesRepository`, `RoutineBackupSerializer` |
| `:privilege:api` | `PrivilegedEngine`, `CompositePrivilegedEngine`, `PrivilegeAvailability`, and UI-safe `PrivilegeSetupCoordinator` |
| `:privilege:one-time-grant` | Option B persistent `WRITE_SECURE_SETTINGS` engine + clipboard helper + pure-Kotlin ISO/IEC 18004 Version 4 QR encoder |
| `:privilege:adb-bridge` | Option A pure-Kotlin Wireless Debugging mDNS discovery, BoringSSL SPAKE25519 + TLS 1.3 pairing, ADB transport, and internal `app_process` Binder shell server |
| `:trigger:standard` | Event-driven `TriggerScheduler`, `AlarmManager` exact alarms, manifest/dynamic `BroadcastReceiver`s, `PeriodicCheckWorker`, optional `RoutineAccessibilityService`, and `RoutineCallScreeningService` |
| `:action:standard` | Standard-tier `ActionExecutor`s (`NOTIFY`, `TOAST`, `RINGER_MODE`, `OPEN_APP`, `OPEN_URL`, `BRIGHTNESS`, `DO_NOT_DISTURB`, `SMS_AUTO_REPLY`, `BATTERY_SAVER`, `ROTATION_LOCK`, `SILENCE_UNKNOWN_CALLER`) with graceful fallbacks |
| `:action:privileged` | Privileged-tier `ActionExecutor`s (`SHELL_COMMAND`, `WIFI_TOGGLE`, `HOTSPOT_TOGGLE`, `SECURE_SETTING`) delegating solely to `PrivilegedEngine` |
| `:feature:onboarding` | Skippable 3-step first-launch wizard + mandatory `PermissionExplanationBottomSheet` before any system permission prompt |
| `:feature:templates` | 20 self-contained `@ElementsIntoSet` `TemplateProvider` recipes + 2-column capability-badged Compose grid |
| `:feature:rules` | `MyRulesScreen` (Active/Inactive sections + toggles), linear `RuleEditorScreen` (with side-effect-free `"Test Rule"` dry-run), and filterable `LogViewerScreen` |
| `:feature:settings` | Privileged Tier Setup wizards (Option A & Option B), Battery Optimization review, SAF JSON Backup/Restore, Theme preferences, and About |

---

## Execution Lifecycle & Safety Rails

1. **Validation**: `Rule.validate()` enforces non-blank names, valid trigger bounds (hours `0..23`, minutes `0..59`, percentages `1..100`, geofence coordinates), and `1..3` valid actions.
2. **Dry Run (`dryRun = true`)**: Invoked by `"Test Rule"` in the Rule Editor. Evaluates `CapabilityChecker`, calls `ActionExecutor.describeDryRun` on each step **without executing side effects**, redacts parameters via `LogRedactor`, and records a `[DRY_RUN]` log entry.
3. **Cooldown Enforcement**: `RuleEngine` guards per-rule timestamps with a coroutine `Mutex`, skipping rapid duplicate firings as `ExecutionResult.SkippedCooldown`.
4. **Per-Action Timeout & Structured Error Handling**: Each step runs inside `withTimeout(actionTimeoutMs)` (default `10,000 ms`). Coroutine `CancellationException` propagates cleanly; timeouts and failures are captured and logged without crashing background receivers.
5. **5-Failure Circuit Breaker**: When `RuleExecutionStateStore.recordFailure` reaches `Rule.CIRCUIT_BREAKER_THRESHOLD` (`5`), Room atomically sets `enabled = false` and `CircuitBreakerListener` posts a user-visible safety notification explaining why the rule was paused. Re-enabling the rule resets `consecutiveFailures` to `0`.
6. **Encrypted Storage & 1,000-Row Redacted Logs**: `RoutineDatabase` wraps a 256-bit SQLCipher key inside Android Keystore (`AES/GCM/NoPadding`). `LogRepository` scrubs SMS bodies, phone numbers, bearer tokens, passwords, and shell setting values via `LogRedactor.redact` before inserting via `LogDao.insertWithRotation(maxEntries = 1000)`.
