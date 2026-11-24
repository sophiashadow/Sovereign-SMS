# Ledger: definitions

Settings → Ledger is a daily record you type yourself. Nothing in it is read from messages. All figures stay on the phone in the encrypted vault unless you export them.

## Day types

Every day has a **basis**, stored with it.

- **Cash** is the basis for every new day.
- **Legacy** applies to days recorded before the cash fields existed (vault schema 2 or earlier, or a content-v1 backup). They keep their original fields and their original formula. The app never converts them, because their "gross" might have included money that was never received. Editing a legacy day keeps it legacy.

## Cash days

| Field | Meaning |
|---|---|
| Received | Cash actually received that day |
| Refunds | Cash paid back that day |
| Expenses | Cash spent that day and attributable to the work (for example room or travel cost) |
| Receivables | Amounts owed to you but not yet received. **Recorded only; not realized cash** |
| Restricted credits | Deposits, prepayments or credits you can't yet treat as yours. **Recorded only; not realized cash** |
| Booked / attended | Counts of whatever unit you track. The label is yours ("Labels") |
| Service hours | Hours spent delivering the work. The label is yours |
| Overhead hours | All other hours the work took: admin, messaging, travel, preparation |

Derived figures:

- **Net realized cash** = received − refunds − expenses.
- **Labor hours** = service hours + overhead hours.
- **Net cash per labor hour** = net realized cash ÷ labor hours. With zero labor hours it is **undefined**, shown as "—" and left empty in the CSV. It is never shown as 0.
- **Show rate** = attended ÷ booked. Undefined when nothing was booked.

Receivables and restricted credits never enter the net. When a receivable is paid, enter it as Received on the day it arrives.

## Legacy days

They keep their recorded room cost and gross, and their original derived figures:

- net = gross − room cost;
- per hour (formerly "BCR") = net ÷ labor hours.

These are labelled legacy everywhere, and the totals keep them separate from cash figures.

## Totals and weekdays

- Cash totals add up cash days only.
- Net cash per labor hour in the totals is the summed net realized cash ÷ the summed labor hours **of cash days**. Legacy hours are left out of this, so the two bases never mix in one rate.
- Legacy days get their own totals line.
- Counts, hours, show rate and service share (service hours ÷ labor hours) cover all days.
- The by-weekday table uses the same rules per weekday.

## Labels

"Labels" in the ledger renames the counted unit (default: "Appointments") and the service hours (default: "Service hours"). Labels stay on this phone. They are not part of a backup or a settings pack, and they don't change stored data or export column names.

## Exports

The CSV and Markdown formats are described in [BACKUP_RESTORE.md](BACKUP_RESTORE.md#ledger-csv-and-markdown). Both are unencrypted, and the app warns before writing them.

## Storage history

| Vault schema | Change |
|---|---|
| 1 | Ledger table: date, room cost, booked, showed, gross, session hours, overhead hours, note |
| 2 | (cards only) |
| 3 | Adds `basis` (existing rows → legacy) and the cash fields received, refunds, expenses, receivables and restricted credits, all defaulting to 0. Purely additive: no column is changed or dropped (`VaultMigrationTest`) |
