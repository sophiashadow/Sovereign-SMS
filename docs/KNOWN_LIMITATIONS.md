# Known limitations

As of 2026-11-24, for v0.1.0-alpha.1. The status of each feature is in [STATUS.md](STATUS.md).

## Testing

- **No device, emulator or carrier test has been run.** The build container has no `/dev/kvm`. Every claim is JVM tests, a compile, lint and static inspection of the APK. [DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md) is the checklist that closes this gap.
- **Release gate: MMS receipt.** `MmsReceiver` was made non-exported (security fix F-1). The code path says same-package delivery still works, but a real MMS must be received on a phone, with the **release** APK, before release.
- **Release gate: R8.** Backup and restore, the vault and MMS must be exercised on the release (minified) APK, not only the debug one.
- No instrumented (on-device) tests exist. The vault migrations 1→2 and 2→3 are checked against Room's generated schemas and on stock SQLite, not on a device.
- The dependency CVE lookup could not run (the proxy refuses `api.osv.dev`). Versions are pinned in `gradle/libs.versions.toml`.

## Messaging

- Ordinary SMS and MMS are **not end-to-end encrypted**. The carrier, and anyone with access to the carrier network or the phone, can read them. Sovereign's encryption covers only its own records (vault, settings, `.sovbak`).
- No RCS. With Sovereign as the default SMS app, RCS chats in your previous messenger stop. Turn RCS off before switching ([INSTALL_AND_ROLLBACK.md](INSTALL_AND_ROLLBACK.md)).
- *Inference:* numbers on Android's own block list may be dropped before any app sees them. Sovereign's Quarantine list is the preserving alternative.
- Android 17 holds SMS in WebOTP or SMS Retriever format back from other apps for 3 hours. Whether the default SMS role holder is exempt is a device question ([ANDROID17_ONEUI_AUDIT.md](ANDROID17_ONEUI_AUDIT.md)).

## Privacy and locking

- The app lock is upstream Fossify's (PIN, pattern or biometrics). It doesn't offer the device screen lock as a method.
- Quick Cover filters the inbox and holds alerts. Upstream search and direct thread links (shortcuts, share targets) can still open a Professional conversation while cover is on.
- With no screen lock or biometrics enrolled, leaving Quick Cover needs no authentication.
- How One UI displays the neutral lock-screen notification and the Copy code action is untested.
- mmslib's own `Log.w`/`Log.e` lines may log MMS metadata (not bodies) on a device. This is unverified.
- The vault key isn't bound to user authentication, because messages must be filed while the phone is locked. Someone with an unlocked phone and the app lock open sees everything the app shows.

## Backup and portability

- A `.sovbak` holds Sovereign's records, not messages. Messages need a separate export ([BACKUP_RESTORE.md](BACKUP_RESTORE.md)).
- The thread preservation ZIP, the settings pack, the ledger CSV/Markdown and the upstream message export are **unencrypted**. The app warns before each one.
- Merge restore keeps both entries when the same ledger date has different values. You resolve the conflict by deleting one.
- Per-conversation settings (category, follow, priority, mute), the decision log, summary counters and ledger labels are not backed up.
- Uninstalling deletes the vault. A lost signing key means uninstalling to install a new build ([BUILD_AND_SIGNING.md](BUILD_AND_SIGNING.md)).

## Features

- **Deferred by owner decision:** per-message tags and notes.
- One quiet-hours window in the UI. Extra windows can be imported through JSON and are kept.
- The held summary notification counts messages but doesn't list them.
- The focus-ending notice uses an inexact alarm window (about a minute wide). It is re-armed after a reboot; focus itself ends on time either way.
- The decision log is global (thread id shown), not per conversation.
- Pair rules with a message window use the thread history in the conversation view and the badge. At receipt time only the new message is checked.
- Workload insights and daily summaries read the app's local message cache, which fills as conversations are opened. Threads never opened in this app may be undercounted.
- A card linked to a conversation can be edited but not deleted from the UI (unlinked cards can).
- Schedules and rules are edited as text.
- Ledger hours are capped at 24 per field. Ledger figures are whatever you type.
- Sovereign strings are English only. Upstream translations remain for upstream screens.

## Release

- The only release APK built so far was signed with a **throwaway key** that is not kept. It proves the release build works. It is not a release.
- No production keystore exists yet, so `docs/release/SIGNING_CERT_SHA256` is empty and `verify-apk.sh` reports the certificate pin as NOT_RUN.
- Not published, tagged or merged.

## Sizes (this build)

The figures are in the [release notes](release/RELEASE_NOTES_0.1.0-alpha.1.md).
