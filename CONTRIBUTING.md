# Contributing to Routine

Thank you for helping make **Routine** better — *automations for people who don't want to learn Tasker*.

## Core Project Rules

1. **100% Self-Contained**: Never introduce dependencies on external companion apps (Shizuku, Tasker, Termux, LADB).
2. **Kotlin Only**: All application, library, and build scripts must be written in Kotlin. No Java or JavaScript.
3. **Coroutines & Flow**: Use `suspend` functions and `Flow` / `StateFlow`. Do not use `AsyncTask` or `LiveData`.
4. **Module Boundaries**: No module outside `:privilege:adb-bridge` and `:privilege:one-time-grant` may contain ADB protocol code or `Settings.Secure` / `Settings.Global` write calls.
5. **Privacy by Default**: The default `offline` flavor must **not** declare `android.permission.INTERNET`. No analytics, crash reporting, or ad SDKs.
6. **Exception Handling**: Never swallow exceptions silently. Every `catch` block must log a structured entry or rethrow with context.
7. **Documentation**: Every public function in `:core:engine`, `:core:permissions`, and `:privilege:api` must carry KDoc describing its contract, failure modes, and thread safety.
8. **Conventional Commits & Focused PRs**: Use Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`). Keep pull requests under 400 lines of diff and separate refactors from features.

## Building & Running Tests

```bash
# Run formatting and static analysis
./gradlew ktlintCheck detekt lintOfflineDebug

# Run all unit tests
./gradlew test

# Assemble the default privacy-first debug APK
./gradlew assembleOfflineDebug
```

## Adding a New Template

Templates live in `:feature:templates` as self-contained `TemplateProvider` implementations bound into Hilt's `Set<TemplateProvider>`. Use `./scripts/generate_template.py` to scaffold a new template without touching `:core:engine` or Compose screen code.
