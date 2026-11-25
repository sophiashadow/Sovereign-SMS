# Group conversations and unrecognized addresses

Investigation of a finding from the owner's phone on 2026-11-25:

- some conversations were titled with long, opaque strings of letters and digits instead of names;
- some group conversations seemed to be split across more than one thread.

Other groups looked normal. Each statement below is labelled **fact** (read in this repository's code, or in the `mmslib` 1.0.1 bytecode), **inference**, or **unverified**. No message content or real address was collected. The tests use synthetic values only.

## How Android stores a thread

**Fact.** An SMS app doesn't own its threads. Android's shared message store (the Telephony provider) does. Each thread row has `recipient_ids`, a list of IDs into the `canonical_addresses` table, and each of those rows holds one address string. Any app that writes to the store can put any string there. Fossify, and Sovereign SMS, read `recipient_ids` → `canonical_addresses` (`getThreadRecipientIds`, `getThreadPhoneNumbers`, `getPhoneNumberFromAddressId` in `extensions/Context.kt`), and treat each string as a phone number.

## Root causes

### 1. Opaque titles: fact

- `getPhoneNumberFromAddressId` returns the stored string as it is.
- `getThreadContactNames` (and `getNameAndPhotoFromPhoneNumber`) look the string up in contacts. When nothing matches, they fall back to the string itself.
- `getConversations` and `getThreadTitle` join those names with commas.

Nothing checked whether the string was a phone number. So any non-phone identifier in `canonical_addresses` became the title, character for character. A recipient ID with no `canonical_addresses` row came back as `""`, which left blank names in titles (`"Ashford, "`).

A second effect: `Addresses.sameNumber`, used by priority rules and card re-linking, compared only the digits. It could match the digits inside an ID like `rcs9195550142x` to a real number.

### 2. Where the opaque strings come from: inference, unverified on this phone

The likeliest source is another messaging app, Google Messages or Samsung Messages, writing RCS chat or group identifiers into the shared store. RCS messages themselves stay in that app's private database, so no third-party SMS app can read them.

The code is unchanged from Fossify 1.10.3, so upstream behaves the same way. That fits the r/degoogle and r/fossdroid reports. Those threads could not be fetched from the build container, because reddit is blocked.

The **Check conversations** report (below) shows the shape of each such address, without the address itself, so the owner can confirm the shape on the phone.

### 3. Opaque addresses used as send destinations: fact

- Thread participants went straight to `sendMessageCompat`.
- `ThreadActivity` hid the composer only when an address contained a letter. That check, `isShortCodeWithLetters`, was meant for alphanumeric short codes. An ID made only of digits, or a blank address, passed it.
- The notification reply, scheduled sends, the availability queue and the headless send service had no check at all.

### 4. Fragmented groups: three code paths

- **a. Notification reply in a group (fact).** An MMS notification carries the sender's address. `DirectReplyReceiver` replied to that one address, so a reply typed in a group's notification went to the sender alone, as an SMS. Android filed it in a separate one-to-one thread.
- **b. Your own number in incoming groups (fact in `mmslib`; device behaviour inferred).**
  - `PduPersister` builds the recipient set of an incoming MMS from From, To and Cc. It drops your own number only when it matches `TelephonyManager.getLine1Number()` (by `PhoneNumberUtils.compare`).
  - Many SIMs and eSIMs don't give Android their number. Then you stay in the set, and Android files the message under a different thread (A, B and you) than the one your own sends use (A and B).
  - Replying in that thread also sends to you. Upstream issue [FossifyOrg/Messages#699](https://github.com/FossifyOrg/Messages/issues/699) reports this, after a group was created in Google Messages. It is open and unfixed upstream.
- **c. Group sends as separate SMS (fact; upstream by design).**
  - "Send group messages as MMS" is off until you choose, and the app asks on the first group send.
  - With it off, each person gets their own SMS, and their replies arrive in one-to-one threads.

**Ruled out.** Stale participants after recipients change (upstream #615, fixed by #812) are already fixed in this code (`reconcileProviderParticipants`). Format variants of one number (`+1 919…` and `919…`) are normally merged by Android's own canonical-address matching. That is inference: the AOSP source could not be fetched from the container. The check reports any that aren't.

## What changed

| Area | Change | Evidence |
|---|---|---|
| Classification | `AddressClassifier` sorts an address into phone number (7–15 digits, E.164), short code, email, alphanumeric sender name (≤ 11 characters, GSM limit), URI (`sip:`, `tel:` …), opaque, or empty | `AddressClassifierTest` |
| Sending | Only phone numbers, short codes and emails are sendable. `sendMessageCompat` refuses the **whole** send when any recipient isn't, because sending to the rest would start a different thread. Every send path goes through it. `ThreadActivity` keeps the draft. A scheduled message is kept instead of being deleted as sent | compiled; classifier tested |
| Composer | A conversation with an unrecognized address hides the composer, as upstream does for letter short codes, with its own explanation (`sv_unsupported_thread_desc`): RCS is not supported, the messages are kept, start a new conversation by number | compiled |
| Titles | `ThreadTitle` never shows an unrecognized address. "Ashford, 1 unrecognized address"; "Unsupported conversation"; "Unsupported group (2 unrecognized addresses)". Blank addresses count as unrecognized. A person's label is "Unrecognized address" | `ThreadTitleTest` |
| Rules | `Addresses.sameNumber` compares only phone-shaped addresses | regression test |
| Inbox | A one-to-one thread with an unrecognized address counts as unknown in workspace filtering, even though its fallback title has letters | `WorkspaceTest` regression |
| Notifications | No inline reply in group threads or to unsendable addresses. Tap the notification to reply in the thread | compiled |
| Diagnosis | Settings → Sovereign → **Check conversations**: a read-only report of thread counts, threads with unrecognized addresses (thread number, participant count, shapes such as "UUID-shaped, 36 chars"), possible duplicate threads (same participants once formats and your own number are ignored), and whether Android knows your number. Copy report copies the text, which holds no numbers, names or messages | `ThreadAuditTest` (incl. no-leak test); dialog compiled |

## Unchanged on purpose

- Nothing is merged, moved or deleted. Duplicate threads are listed, and you decide.
- Every stored message stays where it is.
- **Manual rename is upstream's and already works:**
  - thread menu → Rename conversation, or select a conversation in the list → Rename;
  - a renamed title (`usesCustomTitle`) wins over the fallback title and survives refreshes;
  - a Sovereign card alias wins over both in the thread view.
- No RCS support is added or claimed.
- Your own number is never shown or stored. The check only compares it in memory.

## Platform limits that can't be fixed here

- **RCS conversations can't be read or sent by any SMS app.** They live in the other app's private store, and Android has no public RCS API for third-party apps. Turn RCS off in the old messenger before switching ([INSTALL_AND_ROLLBACK.md](INSTALL_AND_ROLLBACK.md)).
- **Your own number in group MMS.** When Android doesn't know the phone's number, `mmslib` can't leave you out of incoming groups, and Sovereign can't reliably detect you either, since it asks Android the same question. Fixing that would mean patching or forking `mmslib`, or adding a way for you to enter your number. Both are outside this stabilization pass.
- **Separate threads Android already created stay separate.** Merging them would rewrite Android's shared store, which other apps also read. It isn't done.
- **Short IDs.** An opaque ID of 11 characters or fewer looks the same as an alphanumeric sender name ("MERIDIAN"). It is shown, but never sendable.
- **"RCS" is a guess.** The app can say an address isn't SMS/MMS. It can't prove that RCS wrote it. The UI says "often".

## Checking it on the phone

1. Install the new build (see the release notes for how to update), then open Settings → Sovereign → **Check conversations**.
2. Copy the report and share it if you want it analysed. It holds shapes and thread numbers only.
3. Open a thread the report lists and confirm:
   - the title no longer shows the raw string;
   - the composer says it can't reply.

   Long-press → Rename to give it a name.
4. For a split group, compare the threads the report pairs. Note whether "your own number" is "not known to Android".
5. The group items in [DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md) cover new MMS groups. They need a carrier and the owner's own second line.
