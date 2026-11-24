# Security review: P0/P1 debug build

Date: 2026-11-24. Scope: `sovereign-sms-1-foss-debug.apk` built from this branch. Method: the merged manifest (`aapt2 dump`) and a source read of the Sovereign hooks. No dynamic or device testing has been done.

## Network and data flow

- **No `INTERNET` permission.** The app can't open network connections. MMS uses the carrier's MMS APN through the system, as upstream does.
- No analytics, crash reporting or telemetry. None upstream either.
- Messages stay in the Android Telephony provider. Sovereign stores only settings, rules and per-conversation flags, in the private SharedPreferences file `sovereign_sms`. It never stores message bodies.
- **Cloud backup is off** for all app data (`dataExtractionRules`, `fullBackupContent`). Device-to-device transfer is still allowed.

## Permissions (inherited from upstream)

READ/WRITE/SEND/RECEIVE_SMS, RECEIVE_MMS, READ_CONTACTS, READ_PHONE_STATE, CALL_PHONE, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM (scheduled messages), RECEIVE_BOOT_COMPLETED, WAKE_LOCK, READ_SYNC_SETTINGS, USE_FINGERPRINT, and `org.fossify.android.permission.WRITE_GLOBAL_SETTINGS` (Fossify's shared theme settings). All are needed for a default SMS app or for existing upstream features. Removing CALL_PHONE and the Fossify global-settings permission is a candidate hardening step (P2).

## Exported components

- `SmsReceiver`, `MmsReceiver`, `PushReceiver` and `HeadlessSmsSendService` are protected by system-only permissions (BROADCAST_SMS, BROADCAST_WAP_PUSH, SEND_RESPOND_VIA_MESSAGE). Android requires them for a default SMS app.
- `MainActivity`, `NewConversationActivity` (SENDTO/sms: intents), `SettingsActivity`, `ArchivedConversationsActivity`, `RecycleBinConversationsActivity` and the colour launcher aliases are exported as in upstream. Opening one only shows UI; none of them sends anything without user action.
- `FossifyBroadcastReceiver` (exported, no permission). Any app can make it re-read Fossify's shared theme settings. The effect is limited to colours. Candidate for removal (P2).
- `SovereignSettingsActivity` is **not** exported.

## Sovereign-specific behaviour

- **The discard fix.** Upstream silently deleted messages from blocked numbers and keywords, and from unknown senders when that blocking was on; mmslib deleted blocked MMS. Sovereign stores them read and archived and doesn't notify. This is reversible by a setting. It satisfies "never silently discard".
- **Rules never touch storage**, only how a message is announced. Any failure in the rules falls back to a normal alert. Corrupt or invalid rule files are rejected, and the stored config falls back to defaults, so the app can't crash.
- **Nothing auto-sends.** No Sovereign code calls a send API.
- **Unknown-sender detection** counts as unknown only when the contact lookup gives a definite "not found". A lookup error counts as known, so the app fails toward alerting.

## Known gaps

- *Inference:* from Android 7 onward, numbers on the system block list (`BlockedNumberContract`) may be dropped by the platform before any app receives them. Upstream's "block number" writes to that list. To truly preserve messages, Sovereign needs its own screen list. That's planned; check it on a device.
- No screenshot protection (FLAG_SECURE) yet. Notification content shows on the lock screen according to Android's own setting.
- The app lock comes from Fossify Commons and hasn't been re-verified.
- Carrier SMS is not end-to-end encrypted, and this app doesn't change that.
- The debug build is debuggable and signed with the debug key. It isn't a release.
