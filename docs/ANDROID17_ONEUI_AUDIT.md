# Android 17 / One UI audit

Date: 2026-11-24. Build under audit: `compileSdk 36`, `targetSdk 36`, `minSdk 26` (`app/build.gradle.kts`). Android 17 is API level 37, so the app does **not** opt in to any behaviour change that applies only to apps targeting 37.

Labels:
- **Fact (doc):** stated on the cited developer.android.com page, read on 2026-11-24.
- **Fact (build):** observed in this repository or its APK.
- **Inference:** a conclusion drawn from those facts, not checked.
- **Device test:** must be confirmed on a phone. None has been run yet.

## Sources

- Android 17 overview: https://developer.android.com/about/versions/17. The page's "last updated" date is 2026-07-01, and it doesn't state a stable release date.
- Changes for all apps: https://developer.android.com/about/versions/17/behavior-changes-all
- Changes for apps targeting 37: https://developer.android.com/about/versions/17/behavior-changes-17
- Developer verification: https://developer.android.com/developer-verification
- Advanced Protection: https://developer.android.com/privacy-and-security/advanced-protection-mode

## Changes that apply to every app, whatever its target

| Change | Status | Effect on Sovereign SMS |
|---|---|---|
| SMS one-time-code protection. Messages in WebOTP or SMS Retriever format are held from other apps for 3 hours (the SMS_RECEIVED broadcast is withheld and provider queries are filtered) | Fact (doc) | The docs exempt the "default SMS assistant app" and a few roles. They don't say whether the **default SMS role holder** is exempt. If it isn't, a code in those formats could arrive late, and so would the Copy code action. **Device test.** Codes in ordinary formats aren't covered by this change at target 36 (Fact, doc) |
| Per-app memory limits and Keystore key-count limits | Fact (doc) | Inference: no effect. The vault uses 1 wrapping key and the guard and templates use 1 key each |

## Changes that apply only when targeting 37 (we target 36, so none applies yet)

| Change | What it means when the target is raised |
|---|---|
| One-time-code protection extends to standard SMS formats | The same question as above, but for every code message. Recheck before raising the target |
| `setContentCaptureEnabled(false)` no longer prevents content capture | FLAG_SECURE is the replacement. The Sovereign privacy toggle already sets FLAG_SECURE on every activity (Fact, build) |
| Certificate transparency on by default | No effect: the app has no INTERNET permission (Fact, build: `aapt2 dump permissions` lists no INTERNET) |
| Native libraries loaded with `System.load` must be read-only | The vault loads SQLCipher with `System.loadLibrary("sqlcipher")` (Fact, build: `vault/Vault.kt`), which loads from the installed APK and not from a writable path (Inference) |
| Background audio limits | Not used |

## Not covered by the Android 17 pages (nothing claimed)

The pages say nothing about 16 KB page size, notification channels, alarms, Quick Settings tiles, broadcast receivers or BiometricPrompt. We verified 16 KB readiness on the APK ourselves, so it doesn't depend on a doc claim:

- **Fact (build):** `zipalign -c -P 16 -v 4` passes on both APKs.
- **Fact (build):** every native library in the release APK has ELF LOAD segments aligned to 0x4000 (`readelf -lW`). There are two native libraries, each in 4 ABIs: `libsqlcipher.so` (SQLCipher 4.20.0) and `libandroidx.graphics.path.so` (AndroidX).

## Developer verification

- **Fact (doc):** from 2026-09-30, verification is enforced only for apps from participating stores in Brazil, Indonesia, Singapore and Thailand. Global rollout is planned for 2027.
- **Fact (doc):** "Apps installed using ADB won't require verification." The `adb install -r` path in `DEPLOY_SAMSUNG_ONEUI.md` is therefore not affected.
- **Fact (doc):** there is an advanced flow for installing unverified apps, which can be allowed for 7 days or indefinitely.
- **Fact (doc):** a limited-distribution option exists for small groups of devices. The page gives inconsistent device counts (20 and 50), so we don't rely on either figure.
- **Inference:** opening an APK from a file manager is not yet affected outside the four countries. Recheck before 2027.

## Advanced Protection Mode

- **Fact (doc):** its listed protections include "Blocked app sideloading", spam protection for links in messaging apps, blocking calls from unknown numbers, and forensic logging. Apps can read the state with `AdvancedProtectionManager` (permission `QUERY_ADVANCED_PROTECTION_MODE`).
- **Not stated:** whether the sideloading block also covers `adb install`. **Device test.** If the install is refused, turn the mode off for the install only, or install before you turn it on.
- Sovereign SMS doesn't read the Advanced Protection state. No change is needed.

## One UI (Samsung)

Samsung publishes no API-level documentation for these. Everything here is **Inference** from general Android behaviour plus Samsung's user-facing menus, so it needs a **device test**:

- **Auto Blocker** blocks installs from outside the stores and can block USB commands. Turn it off for the install (`DEPLOY_SAMSUNG_ONEUI.md` §1).
- **Sleeping apps** can delay SMS notifications. Set Battery to Unrestricted (§4).
- One UI may restyle the neutral public notification and the Copy code action on the lock screen.
- **Restricted settings** applies to sideloaded apps. It may grey out a role or permission request until it is allowed in App info (§3).

## Result

At target 36, no Android 17 change requires a code change today. Three open items need a device:

1. Whether the default SMS app is exempt from the 3-hour one-time-code hold.
2. Whether Advanced Protection blocks `adb install`.
3. How One UI shows the lock-screen notification.

Raising the target to 37 is a separate task, with the checklist above.
