# Sovereign SMS 0.1.0-alpha.1

**Alpha. Not yet tested on a phone or over a carrier network.** This is a release candidate for the owner's own device testing. It is not a public release. Read [KNOWN_LIMITATIONS.md](../KNOWN_LIMITATIONS.md) and [DEVICE_ACCEPTANCE.md](../DEVICE_ACCEPTANCE.md) first.

- Package `com.sovereignops.sms`, versionCode 1, versionName 0.1.0-alpha.1.
- Android 8.0 (API 26) and later; target SDK 36.
- Based on Fossify Messages 1.10.3 (GPL-3.0). Upstream history is preserved in the source repository.

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

## Upgrading and data

This is the first Sovereign release, so there is nothing to upgrade from. Later releases must be signed with the same key and have a higher versionCode (`scripts/verify-apk.sh --previous`). Back up with `.sovbak` before any update.

## Release gates still open

1. MMS receipt on a real device and carrier with the release APK (F-1).
2. Backup and restore on the release (R8) APK.
3. Signing with the owner's permanent key, and pinning its fingerprint in `docs/release/SIGNING_CERT_SHA256`.

## Verification

VERIFICATION_PLACEHOLDER
