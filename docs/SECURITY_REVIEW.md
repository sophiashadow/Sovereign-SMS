# Security review: Slice F release build

Date: 2026-11-24.

**Scope.** `sovereign-sms-1-foss-release.apk` (package `com.sovereignops.sms`, versionCode 1, versionName 0.1.0, compile and target SDK 36), built from this branch. The release build is R8-minified and signed with a throwaway test key that is not kept.

**Method.**
- The merged manifest (`aapt2 dump xmltree` and `aapt2 dump permissions`).
- `apksigner verify`, `zipalign -c -P 16`, and `readelf` on the native libraries.
- A source read of the Sovereign code and JVM unit tests.

No dynamic, device or carrier testing has been done. Android 17 and One UI points are in `ANDROID17_ONEUI_AUDIT.md`.

## Network and data flow

- **No `INTERNET` permission** (`aapt2 dump permissions`), so the app can't open network connections. MMS uses the carrier's MMS APN through the system, as upstream does.
- No analytics, crash reporting or telemetry. Upstream has none either.
- Messages stay in the Android Telephony provider. Sovereign stores its settings, rules, cards, quarantine list, ledger and decision log in its own private storage. It never copies message bodies into its storage.
  - The decision log keeps the rule, the outcome and the explanation, with no body or address. It holds the last 200 entries.
- **Backup is off.**
  - `allowBackup="false"`.
  - `dataExtractionRules` excludes everything from cloud backup. From device-to-device transfer it excludes the vault database (`sovereign_vault.db` and its `-wal`, `-shm` and `-journal` files), the wrapped vault key and the sealed settings.
  - Keystore keys never migrate, so copied ciphertext would be unreadable anyway.

## Storage cryptography (no custom crypto)

- **Vault.**
  - SQLCipher 4.20.0, keyed with a raw random 256-bit key, so no KDF is involved.
  - That key is wrapped by a non-exportable Android Keystore AES-GCM key. It isn't bound to user authentication, because messages must be filed while the phone is locked.
  - If the vault fails to open, its files are renamed aside and kept.
  - The crypto provider in this AAR is LibTomCrypt, not OpenSSL. We checked the native library (`PROVENANCE.md`).
- **Guard rules, templates and sealed settings:** Keystore AES-GCM.
- **`.sovbak` backup** (`core/backup/SovBak.kt`). All primitives come from the platform JCA.
  - Key derivation: PBKDF2-HMAC-SHA256 with 600,000 iterations and a random 16-byte salt.
  - Encryption: AES-256-GCM with a random 12-byte nonce.
  - Format: a JSON header line, a newline, then the ciphertext. The exact bytes of the header are the GCM associated data, so changing the header (for example the iteration count, salt or format) makes decryption fail.
  - Validation:
    - Iteration counts outside 10,000 to 10,000,000 are refused before any key is derived.
    - Passwords must be at least 10 characters.
    - A wrong password and a damaged file give the same error, "Wrong password or damaged file".
  - Restore decrypts and parses the whole file before it changes anything. The write then happens in one database transaction.
  - After writing a backup, the app reopens it with the password the user entered to check it.
  - Tests: `SovBakTest` (round trip at 600k, wrong password, flipped ciphertext, header tamper, fresh salt and nonce, garbage rejected) and the backup-content tests (card remap, merge and overwrite). The iteration bounds are in code but have no test of their own.
- **Thread preservation ZIP** (`core/preserve/ThreadArchive.kt`).
  - Contents: `thread.md`, `messages.json`, attachments, and `manifest.sha256`, written last in `sha256sum` format.
  - The ZIP is **not encrypted**. It is a preservation copy that the user saves where they choose.
  - After writing, the app reads the ZIP back and checks it against the manifest.
  - Markdown escapes `<` and backslashes, so message text can't inject markup.

## Release hardening

- **Not debuggable.** The release manifest has no `debuggable` attribute.
- **Signature.** `apksigner verify` passes with v2 signing. The certificate is the throwaway test key (SHA-256 `5284987520e892756df6843979ac2c560f20b5d526a203326d9fdfae078a88c5`). The owner must re-sign with their own key (`DEPLOY_SAMSUNG_ONEUI.md` §2).
- **16 KB pages.** `zipalign -c -P 16 -v 4` passes. Every native library (`libsqlcipher.so` and `libandroidx.graphics.path.so`, 4 ABIs each) has LOAD segments aligned to 0x4000.
- **R8.**
  - `-keep class net.zetetic.** { *; }` keeps SQLCipher, which is reached from JNI and Room.
  - MMS packages are kept (`com.android.mms.**`, `com.klinker.android.send_message.**`, `com.google.android.mms.**`).
- **Logcat.**
  - Sovereign code has no `Log`, `println` or `printStackTrace` calls (checked with grep).
  - The release build strips `Log.v`, `Log.d` and `Log.i` from all code, including libraries (`-assumenosideeffects`). `Log.w` and `Log.e` stay for crash triage.
  - *Inference:* upstream or library warnings could still log metadata. We haven't audited this on a device.
- **Runtime receivers.** The screen-off receiver is registered with `RECEIVER_NOT_EXPORTED`.
- **Screen privacy.** The FLAG_SECURE toggle applies to every activity and blocks screenshots and the Recents preview.

## Permissions (all inherited from upstream)

| Permissions | Why |
|---|---|
| READ/WRITE/SEND/RECEIVE_SMS, RECEIVE_MMS, `android.provider.Telephony.SMS_RECEIVED` | Default SMS app |
| READ_CONTACTS, READ_PHONE_STATE | Names, SIM choice |
| CALL_PHONE | Upstream's call button |
| POST_NOTIFICATIONS | Notifications |
| SCHEDULE_EXACT_ALARM | Upstream's scheduled messages. Sovereign's focus notice uses an inexact window, not this permission |
| RECEIVE_BOOT_COMPLETED, WAKE_LOCK | Rescheduling and receipt |
| READ_SYNC_SETTINGS | Upstream |
| USE_FINGERPRINT | Upstream lock |
| `org.fossify.android.permission.WRITE_GLOBAL_SETTINGS` | Fossify's shared theme settings |

Candidates for removal (owner decision; each removes an upstream feature): CALL_PHONE and the Fossify global-settings permission.

## Exported components (release merged manifest)

| Component | Protection | Note |
|---|---|---|
| `SmsReceiver` | BROADCAST_SMS | Required for a default SMS app |
| `PushReceiver` (WAP push / MMS) | BROADCAST_WAP_PUSH | Required |
| `HeadlessSmsSendService` | SEND_RESPOND_VIA_MESSAGE | Required |
| `QuickCoverTile` | BIND_QUICK_SETTINGS_TILE | Only the system can bind it |
| `MainActivity`, `NewConversationActivity` (SENDTO/VIEW/SEND), `SettingsActivity`, `ArchivedConversationsActivity`, `RecycleBinConversationsActivity`, launcher aliases | none | As upstream. They open UI only, and nothing sends without a tap |
| **`MmsReceiver`** | **none, no intent filter** | **Finding (upstream).** It is exported with no permission. It receives the MMS download-complete `PendingIntent`, which the system delivers under our own identity, so it probably doesn't need to be exported. Another app could send it a forged explicit broadcast. The impact isn't analysed. *Recommendation:* set `exported="false"`, then confirm that MMS receipt still works on a device. Not changed yet, because breaking MMS receipt untested is worse than the open receiver |
| `FossifyBroadcastReceiver` | none | Re-reads Fossify theme settings only, so the effect is limited to colours. Removal candidate |
| `ProfileInstallReceiver` (AndroidX) | DUMP | Only the system or shell can send to it |

Every other Sovereign component is **not exported**: the settings, ledger, portability and availability-queue activities, plus `FocusEndingReceiver` and `CopyCodeReceiver`. The client card is a dialog inside the thread screen. `QuickCoverTile` is the only exported Sovereign component, and only the system can bind it.

## Sovereign behaviour

- **Nothing is discarded.** Messages that upstream would silently delete (blocked numbers, keywords, unknown senders) are kept read and archived. Quarantined senders are always received and stored.
- **Rules change announcement only, never storage.** If rules fail, the message gets a normal alert, and invalid rule files are rejected.
- **Nothing auto-sends.** Templates and placeholders insert into the composer. The availability queue sends only on a tap, and a warning needs a second tap. No Sovereign code sends on its own.
- **Unknown-sender detection fails toward alerting.** A contact lookup error counts as known.

## Known gaps

- The `MmsReceiver` export (above).
- **System block list.** *Inference:* numbers on Android's own block list may be dropped before any app sees them. Sovereign's Quarantine list is the preserving alternative, and the app warns before you block at OS level. Needs a device test.
- **App lock.** Upstream's lock hasn't been re-verified, and it doesn't offer the device screen lock as a method.
- **Encryption.** Carrier SMS is not end-to-end encrypted, and this app doesn't change that.
- **Signing.** The release key is throwaway. Production needs the owner's keystore and a pinned certificate digest.
