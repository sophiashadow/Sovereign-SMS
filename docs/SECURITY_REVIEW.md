# Security review: v0.1.0-alpha.1 release candidate

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
| `MmsReceiver` | **not exported** (fixed, F-1 below) | Reached only by an explicit broadcast from this app's own process |
| `FossifyBroadcastReceiver` | none | Re-reads Fossify theme settings only, so the effect is limited to colours. Removal candidate |
| `ProfileInstallReceiver` (AndroidX) | DUMP | Only the system or shell can send to it |

Every other Sovereign component is **not exported**: the settings, ledger, portability and availability-queue activities, plus `FocusEndingReceiver`, `CopyCodeReceiver` and `SummaryReceiver` (system broadcasts such as BOOT_COMPLETED still reach a non-exported receiver). `ManifestExportTest` fails the build when a component is exported without being on its allow-list. The client card is a dialog inside the thread screen. `QuickCoverTile` is the only exported Sovereign component, and only the system can bind it.

## Sovereign behaviour

- **Nothing is discarded.** Messages that upstream would silently delete (blocked numbers, keywords, unknown senders) are kept read and archived. Quarantined senders are always received and stored.
- **Rules change announcement only, never storage.** If rules fail, the message gets a normal alert, and invalid rule files are rejected.
- **Nothing auto-sends.** Templates and placeholders insert into the composer. The availability queue sends only on a tap, and a warning needs a second tap. No Sovereign code sends on its own.
- **Unknown-sender detection fails toward alerting.** A contact lookup error counts as known.

## Adversarial review (2026-11-24)

Method: source read of the Sovereign code and the upstream paths it touches, `javap` disassembly of the
bundled mmslib (`com.klinker.android.send_message` 1.0.1), and JVM tests. **No emulator or device was
available** (no `/dev/kvm`), so nothing below was reproduced dynamically.

| ID | Area | Finding | Evidence | Status |
|---|---|---|---|---|
| F-1 | Forged intents | **High.** `MmsReceiver` was exported with no permission. Its base class (`MmsReceivedReceiver.onReceive`, final) reads the path in the `file_path` extra, tries to parse it as an MMS PDU and then calls `File(file_path).delete()`. For a non-PDU file, `getNotificationTask` and `DownloadRequest.persist` return null without throwing, so the delete is reached. Any installed app, with no permissions, could delete files in this app's private storage (the vault database, its wrapped key, settings) | Bytecode (`javap -c`): `lambda$onReceive$0` offsets 9–51 (read), 204 (`persist`), 248 (`File.delete`); exception table shows no handler between them | **Fixed**: `exported="false"`. The download chain is PushReceiver (BROADCAST_WAP_PUSH) → `DownloadManager` → system MMS service → a runtime receiver on a random-UUID action → `BroadcastUtils.sendExplicitBroadcast`, which finds `MmsReceiver` by taskAffinity in our own package and sends an explicit same-package broadcast. Same-package delivery doesn't need `exported`. `MmsSentReceiver` already used this path unexported upstream. Regression test: `ManifestExportTest`. **Release gate:** receive a real MMS on a device before release |
| F-2 | Regex DoS | **Medium.** User-written or imported regex flag rules run on every incoming body. A backtracking pattern (for example `(.*a){12}x`) could stall the receipt path for seconds or longer | `RegexBudgetTest` (a 40-character input that runs unbounded without the fix) | **Fixed**: each regex match runs on a worker with a 100 ms limit. A pattern that runs out is reported invalid (shown in Settings), never treated as a match, and isn't run again in that process. *Inference:* Android's regex engine copies the input natively, so on a device the worker may keep running in the background until it finishes. The receipt path no longer waits for it |
| F-3 | Oversized imports | **Low.** Settings-pack import and `.sovbak` restore read the whole file into memory; a huge file could crash the app (no data loss: restore parses before writing) | Source | **Fixed**: 1 MB limit for settings packs and 32 MB for backups (`core/io/Bounded.kt`, `BoundedTest`) |
| R-1 | MMS runtime receiver | mmslib registers its download receiver as `RECEIVER_EXPORTED` on Android 14+, on a random UUID action, and builds an implicit `PendingIntent` for it | Bytecode (`DownloadManager`: `registerReceiver(…, 2)`) | Accepted (upstream library). The action is unguessable and single-use; changing it means patching the library |
| R-2 | `FossifyBroadcastReceiver` | Exported, no permission; re-reads theme settings | Merged manifest | Accepted; colours only |
| C-1 | Clipboard | The one-time code is copied only on a tap, marked `EXTRA_IS_SENSITIVE` on Android 13+, and validated as 4–8 digits | Source (`CopyCodeReceiver`) | No issue |
| C-2 | Duplicate sends | The availability queue disables Send while a send runs and re-enables it only on failure; nothing sends without a tap | Source (`SovereignQueueActivity.send`) | No issue |
| C-3 | Logging | No `Log`, `println` or `printStackTrace` in Sovereign code; release strips `Log.v/d/i` everywhere | `grep`, R8 rules | No issue. mmslib's own `Log.e`/`Log.w` may log MMS metadata (not bodies) on a device: unverified |
| C-4 | Notifications | Daily summary: silent, private, a count-free public version; with Quick Cover or the app lock on, no counts. Held and quarantine notices are neutral | Source, `DailySummaryTest` | No issue |
| C-5 | Process death, concurrent receipt | Summary counters use single atomic SQL statements; the decision log is append-and-trim; restore writes in one transaction | Source | No issue |
| C-6 | Keystore, corruption | An unreadable vault is renamed aside, never deleted (F-1 was the only delete path found). Keys aren't bound to user authentication, so biometric changes don't invalidate them | Source (`Vault.kt`) | No issue |
| C-7 | Dependencies | The online CVE lookup could not run (the session's network proxy refuses `api.osv.dev`). Versions are in `gradle/libs.versions.toml`. The app has no INTERNET permission, which limits network-borne library bugs | — | **NOT_RUN**: owner to enable GitHub dependency alerts or run a scanner |
| C-8 | R8 | Release-only failures are possible where code is reached by reflection. The keep rules cover SQLCipher, mmslib and kotlinx.serialization (its own consumer rules) | Release build assembled | Device check of backup/restore on the **release** APK is a release gate |
| C-9 | SMS role | When the default-SMS role is revoked, receipt stops (upstream behaviour) and so does summary counting; nothing is lost | Source | Device check in the acceptance list |

## Known gaps

- **MMS receipt after F-1** must be confirmed on a device.
- **System block list.** *Inference:* numbers on Android's own block list may be dropped before any app sees them. Sovereign's Quarantine list is the preserving alternative, and the app warns before you block at OS level. Needs a device test.
- **App lock.** Upstream's lock covers every app screen (Fossify `BaseSimpleActivity.onResume` → `maybeLaunchAppUnlockActivity`, checked by disassembly). It hasn't been re-verified on a device, and it doesn't offer the device screen lock as a method.
- **Encryption.** Carrier SMS is not end-to-end encrypted, and this app doesn't change that.
- **Signing.** The release key used here is throwaway. Production needs the owner's keystore and a pinned certificate digest (`BUILD_AND_SIGNING.md`).
