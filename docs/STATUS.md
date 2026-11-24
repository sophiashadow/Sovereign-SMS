# Feature status register

Updated 2026-11-24. Each label is claimed only with its own evidence:

- **implemented**: code is in the repo.
- **compiled**: `assembleFossDebug` succeeded (and, from Slice F, `assembleFossRelease`).
- **tested**: JVM unit tests pass.
- **device**: verified on a phone. **Nothing has reached this state yet.**

| Area | Feature | State |
|---|---|---|
| P0 | Pinned upstream 1.10.3 with history | implemented |
| P0 | Distinct ID `com.sovereignops.sms` (`.debug` for debug builds), name, icon | compiled |
| P0/A | `allowBackup=false`; cloud backup excludes all data; device-to-device transfer excludes the vault DB, its journals, the wrapped vault key and sealed settings | compiled |
| P0 | Reproducible debug build | compiled (local; CI not set up) |
| P0 | Install / rollback docs | written, **not exercised on a device** |
| B/C | Screened SMS and MMS kept (read and archived) instead of deleted | compiled; decision logic tested |
| C | Rules engine (precedence, quiet hours across midnight, focus, priority, mute, unknown sender, keywords and tags, cooldown, one-time codes) | tested (20 tests) |
| C | Workspace modes (All / Personal / Professional / Focus), quiet hours scoped by category, off-duty action | tested (5 rules + 4 workspace) |
| C | Top-bar mode chips filter the inbox | compiled |
| C | Per-category notification channels (Professional/Unknown hide lock-screen content by default) | compiled; channel choice tested |
| D | Pre-send draft guard: keyword/regex/pair rules, empty by default, Keystore-encrypted, non-blocking amber banner | tested (7); banner compiled |
| D | Response library: insert at cursor from the conversation menu, never sends; Keystore-encrypted | tested (4); menu compiled |
| D | Pack import/export (`sovereign-sms/pack` v1: rules + guard + templates), neutral starters off by default | tested (5 + template file) |
| C | Rules JSON codec: rejects bad files and never crashes | tested (3) |
| B | One pattern engine both ways: FORBIDDEN_TERM, PAIR_MATCH (same text, or A in the thread's last N messages), CONTEXT_GUARD (card stage); `#off`; options after ` \| ` | tested (BidirectionalGuardTest 9) |
| B | Incoming red-flag badges (INFO / CAUTION / HIGH_RISK) with matched text; triage: Quarantine & mute (reversible), insert deflection template (never sends), dismiss match (stored in vault) | compiled |
| B | Opt-in receipt actions per flag: silent, tag, category (only if unset). Never deletes, forwards or replies | silence/tags tested in the engine (3); receipt wiring compiled |
| B | Confirm-send gate: a flagged draft needs a second tap or a long-press within 8 s; can be switched off | compiled |
| B | Preset packs, added switched off: Solo Operator — Boundary & Screening Starter; Unsolicited Link & Scam Signals; Shared-SIM Quiet Hours Starter | tested (neutral, disabled, regexes compile, synthetic samples) |
| B | Pack schema: `flags` section and guard `window`/`stages`/`severity`/`silent`/`tag`/`category`; unknown enum names reject the file | tested |
| B | Rule Sandbox: the existing test-a-message preview now shows incoming flags, tags and stage-dependent guard results | compiled |
| C | Text editing of keyword and priority lists (including disabled rules) | tested (5) |
| C | Quiet channel, held summary notification, summary cleared when the inbox opens | compiled |
| C | Settings screen: toggles, focus, quiet window, actions, cooldown, lists, preview, JSON import/export | compiled |
| C | Per-conversation Follow rules / Priority / Muted | compiled |
| B | Per-conversation category (Automatic / Personal / Professional / Unknown) | compiled; resolution tested |
| B | Tags, notes, pins | not started (upstream has pins) |
| E | Precedence: Emergency/OTP (one-time code, break-through keyword) > Personal/Priority (priority contact or conversation; personal conversation when quiet hours exclude personal) > thread mute > focus (timed, Focus workspace, off duty) > quiet hours, then schedule rules > unknown sender, quiet keyword, silent flag, cooldown. Every other match is listed as "Also matched" | tested (PrecedenceTest 7, including an exhaustive tier check) |
| E | Schedule rules: text editor in Settings (`Name: days HH:MM-HH:MM categories action`, `#off` to disable; bad lines listed, not dropped silently) and pack field `rules.schedules`; Shared-SIM preset adds 3 disabled schedules | tested (ScheduleTextTest 4, PackTest); editor compiled |
| E | "Why did this happen?" decision log: rule, outcome and explanation per incoming message (never body or address), kept to the last 200 in the encrypted vault; viewer and Clear in Settings | compiled; explanation content tested through the engine |
| E | Timed focus 30 / 60 / 90 min or until a chosen time; a silent, private "focus ends soon" notice about 5 min before the end (inexact alarm window, no exact-alarm permission); turning focus off cancels it | timing tested (FocusTimerTest); alarm and receiver compiled. The notice does not survive a reboot |
| E | Ledger (Settings → Ledger): date (day of week derived), room cost, clients booked, clients showed, gross, session hours, overhead hours, note. Show rate, Net and BCR = (gross − room cost) / (session + overhead hours) derived; totals, session share and per-weekday workload; CSV (RFC 4180) and Markdown export through the system file picker | metrics and exports tested (LedgerTest 5, synthetic figures); screen compiled |
| C | Daily summaries | not started |
| D | Template categories (Initial Info, Screening Gate, Verification Request, Deflection / Decline, Booking Confirm, Reconfirm, Follow-Up) and favorites; text form `Title \| category=x favorite`; pack fields `category`, `favorite` (unknown category rejects the file) | tested (QuickReplyTest 4, PackTest 2) |
| D | Quick-reply chip bar above the composer: favorites insert directly, category chips open that list; hidden with no templates. Inserting never sends; the draft guard and confirm-send gate check the result like typed text | compiled |
| D | Placeholders `{alias}`, `{time_window}` from the client card; `{date}` and anything missing asked for (date prefilled with today); other braces untouched | tested |
| D | Template CRUD in Settings (text editor); import/export through the existing rules pack (SAF) | tested (codec); UI compiled |
| D | Availability queue (main menu): opt-in cards only, longest-uncontacted first, cooldown hits (7 days / 4 in 30 days) flagged and sorted last; one card at a time with Send / Skip / Edit; a cooldown or guard warning needs a second tap; Send calls upstream `sendMessageCompat` only on that tap, then records the ping and last contact | ordering and recording tested (AvailabilityQueueTest 4); review screen compiled; **no message has been sent from it** |
| A | Vault: Room + SQLCipher 4.20.0, random 32-byte key wrapped by a Keystore AES-GCM key; an unreadable vault is set aside (renamed, never deleted) and a notice shown | compiled; 16 KB ELF alignment and `zipalign -P 16` verified on the APK |
| A | Client card (header tap / menu): local alias (never written to Contacts), screening stage, renamable checklist (`#off`, "Deposit / Terms confirmed" off by default), rate tier, time window, availability opt-in, encrypted notes, derived first/last/count | compiled; model tested (CrmTest) |
| A | Inbox stage dot + alias; alias in notifications (switchable); alias/stage makes a sender known | compiled; resolution tested |
| A | App Quarantine list (vault table, card toggle) separate from Android's block list; warning before OS blocking and before the system block manager | compiled (receive path is Slice C) |
| C | Quarantine receive path: quarantined senders are always received, stored, marked read and posted to a silent secret channel; checked before every other rule, even with rules off | decision tested (QuarantineCoverTest); receive wiring compiled |
| C | Quarantine workspace mode (inbox chip): shows only quarantined threads; quarantined threads hidden in every other mode | tested (QuarantineWorkspaceTest); chip compiled |
| C | Channels: Personal, Priority / Regular (priority or card stage Regular), Professional and Unknown (neutral "New message" public version on the lock screen), Codes, Quarantine (min importance, secret, no sound/vibration/badge) | channel choice tested; channels compiled |
| C | "Copy code" notification action on one-time codes (4–8 digits nearest the keyword; clipboard marked sensitive on API 33+); switchable | extraction tested; action compiled |
| C | App lock: **upstream's** lock (Settings → Password protection: PIN, pattern or biometrics) reused, not duplicated. Sovereign adds the re-lock timeout (Immediately / 1 min / 5 min / Screen off) and locks on screen-off for Immediate and Screen-off modes | timeout mapping tested (LockPolicyTest); wiring compiled. Upstream's lock does not offer the system screen lock (device credential) as a method |
| C | FLAG_SECURE toggle (blocks screenshots and the Recents preview) on every activity | compiled |
| C | Quick Cover: toolbar button, settings switch and Quick Settings tile. On: inbox forced to Personal, Professional/Unknown alerts held, Quarantine hidden; held notifications cleared. Off, or switching to another mode chip: biometrics or device screen lock (BiometricPrompt, BIOMETRIC_WEAK or DEVICE_CREDENTIAL) | engine part tested (QuarantineCoverTest); UI and tile compiled. Without any device lock set, leaving cover needs no authentication |
| G | Workload insights (Settings → Workload insights): last 30 days of received/sent counts, busiest hour and weekday, reply times (median and 90th percentile, nearest rank, replies within 24 h; slower ones counted separately), threads waiting for a reply. Computed on demand from thread, direction and time only (new DAO query `getStamps`); no body or address read, nothing stored. Uses the app's local message cache, so threads never opened in this app may be missing | tested (InsightsTest 6); dialog compiled |
| F | Preserve thread (thread menu → Preserve thread): ZIP with `thread.md` (chronological, quoted, markup neutralised), exact `messages.json`, attachments (each capped at 64 MB; unreadable ones listed as missing), `manifest.sha256` last in `sha256sum` format; read back and verified after writing | tested (ThreadArchiveTest 4); screen compiled |
| F | `.sovbak` encrypted backup / restore (Settings): PBKDF2-HMAC-SHA256 600k + AES-256-GCM, header as AAD; card, checklist, quarantine, ledger and rules pack; MERGE (keeps this phone's data) or OVERWRITE (asks to confirm); decrypt before any change, one transaction; re-opened after writing. Restored cards re-link to this phone's conversations by number; a card with no matching conversation (or whose conversation already has a card) is kept *unlinked* (negative id, numbers stored, vault schema 2) and never attached to an unrelated thread or offered in the availability queue | tested (SovBakTest 4 + BackupContentTest 4, real 600k round trip; AvailabilityQueueTest); schema 1→2 migration (additive column) checked against Room's generated schemas on stock SQLite, not on a device; screen compiled |
| F | Cards without a conversation (Settings): restored cards that match no conversation are retried by number on opening; the rest can be linked by hand to a conversation that has no card, or deleted after a confirmation. Never overwrites an existing card | matching and planning tested (RelinkTest 3, also used by restore); screen compiled |
| F | Shipped notices: Settings → Open-source notices (SQLCipher BSD-3-Clause, LibTomCrypt Unlicense, Apache-2.0) from `assets/licenses/` | compiled |
| F | R8 keep rules (SQLCipher, MMS), `Log.v/d/i` stripped in release, screen-off receiver `RECEIVER_NOT_EXPORTED` | release compiled; no device run |
| F | Signed release APK `com.sovereignops.sms` | built and `apksigner`-verified with a **throwaway test key** (not kept); 16 KB `zipalign -P 16` and ELF 0x4000 verified. Owner-key signing not done |
| F | Android 17 / One UI audit (`ANDROID17_ONEUI_AUDIT.md`) and release security review (`SECURITY_REVIEW.md`) | written; 3 open device questions |

Test command: `./gradlew testFossDebugUnitTest`. The last run had 138 tests with 0 failures and 0 errors.
