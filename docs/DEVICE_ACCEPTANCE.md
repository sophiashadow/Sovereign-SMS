# Physical-device acceptance checklist

To be done by the owner, on the owner's phone, for v0.1.0-alpha.2. **None of these has been run yet.** (First use of alpha.1 found the group defects in [GROUP_CONVERSATIONS.md](GROUP_CONVERSATIONS.md); the group items below re-check them.)

Record each item as PASS, FAIL or NOT_RUN, with the date, the device model, the One UI or Android version and the build's SHA-256. Keep the results somewhere private, not in this repository, if they name a device or carrier. Only a summary belongs in [STATUS.md](STATUS.md).

There are three kinds of evidence, and they are never substituted for each other:

- **E — Emulator.** An Android Virtual Device, with no carrier. It shows UI and storage behaviour only. Emulator "SMS" is injected by `adb emu sms send`, not a carrier message.
- **D — Physical device, no carrier traffic.** Install, UI, storage, lock, backup and restore, and notifications from non-message sources.
- **C — Carrier.** Real SMS or MMS over a real network, between the owner's own phones or numbers only. Never send to anyone else for testing.

Use the **release** APK (signed with the owner's key) for every gate unless the item says debug. Use a second phone or number the owner controls as the counterpart. Use synthetic text only ("Test 1 from Ashford", for example), not real correspondence.

## Before you start

- [ ] D: Current messenger still installed. Its own backup is made, and RCS is off ([INSTALL_AND_ROLLBACK.md](INSTALL_AND_ROLLBACK.md)).
- [ ] D: `scripts/verify-apk.sh` passes on the APK, with the certificate pinned (no NOT_RUN on the pin).
- [ ] D: Installs next to the current messenger with `adb install`. If Advanced Protection blocks it, note that.
- [ ] D: Not yet the default: the app opens and asks for the role. Decline once, and the app still opens without crashing.

## Release gates (all must PASS)

- [ ] **C: MMS receipt (F-1 gate).** With Sovereign as default, receive an MMS with a picture from the owner's other phone. Check that:
  - it downloads;
  - it shows in the thread;
  - the attachment opens;
  - it survives an app restart.
- [ ] C: MMS send with a picture to the owner's other phone; it arrives.
- [ ] C: SMS receive and send, both ways; a notification on the expected channel.
- [ ] D: **Release-build backup and restore (R8 gate).** Make a `.sovbak` with synthetic cards, quarantine and ledger. Then:
  - uninstall and reinstall;
  - restore with Overwrite and check the counts;
  - restore again with Merge: nothing is duplicated.
- [ ] D: The vault survives a reboot and an app update (install a higher versionCode with `adb install -r`). Cards and ledger are intact.
- [ ] D: Switching the default back to the previous messenger shows all messages, including ones received in Sovereign.

## Receipt and notifications

- [ ] C: A one-time code SMS ("Your code is 482913", sent from the owner's other phone) alerts during quiet hours, and Copy code works. On Android 17, note any delay for WebOTP or SMS Retriever-format codes.
- [ ] C: A quarantined sender's message is stored, read and silent, and visible only in the Quarantine workspace.
- [ ] C: A number on the **system** block list: note whether the message reaches the app at all (expected: maybe not).
- [ ] C: Upstream-blocked keyword or number: the message is kept read and archived, not deleted.
- [ ] C: Quiet hours, focus, priority, mute and the cooldown behave as the rule preview predicts. The decision log explains each.
- [ ] D: Lock-screen display: Professional and Unknown show the neutral "New message" version, and Personal follows the channel setting. Note how One UI styles it.
- [ ] D: Focus-ending notice arrives about 5 minutes before the end, including after a reboot during focus.
- [ ] D: Daily summary arrives at the chosen time and is silent. Its public version shows no counts. With Quick Cover on, it shows no counts at all.

## Group conversations

Use only the owner's own lines. A group needs at least two other numbers the owner controls, or it can't be tested, and is NOT_RUN.

- [ ] D: Settings → Sovereign → Check conversations opens, changes nothing, and the copied report holds no number or name.
- [ ] D: A thread the report lists as having an unrecognized address:
  - shows a fallback title, not the raw string;
  - shows "Can't reply to this conversation" in place of the composer;
  - can be renamed, and the name survives an app restart.
- [ ] D: Threads the report lists as possible duplicates are still separate and all messages are still there.
- [ ] C: New group MMS (setting "Send group messages as MMS" on) to two of the owner's lines:
  - every line receives one group message;
  - their replies arrive in the same Sovereign thread;
  - no line receives its own message back.
- [ ] C: A reply from the group's notification: there is no inline Reply action, and tapping opens the group thread.
- [ ] C: The same group with the setting off: each line gets a separate SMS, and replies arrive in one-to-one threads (expected).
- [ ] D: Note whether the report says "Your own number: not known to Android".

## Privacy controls

- [ ] D: App lock covers every screen, including after a re-lock timeout and after screen-off.
- [ ] D: FLAG_SECURE blocks screenshots and blanks the Recents preview.
- [ ] D: Quick Cover tile and toolbar button turn cover on. Turning it off asks for biometrics or the screen lock.
- [ ] D: Note what upstream search and a share-target link can open while cover is on (a known gap).

## Composer and queue

- [ ] D: A template inserts into the composer and does not send.
- [ ] D: A draft-guard match shows the banner, and a flagged send needs a second tap.
- [ ] C: Availability queue: Send sends exactly one message to the owner's other phone on the tap. Skip sends nothing.

## Portability

- [ ] D: Preserve thread: unzip on a computer, and `sha256sum -c manifest.sha256` passes.
- [ ] D: Each unencrypted export (ZIP, settings pack, ledger CSV/MD, message export) shows its warning first. Cancel writes nothing.
- [ ] D: Settings pack import lists its sections, and Undo puts back the previous settings.
- [ ] D: Restore on a second phone, or after a reinstall with different thread IDs. Cards re-link by number, and the rest appear under Cards without a conversation.
- [ ] D: A corrupted `.sovbak` (one byte changed on a computer) and a wrong password are both refused, and nothing changes.

## Rollback

- [ ] D: Set the previous messenger as default again, then uninstall Sovereign (after a `.sovbak`). Messages remain.
