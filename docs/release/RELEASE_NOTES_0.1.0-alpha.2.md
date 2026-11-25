# Sovereign SMS 0.1.0-alpha.2

**Alpha.** The owner has used 0.1.0-alpha.1 on a phone, which found the group defects fixed here; no checklist item has passed yet, and nothing has been tested over a carrier network. This is a release candidate for the owner's own device testing, not a public release. Read [KNOWN_LIMITATIONS.md](../KNOWN_LIMITATIONS.md) and [DEVICE_ACCEPTANCE.md](../DEVICE_ACCEPTANCE.md) first.

- Package `com.sovereignops.sms`, versionCode 2, versionName 0.1.0-alpha.2.
- Android 8.0 (API 26) and later; target SDK 36.
- Based on Fossify Messages 1.10.3 (GPL-3.0). Upstream history is preserved in the source repository.

## Changes since 0.1.0-alpha.1

0.1.0-alpha.1 was never released. This release is stabilization only, with no new features. Root causes and limits are in [GROUP_CONVERSATIONS.md](../GROUP_CONVERSATIONS.md).

- **Titles.** A conversation is never titled with a raw chat ID or other opaque string. Unrecognized addresses are counted instead: "Ashford, 1 unrecognized address", "Unsupported conversation".
- **Sending.** An address that isn't a phone number, short code or email is never used as a destination. The whole send is refused, the draft or scheduled message is kept, and the conversation explains why it can't be replied to.
- **Group notifications.** They no longer offer an inline reply, which went to the sender alone and split the thread.
- **Rename.** Also available for one-to-one threads with an unrecognized address. Upstream rename for groups is unchanged.
- **Check conversations** (Settings → Sovereign). A read-only report of unrecognized addresses (shape only), possible duplicate threads and whether Android knows your number. It never merges or deletes.
- **Matching fixes:**
  - digits inside an opaque ID no longer match a real number in rules or card re-linking;
  - such a thread counts as an unknown sender.

## What's in it

Sovereign SMS adds the following to Fossify Messages:

- Messages are kept, never silently discarded, including from blocked and unknown senders. There's a separate Quarantine list and workspace.
- Local notification rules: quiet hours, timed focus, schedules, priority, mute, keyword tags, cooldown, and one-time codes with Copy code. A precedence engine and a "Why did this happen?" log explain each decision. Workspace modes and per-category channels.
- Encrypted contact cards, with alias, stage, checklist, notes and an opt-in availability list.
- A draft guard and incoming flags built on one pattern engine. Both warn and never block. Presets ship switched off.
- Templates, a quick-reply bar and placeholders. Inserting never sends. The availability queue sends only when you tap Send.
- Upstream app lock with a re-lock timeout, FLAG_SECURE and Quick Cover.
- A realized-cash ledger: net realized cash and net cash per labor hour. Receivables and restricted credits are kept out of cash. Legacy days are preserved.
- Workload insights and optional daily summaries.
- An encrypted `.sovbak` backup, merge/overwrite restore with re-linking by number, a checksummed thread preservation ZIP, and a settings pack with confirm and undo. Every unencrypted export warns first.

The full list is in [CHANGELOG.md](../../CHANGELOG.md). Formats are in [BACKUP_RESTORE.md](../BACKUP_RESTORE.md).

## Security and privacy

- No INTERNET permission, no telemetry, and Android cloud backup is off.
- Ordinary SMS and MMS are **not end-to-end encrypted**. The app encrypts its own records (SQLCipher vault, Keystore-sealed settings, password-encrypted `.sovbak`), not messages in transit.
- Security fixes in this release:
  - `MmsReceiver` is no longer exported (F-1);
  - regex rules run with a time budget (F-2);
  - imports are size-limited (F-3).
- No independent audit has been done. See [SECURITY_REVIEW.md](../SECURITY_REVIEW.md), [THREAT_MODEL.md](../THREAT_MODEL.md) and [PRIVACY.md](../PRIVACY.md).

See also the alpha.2 addendum in [SECURITY_REVIEW.md](../SECURITY_REVIEW.md#addendum-010-alpha2-2026-11-25): no new permissions or components.

## Upgrading and data

- **From the alpha.1 test APK: uninstall first.** It was signed with a throwaway key that no longer exists, so Android refuses an in-place update (`verify-apk.sh --previous`: certificate FAIL, versionCode 1 → 2 PASS). Before uninstalling:
  - make a `.sovbak` backup;
  - set your previous messenger as the default.

  Your messages stay in Android's message store.
- Later releases must be signed with the same key and have a higher versionCode (`scripts/verify-apk.sh --previous`).

## Release gates still open

1. MMS receipt on a real device and carrier with the release APK (F-1).
2. Backup and restore on the release (R8) APK.
3. Signing with the owner's permanent key, and pinning its fingerprint in `docs/release/SIGNING_CERT_SHA256`.

## Verification

All checks were local, in a Linux build container with JDK 21 and build-tools 36.0.0, on 2026-11-25. **No emulator, device or carrier test of this build has been run** (no `/dev/kvm`). CI was not run: Actions is not set up for this repository.

| Check | Command | Result |
|---|---|---|
| JVM unit tests | `./gradlew testFossDebugUnitTest` | **198 tests, 0 failures, 0 errors** (27 new: AddressClassifierTest 12, ThreadTitleTest 8, ThreadAuditTest 7; regression assertions added to WorkspaceTest) |
| Test-first evidence | the same suites before the fix | failed: did not compile without the new classes; WorkspaceTest regression failed with the old `rowIsKnown` |
| Lint | `./gradlew lintFossDebug` | 0 errors outside the upstream baseline; 45 warnings in Sovereign code, unchanged in count and kind from alpha.1 |
| Debug build | `./gradlew assembleFossDebug` | built, 40,819,127 bytes, `com.sovereignops.sms.debug` |
| Release build | `./gradlew clean assembleFossRelease` at `75a92b34`, signed with a **new throwaway key** | built, 15,039,413 bytes |
| Release APK SHA-256 | `sha256sum` | `a40f1155f9d7574197dd91501f362da52e3b31b8b73847836b21b3bede23f53e`. Throwaway-signed, **not a release**. The commit holding these notes changes only documentation |
| APK checks | `scripts/verify-apk.sh APK --cert <throwaway>` | PASS: apksigner (one signer), certificate pin, package, versionCode 2, versionName 0.1.0-alpha.2, not debuggable, no INTERNET, `zipalign -P 16`, ELF 16 KB on 8 native libraries |
| Upgrade check | `verify-apk.sh APK --previous <alpha.1 APK>` | package PASS, versionCode 1 → 2 PASS, certificate **FAIL** (different throwaway key: uninstall needed) |
| Merged manifest | merged debug manifest | 16 permissions (unchanged), INTERNET absent |
| Group behaviour on a phone or carrier | — | **NOT_RUN**. See the group items in `DEVICE_ACCEPTANCE.md` |
| Dependency CVEs | — | **NOT_RUN** (osv.dev unreachable from the container) |

The throwaway-signed APK is for the owner's testing, not for keeping. The release is rebuilt and signed with the permanent key ([BUILD_AND_SIGNING.md](../BUILD_AND_SIGNING.md) §3), and that APK's SHA-256 becomes the release checksum.
