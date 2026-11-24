# Privacy policy

Sovereign SMS, version 0.1.0-alpha.1. Effective from its first release.

## Short version

The app collects nothing and sends nothing anywhere except the messages you choose to send. It has no INTERNET permission, no analytics, no crash reporting and no advertising. There is no account and no server.

## What the app handles

- **Messages and contacts.** As your SMS app, it reads, stores and sends SMS and MMS through Android's message store and your carrier, and it reads contacts to show names. These stay on your phone and with your carrier, as with any SMS app.
- **Its own records.**
  - These are cards, notes, the quarantine list, ledger figures, rules, templates, the decision log and daily counters.
  - They're stored on your phone only, in app-private storage. Most are encrypted with keys held by Android Keystore.
  - The decision log and counters never hold message text or numbers.

## What leaves the phone

Only what you send or export:

- messages you send go through your carrier;
- files you export (backup, preservation ZIP, settings pack, ledger, message export) go to the place you pick in the system file dialog. Only the `.sovbak` backup is encrypted, and the app warns before each unencrypted export.

The app is excluded from Android cloud backup.

## Encryption, plainly

Ordinary SMS and MMS are **not end-to-end encrypted**. Your carrier can read them, and so can anyone with access to the carrier's network or your unlocked phone. The app encrypts its own records, not your messages in transit. Nothing here has been independently audited.

## Your control

- You can delete ledger days (long-press) and unlinked cards, and clear the decision log. A card linked to a conversation has no delete button yet: clear its fields by editing it.
- Turning daily summaries off deletes their counters.
- Uninstalling deletes everything the app stored, except messages, which stay in Android's message store for your next SMS app.

## Children

The app isn't directed at children and collects no data from anyone.

## Changes

Changes to this policy are listed in the changelog and shipped with the app's source.

## Contact

[TK: owner to add a public contact channel, or leave it out, before any public release]
