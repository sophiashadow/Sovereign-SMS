# Architecture

Sovereign SMS is Fossify Messages 1.10.3 with a separate Sovereign layer. Upstream stays recognisable, so later upstream fixes can be merged.

- Sovereign code lives in `com.sovereignops.sms.*`.
- Upstream code (`org.fossify.messages.*`) is touched only at a few seams. Each edit is marked `// Sovereign SMS`. There are 11 upstream files with such edits: `App`, `Context`, `SmsReceiver`, `MmsReceiver`, `MessagesDao`, `MainActivity`, `ThreadActivity`, `SettingsActivity`, `NotificationHelper`, `BaseConversationsAdapter` and `ThreadAdapter`.

## Layers

```
upstream UI / receivers (org.fossify.messages)
        │  one-line hooks
        ▼
com.sovereignops.sms            Android glue: Sovereign (receipt seam), SovereignStore, SealedPrefs,
                                SovereignLock, SovereignCover, SovereignFocus, SovereignSummary,
                                CopyCodeReceiver, QuickCoverTile
com.sovereignops.sms.ui         Sovereign screens and hooks (settings, card, composer, flags, inbox,
                                ledger, queue, portability, unlinked cards, warnings)
com.sovereignops.sms.vault      Room + SQLCipher vault, entities, migrations, in-memory cache
com.sovereignops.sms.core.*     Pure Kotlin, no Android types, JVM-tested
```

`core` holds the logic and has the tests:

| Package | Responsibility |
|---|---|
| `core.rules` | Rule engine and precedence; quiet hours, focus timer, schedules; rule text and JSON codecs |
| `core.screening` | Keep or discard decision for screened messages (keeps by default) |
| `core.workspace` | Workspace modes and inbox filtering |
| `core.guard` | One pattern engine for the draft guard and incoming flags; regex time budget |
| `core.templates` | Templates, categories, placeholders |
| `core.crm` | Cards, availability queue ordering and cooldown, re-linking by number |
| `core.lock` | Re-lock timeout policy |
| `core.ledger` | Ledger arithmetic and the CSV and Markdown formats |
| `core.insights`, `core.summary` | Workload insights and daily summaries, from timestamps only |
| `core.preserve` | Thread ZIP writer and manifest check |
| `core.backup` | `.sovbak` container (PBKDF2 + AES-GCM, JCA) and backup content (merge, overwrite, versions) |
| `core.pack` | Settings pack codec, sections, starters, presets |
| `core.io` | Size-limited reads for imports |

## Data placement

| Data | Where | Protection |
|---|---|---|
| SMS and MMS | Android Telephony provider (shared with whichever app is default) | Android's own; not encrypted by this app |
| Upstream message cache, settings, app lock | Upstream's database and preferences | As upstream |
| Cards, quarantine, ledger, decision log, dismissals, counters | `sovereign_vault.db` (SQLCipher) | Random 256-bit key wrapped by a non-exportable Keystore AES-GCM key |
| Draft-guard rules, templates, sealed settings | `SealedPrefs` | Keystore AES-GCM |
| Other Sovereign settings and per-conversation flags | `SovereignStore` (private preferences) | App sandbox |

None of this is in Android backup (`allowBackup=false`; the data-extraction rules exclude the vault, its key and the sealed values from device transfer).

## Receipt path

1. `SmsReceiver` or `MmsReceiver` (upstream) parses the message.
2. `Sovereign.screen(...)` decides keep or discard. Sovereign keeps by default, so blocked and unknown messages are stored read and archived.
3. Upstream stores the message in the Telephony provider.
4. `Sovereign.announce(...)` runs `core.rules` (quarantine first, then the precedence tiers), the incoming flags and summary counting. It then picks a channel, or holds or silences the alert, and appends a decision-log entry (rule and explanation only).

If rules fail to load, the message gets a normal alert. Rules never affect storage.

## Sending

Only upstream's send path sends. Sovereign adds the draft-guard banner and the confirm-send gate in the composer. The availability queue calls upstream `sendMessageCompat` only from a Send tap. Templates and placeholders only fill the composer.

## Persistence and migrations

Room exports its schemas to `app/schemas/com.sovereignops.sms.vault.VaultDatabase/`, and they are tracked. The migrations live in `VaultMigrations`, free of Android types, and are only ever additive:

- 1→2 adds `cards.addresses`;
- 2→3 adds the ledger basis and cash columns.

`VaultMigrationTest` checks each migration against the exported schemas. Backup content and settings packs carry their own format and version fields (see [BACKUP_RESTORE.md](BACKUP_RESTORE.md)).

## Build

- Flavours: `foss` (used here) and `gplay` (upstream's store build).
- Debug builds use the `.debug` suffix.
- The release build uses R8, strips `Log.v/d/i`, and has keep rules for SQLCipher and MMS.
- `ManifestExportTest` fails the build if a component is exported without being on the allow-list.

See [BUILD_AND_SIGNING.md](BUILD_AND_SIGNING.md).
