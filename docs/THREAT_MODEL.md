# Threat model

What Sovereign SMS tries to protect, against whom, and where it stops. It is a local messaging app with an encrypted store for its own records. It is **not** a secure messenger: carrier SMS and MMS are not end-to-end encrypted, and this app doesn't change that. Nothing here has been independently audited.

## Assets

1. **Messages.** They are held by Android's Telephony provider, not by this app's encryption.
2. **Sovereign records:**
   - cards (aliases, notes, stages, contact history);
   - the quarantine list, the ledger and the decision log;
   - draft-guard rules and templates.
3. **Discretion on screen:** what a bystander sees in the inbox, notifications and lock screen.
4. **Continuity:** not losing messages or records through screening, a failed restore, a lost key or a bad import.

## Adversaries and coverage

| Adversary | Covered | How | Not covered |
|---|---|---|---|
| Bystander glancing at the phone | Partly | Neutral lock-screen versions for Professional and Unknown; Quick Cover; app lock with re-lock timeout; FLAG_SECURE | Someone who has the unlocked phone with the app lock open |
| Other apps on the phone | Yes, within Android's sandbox | Private storage; non-exported components (`ManifestExportTest`); `MmsReceiver` fix F-1; sensitive clipboard flag | A compromised OS, root, or apps granted SMS permissions of their own, which can read the Telephony provider |
| Lost or stolen phone, locked | Partly | Vault and sealed values are encrypted with Keystore-wrapped keys; Android file-based encryption | Messages are protected only by Android's device encryption. The vault key isn't bound to user authentication, so once the phone is unlocked after boot, the app can open the vault |
| Cloud or backup leakage | Yes | `allowBackup=false`; data-extraction rules exclude the vault, key and sealed values; no INTERNET permission | Messages in a phone maker's or carrier's own backup; files you export yourself |
| Network observer or carrier | No | — | SMS and MMS content and metadata are visible to the carrier and network. No app-level encryption of messages |
| Someone who gets a `.sovbak` | Yes, with a strong password | AES-256-GCM, PBKDF2-HMAC-SHA256 at 600,000 iterations, authenticated header, minimum 10 characters | Weak or reused passwords; brute force against short ones |
| Someone who gets an unencrypted export | No | A warning before each export | Thread ZIP, settings pack, ledger CSV/MD and message export are plain files |
| Malicious import file | Yes | Parse before write; size limits; unknown versions and kinds rejected whole; regex time budget; pack import confirm and undo | — |
| Tampered update | Yes, with the owner's key | Android's same-certificate rule; `verify-apk.sh` pins the certificate and checks the version code | Loss or theft of the owner's signing key |
| The app itself | By design | No INTERNET permission; no auto-send; templates only insert; queue sends on tap; nothing forwards, deletes or replies automatically | — |

## Design commitments

- **No silent loss.** Screening keeps messages; unreadable stores are renamed aside, never deleted; restore is all-or-nothing; merge never overwrites.
- **No custom cryptography.** The platform JCA, Android Keystore and SQLCipher are used as documented.
- **No covert behaviour.** There is no monitoring, forwarding, hidden transmission, auto-reply or automatic evidence destruction.
- **Warnings, not gates, on your own text.** The draft guard and the export warnings inform and never block.

## Residual risks the owner accepts or decides

- Upstream library behaviour: mmslib registers an exported runtime receiver on an unguessable action (R-1), and `FossifyBroadcastReceiver` is exported but limited to theme colours (R-2).
- The dependency CVE scan hasn't run (C-7).
- Device behaviour is unverified. See [DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md).

Details and evidence are in [SECURITY_REVIEW.md](SECURITY_REVIEW.md).
