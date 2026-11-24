# Known limitations and next priorities

## Limitations (as of 2026-11-24)

- Nothing has been installed, device-tested or carrier-tested yet. Only JVM tests and a compile have run.
- There's one quiet-hours window in the UI. Extra windows can be imported through JSON and are kept.
- The held summary counts messages but doesn't list them. It clears when the inbox opens.
- Slices A–E are committed on this branch; Slice F (preservation export, `.sovbak`, hardening, release build) is in progress.
- The draft guard warns and asks for a second tap; it never blocks. Pair rules with a window use the thread history in the conversation view and the badge, but not at receipt time (receipt sees only the new message).
- Known/unknown: a sender with a card (alias or stage) is known. Otherwise the inbox compares the title with the number; the receiver uses a Contacts lookup.
- Room schemas are generated to `app/schemas/` but not committed (upstream ignores that folder); vault schema is version 1.
- Templates and guard rules are stored with Android Keystore AES-GCM. If a key is lost, the old ciphertext is kept under `unreadable_*` rather than being overwritten.
- No RCS. Upstream Fossify strings and translations remain; Sovereign strings are English only.
- The debug APK is about 38 MB (uncompressed multi-dex). A minified release will be much smaller.

## Next (P2, waiting for the owner's go-ahead)

1. **Device check** (owner): install side by side, then look at the settings, rule preview and conversation menu. Make it the default SMS app only with a backup and RCS checked (see INSTALL_AND_ROLLBACK).
2. **Own screen list**, so blocked senders are preserved and not dropped by the system block list.
3. **Slice A–F** per the Phase 2/3 mandate: SQLCipher vault and contact cards, a bidirectional pattern engine (incoming red flags, ideas only, no code from private repos), compartmentalization, an availability queue, a schedule engine, forensic export and `.sovbak` backup, and a signed release.
5. **Private notes** in SQLCipher (a maintained library, with a key wrapped by Android Keystore), plus FLAG_SECURE and a notification-content toggle.
6. **Signed release process**: owner-held keystore, pinned certificate digest, R8, notices shipped in the APK. Needs owner authorisation.

## Slice C follow-ups (2026-11-24)

- The app lock is upstream's (PIN, pattern or biometrics). It does not offer the system screen lock as a method; Quick Cover's exit prompt does (BIOMETRIC_WEAK or DEVICE_CREDENTIAL). Unifying the two would mean replacing upstream's lock screen, which was not done.
- Quick Cover filters the inbox and holds alerts. Upstream search and direct thread links (shortcuts, share targets) can still open a Professional thread while cover is on; not gated yet.
- With no screen lock or biometrics enrolled on the device, leaving Quick Cover needs no authentication.
- Lock-screen behaviour of the neutral public notification and the Copy code action needs a device test (One UI may restyle both).

## Slice E follow-ups (2026-11-24)

- The focus-ending notice uses an inexact alarm window (about a minute wide) and is not rescheduled after a reboot; focus itself still ends on time because the engine compares the clock.
- The decision log is global (thread id shown), not yet per-conversation from the thread menu.
- Ledger figures are whatever the owner types; nothing is read from messages. Hours are capped at 24 per field.
- Schedules are edited as text; a visual builder can come later if text proves awkward on the phone.
