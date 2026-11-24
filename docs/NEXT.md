# Known limitations and next priorities

## Limitations (as of 2026-11-24)

- Nothing has been installed, device-tested or carrier-tested yet. Only JVM tests and a compile have run.
- There's one quiet-hours window in the UI. Extra windows can be imported through JSON and are kept.
- The held summary counts messages but doesn't list them. It clears when the inbox opens.
- Categories, tags, notes, templates, encrypted annotations, insights, FLAG_SECURE: not started.
- No RCS. Upstream Fossify strings and translations remain; Sovereign strings are English only.
- The debug APK is about 38 MB (uncompressed multi-dex). A minified release will be much smaller.

## Next (P1 continuing, in order)

1. **Device check** (owner): install side by side, then look at the settings, rule preview and conversation menu. Make it the default SMS app only with a backup and RCS checked (see INSTALL_AND_ROLLBACK).
2. **Own screen list**, so blocked senders are preserved and not dropped by the system block list.
3. **Categories and tags**: inbox filter chips (All, Personal, Professional, Unknown, Priority, Muted, Archived), backed by a separate Room DB keyed by thread ID. Add an optional conversation stage (inquiry → screening → confirmed / declined / closed); the owner's provider operations notes suggest this lifecycle.
4. **Response library**: insert into the compose box only, never send. Neutral, empty defaults; the user imports their own text.
5. **Private notes** in SQLCipher (a maintained library, with a key wrapped by Android Keystore), plus FLAG_SECURE and a notification-content toggle.
6. **Signed release process**: owner-held keystore, pinned certificate digest, R8, notices shipped in the APK. Needs owner authorisation.
