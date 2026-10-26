## Summary

-

## Checklist

- [ ] Kotlin only (no Java or JavaScript)
- [ ] Diff is focused (< 400 lines; refactors separated from features)
- [ ] No ADB or `Settings.Secure` writes outside `:privilege:adb-bridge` and `:privilege:one-time-grant`
- [ ] No `android.permission.INTERNET` added to the default `offline` flavor
- [ ] Public functions in `:core:engine`, `:core:permissions`, and `:privilege:api` have KDoc
- [ ] `./gradlew ktlintCheck detekt lintOfflineDebug test` passes
