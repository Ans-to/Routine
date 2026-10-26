# Contributing to Routine

Thank you for contributing to **Routine** — *automations for people who don't want to learn Tasker*.

## Development Prerequisites

- **JDK 17 or 21** (CI uses Eclipse Temurin 21; Kotlin bytecode targets JVM 17)
- **Android SDK Platform 35** (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 28`)
- **Python 3.10+** (optional, for `./scripts/new_template.py`)

## Local Verification Commands

```bash
# 1. Code formatting & static analysis (ktlint + Detekt + Android Lint)
./gradlew ktlintCheck detekt lintOfflineDebug

# 2. All JVM, Robolectric, Room DAO, Compose UI, and JaCoCo coverage gates
./gradlew testDebugUnitTest :app:testOfflineDebugUnitTest \
  :core:model:test :core:permissions:test :core:engine:test \
  :privilege:api:test :core:data:verifyCoreDataJacocoCoverage

# 3. Assemble default privacy-first (zero-INTERNET) debug & release APKs
./gradlew assembleOfflineDebug assembleOfflineRelease
```

---

## Adding a Self-Contained Template (Zero Engine or UI Changes)

Every built-in recipe implements `TemplateProvider` in `:feature:templates` and is contributed to Hilt's `Set<TemplateProvider>` multibinding. Neither `:core:engine` nor any Compose screen needs to be modified.

Run the generator script to scaffold a new self-contained provider:

```bash
python3 scripts/new_template.py \
  --id tpl_21_desk_focus \
  --class-name DeskFocusTemplateProvider \
  --title "Desk Focus Mode" \
  --description "Enable DND when connected to desk charger." \
  --order 21 \
  --trigger CHARGER \
  --action DO_NOT_DISTURB
```

Move any user-visible strings into `feature/templates/src/main/res/values/strings.xml` before opening a pull request.

---

## Adding a New Action Executor

1. Add the enum constant and its required `PrivilegeTier` + `Set<SystemPermission>` to `ActionType` in `:core:model`.
2. Implement `ActionExecutor` in `:action:standard` (if Standard/Accessibility tier) or `:action:privileged` (if Privileged tier, delegating exclusively to `PrivilegedEngine`).
3. Contribute the executor via `@Binds @IntoSet` in that module's Hilt `@Module`.
4. Add unit/Robolectric tests covering live execution, graceful permission-denied fallback, and `describeDryRun`.

---

## Non-Negotiable Guardrails

- **100% Self-Contained**: Never add dependencies on Shizuku, Tasker, Termux, LADB, external plugins, or cloud services.
- **Kotlin Only**: Zero `.java` or `.js` files anywhere in the repository (`ArchitectureBoundaryTest` fails CI if any appear).
- **Coroutines & `Flow` Only**: Never introduce `AsyncTask` or `LiveData`.
- **Privilege Module Boundary**: Only `:privilege:adb-bridge` and `:privilege:one-time-grant` may reference ADB pairing/discovery APIs or `Settings.Secure.put*` / `Settings.Global.put*`.
- **Mandatory Permission Rationale**: Always present `PermissionExplanationBottomSheet` before launching any system permission dialog or Settings intent.
- **Public KDoc Contract**: Every non-private function in `:core:engine`, `:core:permissions`, and `:privilege:api` must include `Contract:`, `Failure modes:`, and `Thread safety:` in its KDoc (`ArchitectureBoundaryTest` enforces this in CI).
- **Structured Error Handling**: Never swallow exceptions silently; every `catch` must log a structured entry or rethrow with context (while always rethrowing coroutine `CancellationException`).
- **Conventional Commits & GPL-3.0**: Format commit messages as `feat(scope): ...`, `fix(scope): ...`, `test(scope): ...`, `docs(scope): ...`, or `refactor(scope): ...`. All contributions are licensed under [GPL-3.0-only](../LICENSE).
