# Contributing to Routine

Thank you for helping make **Routine** better — *automations for people who don't want to learn Tasker*.

Please read the full engineering & contributor guide in [**`docs/contributing.md`**](docs/contributing.md) before submitting a pull request.

## Quick Checklist

1. **100% Self-Contained**: Never introduce dependencies on external companion apps (Shizuku, Tasker, Termux, LADB).
2. **Kotlin Only**: All application, library, and build scripts must be written in Kotlin. No Java or JavaScript.
3. **Coroutines & Flow**: Use `suspend` functions and `Flow` / `StateFlow`. Do not use `AsyncTask` or `LiveData`.
4. **Module Boundaries**: No module outside `:privilege:adb-bridge` and `:privilege:one-time-grant` may contain ADB protocol code or `Settings.Secure` / `Settings.Global` write calls.
5. **Privacy by Default**: The default `offline` flavor must **not** declare `android.permission.INTERNET`. No analytics, crash reporting, or ad SDKs.
6. **Exception Handling**: Never swallow exceptions silently. Every `catch` block must log a structured entry or rethrow with context.
7. **Documentation**: Every public function in `:core:engine`, `:core:permissions`, and `:privilege:api` must carry KDoc describing its `Contract:`, `Failure modes:`, and `Thread safety:`.
8. **Templates**: Scaffold new self-contained templates via `python3 scripts/new_template.py --help` without modifying `:core:engine` or Compose UI screens.
