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

All checks were local, in a Linux build container with JDK 21 and build-tools 36.0.0, on 2026-11-24. **No emulator, device or carrier test has been run** (no `/dev/kvm`). CI was not run: Actions is not set up for this repository.

| Check | Command | Result |
|---|---|---|
| JVM unit tests | `./gradlew testFossDebugUnitTest` | **171 tests, 0 failures, 0 errors** |
| Lint | `./gradlew lintFossDebug` | 0 errors outside the upstream baseline; 45 warnings in Sovereign code, classified below |
| Debug build | `./gradlew assembleFossDebug` | built, 40,797,099 bytes, `com.sovereignops.sms.debug` |
| Release build | `./gradlew clean assembleFossRelease`, signed with a **throwaway key** | built, 15,017,449 bytes |
| Release APK SHA-256 | `sha256sum` | at `fd849a11`: `a3ea2aee1f3f91ecc4c5a7c004ffa2b7c0b9312c0aa8ddb264080779a32d2b1d` (throwaway-signed; **not a release**). The APK built at the commit holding these notes is listed in `dist/0.1.0-alpha.1/SHA256SUMS` |
| Reproducibility | clean `assembleFossRelease` runs, same machine and key | two builds at `fd849a11`: byte-identical. A build at `1ee0797d` (docs and a script changed) differed in exactly one entry, `META-INF/version-control-info.textproto`, where AGP records the git commit SHA; every other entry was identical (`diff` of per-entry SHA-256). So the APK hash is reproducible per commit, and each APK names the commit it was built from. Not tested across machines or JDKs |
| APK checks | `scripts/verify-apk.sh APK --cert <throwaway>` | PASS: apksigner (v2, 1 signer), certificate pin, package `com.sovereignops.sms`, versionCode 1, versionName 0.1.0-alpha.1, not debuggable, no INTERNET, `zipalign -P 16`, ELF LOAD 16 KB on all 8 native libraries. Without `--cert`: certificate pin **NOT_RUN** (no production fingerprint yet) |
| Upgrade check | `verify-apk.sh A.apk --previous B.apk` | FAIL as expected (same versionCode 1 → 1); package and certificate PASS |
| Merged manifest | `aapt2 dump permissions`, `aapt2 dump xmltree` | 16 permissions, all inherited from upstream (table in `SECURITY_REVIEW.md`); `allowBackup=false`; `MmsReceiver` not exported; exported components match `SECURITY_REVIEW.md` |
| Secret and personal-data scan | `git grep` of tracked files for key material, signing passwords, the session's throwaway password, email addresses and non-555 phone numbers in Sovereign files | none found (upstream's `keystore.properties_sample` placeholders only) |
| Licences | `LICENSE` files read at pinned versions | IndicatorFastScroll corrected to MIT; MIT, BSD-2-Clause and W3C texts now ship in the APK (`NoticesTest`). See `PROVENANCE.md` |
| Dependency CVEs | — | **NOT_RUN** (osv.dev unreachable from the container) |

**Lint warnings in Sovereign code (45), by severity.** None is a defect, so none was changed.

| Count | Rule | Severity | Assessment |
|---|---|---|---|
| 27 | UseKtx | style | Suggests KTX helpers; behaviour identical |
| 9 | SetTextI18n | low | Text joined in code. Sovereign strings are English only (a known limitation) |
| 6 | PluralsCandidate | cosmetic | "1 messages" style wording in counts |
| 1 | ApplySharedPref (`Vault.kt` set-aside) | none | Deliberate `commit()`: the old wrapped key must be on disk before a new vault is created |
| 1 | TooManyViews (Sovereign settings layout) | low | Performance of one long settings screen |
| 1 | TypographyDashes | cosmetic | Hyphen in a time-range hint |

**Fixed during this pass:**

- `verify-apk.sh` exited silently when the pin file held no fingerprint, and misread the package name from `aapt2` output.
- `release-artifacts.sh` took release notes and LICENSE from the working tree instead of the named commit.
- The settings-pack export was labelled "Export everything".
- Settings-pack import replaced sections without asking and left a stale flag cache.

**Artifacts.** `scripts/release-artifacts.sh APK` writes `dist/0.1.0-alpha.1/` (not committed):

- the APK;
- the GPL-3.0 source archive;
- notices, licences, these notes and `LICENSE`;
- `SHA256SUMS`, whose header names the commit.

The throwaway-signed APK in that folder is evidence for the build, not something to install and keep. The owner rebuilds and signs with the permanent key ([BUILD_AND_SIGNING.md](../BUILD_AND_SIGNING.md) §3), and that APK's SHA-256 becomes the release checksum.
