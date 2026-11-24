# Known limitations and next priorities

## Limitations (as of 2026-11-24)

- Nothing has been installed, device-tested or carrier-tested yet. Only JVM tests and a compile have run.
- There's one quiet-hours window in the UI. Extra windows can be imported through JSON and are kept.
- The held summary counts messages but doesn't list them. It clears when the inbox opens.
- Tags, notes, encrypted annotations (SQLCipher), insights, FLAG_SECURE: not started.
- The draft guard warns only. A confirm-before-send step is planned for Slice B.
- Known/unknown in the inbox filter is a heuristic: a title with no letters counts as unsaved.
- Templates and guard rules are stored with Android Keystore AES-GCM. If a key is lost, the old ciphertext is kept under `unreadable_*` rather than being overwritten.
- No RCS. Upstream Fossify strings and translations remain; Sovereign strings are English only.
- The debug APK is about 38 MB (uncompressed multi-dex). A minified release will be much smaller.

## Next (P2, waiting for the owner's go-ahead)

1. **Device check** (owner): install side by side, then look at the settings, rule preview and conversation menu. Make it the default SMS app only with a backup and RCS checked (see INSTALL_AND_ROLLBACK).
2. **Own screen list**, so blocked senders are preserved and not dropped by the system block list.
3. **Slice A–F** per the Phase 2/3 mandate: SQLCipher vault and contact cards, a bidirectional pattern engine (incoming red flags, ideas only, no code from private repos), compartmentalization, an availability queue, a schedule engine, forensic export and `.sovbak` backup, and a signed release.
5. **Private notes** in SQLCipher (a maintained library, with a key wrapped by Android Keystore), plus FLAG_SECURE and a notification-content toggle.
6. **Signed release process**: owner-held keystore, pinned certificate digest, R8, notices shipped in the APK. Needs owner authorisation.
