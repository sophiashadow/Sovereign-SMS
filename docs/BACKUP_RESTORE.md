# Backup, restore and export formats

Sovereign SMS keeps **messages** in Android's own message store (the Telephony provider) and its **own records** (cards, quarantine list, ledger, settings) in private app storage. These are backed up by different mechanisms, and no single file holds both. Android cloud backup is off for this app (`allowBackup="false"`), so nothing is backed up unless you make one of the files below.

| File | Made from | Encrypted | Holds messages | Holds Sovereign records |
|---|---|---|---|---|
| `.sovbak` | Settings → Sovereign SMS → Encrypted backup | **yes** (password) | no | yes (cards, quarantine, ledger, settings pack, checklist) |
| Settings pack `.json` | Settings → Sovereign SMS → Export settings pack | **no** | no | settings only |
| Thread preservation `.zip` | Conversation menu → Preserve thread | **no** | one conversation, in full | no |
| Ledger `.csv` / `.md` | Ledger → Export CSV / Export Markdown | **no** | no | ledger only |
| Message export `.json` | Settings → Export messages (upstream Fossify) | **no** | yes, the ones you choose | no |

Every unencrypted export shows a warning first, saying what the file contains. It never blocks: Continue always goes ahead. Where a file is saved is chosen by you in the system file dialog. Nothing is uploaded: the app has no INTERNET permission.

## `.sovbak`: encrypted backup of Sovereign's records

**Container.** One line of JSON header, then a newline, then the ciphertext.

- The header holds:
  - `format: "sovereign-sms/sovbak"`;
  - `version: 1`;
  - the KDF, iteration count, salt and cipher;
  - the nonce;
  - the creation time.
- The ciphertext is AES-256-GCM, with a key from PBKDF2-HMAC-SHA256 (600,000 iterations, 16-byte random salt) and a 12-byte random nonce.
- The exact header bytes are authenticated, so editing any header field makes the file fail to open.
- Passwords are at least 10 characters. There is no recovery without the password.

**Contents** (`format: "sovereign-sms/backup"`, content `version: 2`):

| Section | What it is |
|---|---|
| `pack` | The settings pack (below): notification rules, draft-guard rules, templates, incoming flag rules |
| `checklist` | The card checklist item names |
| `cards` | Each card's alias, stage, ticked checklist items, tier, availability opt-in, notes, time window, contact history (`pings`, `lastContacted`), and the conversation's phone numbers at backup time (used to re-link on another phone) |
| `quarantine` | Quarantined numbers and the time each was added |
| `ledger` | Each day: date, counts, hours, note, `basis` (`LEGACY` or `CASH`), and the money fields for that basis (see `LEDGER.md`) |

**Not in a `.sovbak`:**

- messages and attachments;
- contacts;
- Android's own block list;
- the decision log;
- daily-summary counters and summary settings;
- workload insights (they're recomputed from messages);
- dismissed flag matches;
- per-conversation category, follow, priority and mute settings, and receipt tags;
- the workspace mode;
- Quick Cover state;
- the re-lock timeout and FLAG_SECURE switch;
- ledger labels;
- upstream Fossify settings (theme, app lock PIN, pattern or biometrics, blocked numbers and keywords).

Keystore keys never leave the phone, so none are in the file.

### Restore

Settings → Sovereign SMS → Restore backup → pick the file → enter the password.

1. The whole file is decrypted and parsed **before anything changes**. A wrong password or damaged file ("Wrong password or damaged file") or an unsupported version changes nothing.
2. The app shows what the backup holds (cards, quarantined senders, ledger days) and offers two modes:
   - **Merge** keeps everything on this phone and adds what is missing. Settings are unchanged. In detail:
     - **Cards.** A card for a conversation that already has a card here is skipped, so this phone's card wins.
     - **Quarantine.** Both lists are combined; for a number on both, the earlier date is kept.
     - **Ledger.** Days identical to one here are skipped. Two different entries for the same date are both kept: a conflict is never resolved by overwriting.
   - **Overwrite** replaces this phone's cards, quarantine list, ledger and settings with the backup, after a second confirmation. Messages are never touched.
3. The write happens in one database transaction. A failure leaves the vault as it was.

**Different conversation IDs.** Conversation (thread) IDs differ between phones and after a reinstall, so cards are re-linked by **phone number**, not by ID. In detail:

- A restored card goes to the conversation here with the same numbers.
- If no conversation matches, or that conversation already has a card, the card is kept **unlinked**. It gets a negative ID that can never match a real conversation, and it keeps its numbers.
- An unlinked card never attaches to an unrelated conversation that happens to reuse the old ID. It isn't offered in the availability queue, and it's carried into the next backup.
- Settings → Cards without a conversation retries the match by number when it opens. The rest can be linked by hand to a conversation that has no card, or deleted after a confirmation. Linking never overwrites an existing card.

### Versions

| Reading… | This app (content v2, container v1) | An older app (content v1 only) |
|---|---|---|
| content v1 file | Read. Ledger days become `LEGACY` (gross and room cost kept, not reinterpreted as cash) | Read |
| content v2 file | Read | **Refused**: "Unsupported backup content". The older app doesn't silently drop the cash fields |
| content v3+ file | **Refused**: "This backup was made by a newer version of the app … Update the app, then restore." | Refused |
| container v2+ | **Refused**: "This .sovbak was made by a newer version of the app (file version N)…" | Refused |
| unknown fields at a supported version | Ignored | Ignored |
| an unknown ledger `basis` | Refused, naming the date | — |

Files larger than 32 MB are refused before parsing.

## Settings pack (`sovereign-sms-pack.json`): portable configuration

Plain JSON: `{"format": "sovereign-sms/pack", "version": 1, "rules": …, "guard": […], "templates": […], "flags": […]}`. It holds **configuration only**:

- notification rules (quiet hours, focus, priority numbers, keyword rules, schedules, cooldown and so on);
- draft-guard rules;
- templates;
- incoming flag rules.

It holds **no** messages, cards, quarantine list, ledger, checklist or per-conversation settings. This is the file to share between your own phones or keep as a template. [owner-rules.template.json](owner-rules.template.json) is a blank example.

**Import.** Each section present in the file replaces that section on the phone; sections absent from the file are kept.

- Before anything changes, the app names the sections it will replace and asks.
- After the import it offers **Undo import**, which puts back a full snapshot taken just before.
- A file that's invalid, larger than 1 MB, uses a newer pack version, or contains unknown rule kinds is rejected whole, so nothing is half-imported.
- A plain rules file (`sovereign-sms/rules`) imports as a pack holding only rules.

## Thread preservation ZIP

A preservation copy of one conversation:

| Entry | What it is |
|---|---|
| `thread.md` | Human-readable transcript, oldest first, with markup neutralised |
| `messages.json` | The exact message fields (direction, time, numbers, body, read state, MMS parts) |
| `attachments/` | Each attachment, capped at 64 MB each. Unreadable ones are listed as missing |
| `manifest.sha256` | `sha256sum` lines for every entry above, written last |

After writing, the app reads the ZIP back and verifies every checksum. Check it yourself with `unzip` and then `sha256sum -c manifest.sha256`.

The ZIP is **not encrypted** and contains the full text and numbers. Keep it in a location you trust. Encrypting it would mean making a `.sovbak`-style container for it, which doesn't exist yet. Sovereign card data isn't included.

## Ledger CSV and Markdown

- **CSV.** RFC 4180, oldest first.
  - The first twelve columns are unchanged from the original export: `date`, `day_of_week`, `room_cost`, `clients_booked`, `clients_showed`, `gross`, `session_hours`, `overhead_hours`, `note`, `show_rate`, `net`, `bcr`. `net` and `bcr` are filled for legacy days only.
  - Nine cash columns follow: `basis`, `cash_received`, `refunds`, `expenses`, `receivables`, `restricted_credits`, `net_realized_cash`, `labor_hours`, `net_cash_per_labor_hour`.
  - A figure that doesn't apply is empty, never `0`.
- **Markdown.** A cash-basis table, then totals, legacy days in a table of their own, and a by-weekday table.

Both are unencrypted and contain your figures and notes. Definitions are in [LEDGER.md](LEDGER.md).

## Messages themselves

Messages stay in Android's message store, which is shared by whichever app is the default SMS app. Switching back to your previous messenger shows them all. To copy messages out:

- **Settings → Export messages** (upstream Fossify): plain JSON of the SMS and/or MMS you choose. Import it with Settings → Import messages, here or in Fossify Messages.
- **Preserve thread**, per conversation (above).
- Your previous messenger's or the phone maker's own backup.

None of these are end-to-end encrypted, and ordinary carrier SMS never is.
