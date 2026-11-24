# Feature status register

Updated 2026-11-24. Each label is claimed only with its own evidence:

- **implemented**: code is in the repo.
- **compiled**: `assembleFossDebug` succeeded.
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
| C | Daily summaries, "why did this alert" log | not started (the preview gives explanations) |
| A | Vault: Room + SQLCipher 4.20.0, random 32-byte key wrapped by a Keystore AES-GCM key; an unreadable vault is set aside (renamed, never deleted) and a notice shown | compiled; 16 KB ELF alignment and `zipalign -P 16` verified on the APK |
| A | Client card (header tap / menu): local alias (never written to Contacts), screening stage, renamable checklist (`#off`, "Deposit / Terms confirmed" off by default), rate tier, time window, availability opt-in, encrypted notes, derived first/last/count | compiled; model tested (CrmTest) |
| A | Inbox stage dot + alias; alias in notifications (switchable); alias/stage makes a sender known | compiled; resolution tested |
| A | App Quarantine list (vault table, card toggle) separate from Android's block list; warning before OS blocking and before the system block manager | compiled (receive path is Slice C) |
| F | Biometric/PIN lock | inherited from upstream Commons (app lock), not re-verified |
| F | Screenshot protection, notification-content visibility | not started |
| G | Workload insights | not started |
| — | Signed release build | not done (no signing key; needs owner authorisation) |

Test command: `./gradlew testFossDebugUnitTest`. The last run (Slice B) had 83 tests with 0 failures and 0 errors.
