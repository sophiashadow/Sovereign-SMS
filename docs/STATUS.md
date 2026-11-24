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
| P0 | Cloud backup excluded; device-to-device transfer allowed | compiled |
| P0 | Reproducible debug build | compiled (local; CI not set up) |
| P0 | Install / rollback docs | written, **not exercised on a device** |
| B/C | Screened SMS and MMS kept (read and archived) instead of deleted | compiled; decision logic tested |
| C | Rules engine (precedence, quiet hours across midnight, focus, priority, mute, unknown sender, keywords and tags, cooldown, one-time codes) | tested (20 tests) |
| C | Rules JSON codec: rejects bad files and never crashes | tested (3) |
| C | Text editing of keyword and priority lists (including disabled rules) | tested (5) |
| C | Quiet channel, held summary notification, summary cleared when the inbox opens | compiled |
| C | Settings screen: toggles, focus, quiet window, actions, cooldown, lists, preview, JSON import/export | compiled |
| C | Per-conversation Follow rules / Priority / Muted | compiled |
| B | Categories (Personal / Professional / Unknown / Priority / Muted / Archived) as inbox filters | not started (Archived exists upstream) |
| B | Tags, notes, pins | not started (upstream has pins) |
| C | Daily summaries, "why did this alert" log | not started (the preview gives explanations) |
| D | Response library (insertion only, never sends) | not started |
| E | Encrypted annotations (Room + SQLCipher + Keystore) | not started |
| F | Biometric/PIN lock | inherited from upstream Commons (app lock), not re-verified |
| F | Screenshot protection, notification-content visibility | not started |
| G | Workload insights | not started |
| — | Signed release build | not done (no signing key; needs owner authorisation) |

Test command: `./gradlew testFossDebugUnitTest`. The last run had 33 tests with 0 failures.
