# Sovereign SMS

A private SMS/MMS app for Android, for independent service providers, freelancers and anyone who wants their messages organised on their own terms.

- It runs on the phone only. It has **no INTERNET permission** and sends no telemetry.
- Its own records (contact cards, rules, ledger) are kept in an encrypted local vault.
- Messages themselves are ordinary carrier SMS and MMS. They are **not end-to-end encrypted**, and this app doesn't change that.

It is a fork of **[Fossify Messages](https://github.com/FossifyOrg/Messages)** 1.10.3 (GPL-3.0). See [docs/PROVENANCE.md](docs/PROVENANCE.md). Version: **0.1.0-alpha.1**. It has not yet been tested on a phone; see [Known limitations](docs/KNOWN_LIMITATIONS.md).

## What it adds to Fossify Messages

**Nothing is silently discarded.**
- Messages from blocked numbers, blocked keywords and unknown senders, which upstream deletes, are kept read and archived.
- A separate **Quarantine** list keeps a sender's messages silently in their own workspace instead of blocking them.

**Notifications on your terms.** Local rules decide how a message is announced, never whether it is stored. They cover:
- quiet hours, focus (timed or until a chosen time) and schedules;
- priority numbers and conversations, mutes and unknown senders;
- keyword rules with tags, a cooldown, and one-time codes that always alert, with a Copy code action.

Each decision is explained ("Why did this happen?"), and you can test rules against a sample message first. Workspace modes (All, Personal, Professional, Focus, Quarantine) filter the inbox, and each category has its own notification channel.

**Contact cards.** Each card is stored in the vault and never written to Contacts. It holds:
- a private alias, a stage, a checklist, a tier and a time window;
- notes;
- an opt-in availability list with contact history.

**Pattern rules, both ways.**
- The **draft guard** warns above the composer when an outgoing draft matches your own keyword, regex, pair or stage rules. It never blocks: a flagged send needs a second tap, and the gate can be switched off.
- **Incoming flags** badge matching messages with what matched, plus optional triage actions.

Neutral preset packs are included and start switched off.

**Templates and quick replies.**
- Categorised templates and favourites, on a chip bar above the composer.
- Placeholders (`{alias}`, `{time_window}`, `{date}`) are filled from the card or asked for.
- Inserting a template **never sends**.
- An availability queue lets you review opted-in cards one at a time. Nothing goes out without your tap on Send.

**Privacy controls.**
- Upstream's app lock, with a re-lock timeout.
- FLAG_SECURE, which blocks screenshots and the Recents preview.
- Neutral lock-screen notifications.
- **Quick Cover**, which shows only personal conversations and holds the rest until you authenticate.

**Ledger.** A daily record you type yourself. Labels can be customised. It works out:
- net realized cash (received − refunds − expenses);
- net cash per labor hour.

Receivables and restricted credits are recorded but never counted as cash. Exports are CSV and Markdown. See [docs/LEDGER.md](docs/LEDGER.md).

**Insights and summaries.**
- Workload insights cover counts, busiest times, reply times and threads awaiting a reply. They're computed from timestamps only.
- An optional daily summary notification gives the same counts.

**Backup and preservation.**
- Encrypted `.sovbak` backup of Sovereign's records (AES-256-GCM, PBKDF2-HMAC-SHA256). Restore merges or overwrites and re-links cards by number on a new phone.
- A checksummed per-thread preservation ZIP.
- A portable settings pack (rules, guard, templates, flags) with confirm and undo on import.
- Every unencrypted export warns first. See [docs/BACKUP_RESTORE.md](docs/BACKUP_RESTORE.md).

**Its own identity.** It uses the app ID `com.sovereignops.sms`, so it installs alongside your current messenger. Android cloud backup is off.

Feature state, with evidence: [docs/STATUS.md](docs/STATUS.md).

## Documentation

| | |
|---|---|
| [STATUS.md](docs/STATUS.md) | Feature status register |
| [KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | What doesn't work or hasn't been tested |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the code is organised |
| [THREAT_MODEL.md](docs/THREAT_MODEL.md) | What the app protects against, and what it doesn't |
| [SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md) | Release security review and findings |
| [PRIVACY.md](docs/PRIVACY.md) | Privacy policy |
| [BACKUP_RESTORE.md](docs/BACKUP_RESTORE.md) | Backup, restore and every export format |
| [LEDGER.md](docs/LEDGER.md) | Ledger definitions |
| [BUILD_AND_SIGNING.md](docs/BUILD_AND_SIGNING.md) | Build, production signing, verification, reproducibility |
| [INSTALL_AND_ROLLBACK.md](docs/INSTALL_AND_ROLLBACK.md) | Install next to your messenger, switch, roll back |
| [DEPLOY_SAMSUNG_ONEUI.md](docs/DEPLOY_SAMSUNG_ONEUI.md) | Samsung One UI specifics |
| [DEVICE_ACCEPTANCE.md](docs/DEVICE_ACCEPTANCE.md) | Physical-device acceptance checklist |
| [ANDROID17_ONEUI_AUDIT.md](docs/ANDROID17_ONEUI_AUDIT.md) | Android 17 and One UI audit |
| [PROVENANCE.md](docs/PROVENANCE.md) | Upstream and third-party provenance |
| [CHANGELOG.md](CHANGELOG.md), [release notes](docs/release/RELEASE_NOTES_0.1.0-alpha.1.md) | Changes |

## Build

```sh
./gradlew testFossDebugUnitTest lintFossDebug assembleFossDebug
# APK: app/build/outputs/apk/foss/debug/sovereign-sms-1-foss-debug.apk
```

You need:

- JDK 17 or newer;
- Android SDK platform 36 and build-tools 36.0.0 (`local.properties`: `sdk.dir=...`);
- network access to Google Maven, Maven Central and jitpack.io.

Release signing and verification are in [docs/BUILD_AND_SIGNING.md](docs/BUILD_AND_SIGNING.md).

## Install and roll back

Read [docs/INSTALL_AND_ROLLBACK.md](docs/INSTALL_AND_ROLLBACK.md) before making Sovereign SMS your default SMS app. On Samsung, also read [docs/DEPLOY_SAMSUNG_ONEUI.md](docs/DEPLOY_SAMSUNG_ONEUI.md). Turn off RCS first, and keep your current messenger installed as a fallback.

## Licence

GPL-3.0, the same as upstream (see [LICENSE](LICENSE)). Fossify's copyright notices are kept in the source.
