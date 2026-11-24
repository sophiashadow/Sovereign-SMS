# Sovereign SMS

A private SMS/MMS app for Android, built for independent service providers, freelancers, and anyone who wants their messages organised on their own terms. It runs locally, has no INTERNET permission, and sends no telemetry.

It is a fork of **[Fossify Messages](https://github.com/FossifyOrg/Messages)** 1.10.3 (GPL-3.0). See [docs/PROVENANCE.md](docs/PROVENANCE.md).

## What it adds to Fossify Messages

- **Screened messages are kept.** Upstream deletes messages from blocked numbers, blocked keywords and unknown senders. Sovereign SMS stores them as read and archived instead, so nothing is lost. You can turn this off.
- **Local notification rules.** These include quiet hours, focus mode, priority numbers and conversations, muted conversations, unknown-sender handling, keyword rules with tags, a cooldown, and always alerting for one-time codes. Rules only change how a message is announced. They never affect whether it is stored. Each decision comes with an explanation, and you can test rules against a sample message before relying on them.
- **Portable rules.** You can export and import your rules as a JSON file.
- **Its own identity.** It uses the app ID `com.sovereignops.sms`, so it installs alongside your current messenger. App data is excluded from Android cloud backup.

The state of each feature is in [docs/STATUS.md](docs/STATUS.md).

## Build

```sh
./gradlew testFossDebugUnitTest assembleFossDebug
# APK: app/build/outputs/apk/foss/debug/sovereign-sms-1-foss-debug.apk
```

Requirements:
- JDK 17 or newer.
- Android SDK platform 36 and build-tools 36.0.0 (`local.properties`: `sdk.dir=...`).
- Network access to Google Maven, Maven Central and jitpack.io.

See [docs/BUILD.md](docs/BUILD.md).

## Install and roll back

Read [docs/INSTALL_AND_ROLLBACK.md](docs/INSTALL_AND_ROLLBACK.md) before making it your default SMS app.

## Licence

GPL-3.0, the same as upstream (see [LICENSE](LICENSE)). Fossify's copyright notices are kept in the source.
