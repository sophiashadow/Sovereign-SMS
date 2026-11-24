package com.sovereignops.sms.vault

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.sovereignops.sms.core.crm.Card
import com.sovereignops.sms.core.crm.Stage

/*
 * Tables of the encrypted vault. App-owned metadata only: no message bodies are ever
 * written here (the trace log stores rule names and explanations, not text).
 */

@Entity(tableName = "cards")
data class CardRow(
    @PrimaryKey val threadId: Long,
    val alias: String?,
    val stage: String,
    val checked: String,
    val tier: String?,
    val optIn: Boolean,
    val notes: String?,
    val timeWindow: String?,
    val pings: String,
    val lastContacted: Long?,
    /**
     * Only for an *unlinked* card (negative id) restored from a backup whose conversation is not on
     * this phone: its numbers as backed up, comma-separated, so the next backup can re-link it.
     * Linked cards leave it null and use the live conversation's numbers. Added in vault schema 2.
     */
    val addresses: String? = null,
) {
    fun toCard() = Card(
        threadId = threadId,
        alias = alias,
        stage = Stage.parse(stage),
        checked = checked.split('\n').filter(String::isNotEmpty).toSet(),
        tier = tier,
        optInAvailability = optIn,
        notes = notes,
        timeWindow = timeWindow,
        availabilityPings = pings.split(',').mapNotNull(String::toLongOrNull),
        lastContacted = lastContacted,
    )

    companion object {
        fun from(c: Card) = CardRow(
            c.threadId, c.alias?.trim()?.ifEmpty { null }, c.stage.name, c.checked.sorted().joinToString("\n"),
            c.tier?.trim()?.ifEmpty { null }, c.optInAvailability, c.notes?.ifBlank { null }, c.timeWindow?.trim()?.ifEmpty { null },
            c.availabilityPings.joinToString(","), c.lastContacted,
        )
    }
}

/** App-level quarantine: kept and silenced, never dropped (unlike Android's system block list). */
@Entity(tableName = "quarantine")
data class QuarantineRow(@PrimaryKey val address: String, val addedAt: Long)

/** A red-flag match the user dismissed on one message. */
@Entity(tableName = "dismissals", primaryKeys = ["messageKey", "rule"])
data class DismissalRow(val messageKey: String, val rule: String)

@Entity(tableName = "trace")
data class TraceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val threadId: Long,
    val rule: String,
    val alert: String,
    val explanation: String,
)

/**
 * Schema 3 added [basis] and the cash columns. Rows saved earlier read basis 0 (legacy) and
 * zero cash: they keep their gross and room cost and are never reported as realized cash.
 */
@Entity(tableName = "ledger")
data class LedgerRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val roomCostCents: Long,
    val booked: Int,
    val showed: Int,
    val grossCents: Long,
    val sessionHours: Double,
    val overheadHours: Double,
    val note: String?,
    /** 0 = legacy (gross and room cost), 1 = cash. */
    @ColumnInfo(defaultValue = "0") val basis: Int = BASIS_LEGACY,
    @ColumnInfo(defaultValue = "0") val cashReceivedCents: Long = 0,
    @ColumnInfo(defaultValue = "0") val refundCents: Long = 0,
    @ColumnInfo(defaultValue = "0") val expenseCents: Long = 0,
    @ColumnInfo(defaultValue = "0") val receivableCents: Long = 0,
    @ColumnInfo(defaultValue = "0") val restrictedCreditCents: Long = 0,
) {
    fun toEntry() = com.sovereignops.sms.core.ledger.LedgerEntry(
        id, java.time.LocalDate.parse(date), roomCostCents, booked, showed, grossCents, sessionHours, overheadHours, note,
        basis = when (basis) {
            BASIS_LEGACY -> com.sovereignops.sms.core.ledger.Basis.LEGACY
            BASIS_CASH -> com.sovereignops.sms.core.ledger.Basis.CASH
            else -> error("unknown ledger basis $basis")
        },
        cashReceivedCents = cashReceivedCents, refundCents = refundCents, expenseCents = expenseCents,
        receivableCents = receivableCents, restrictedCreditCents = restrictedCreditCents,
    )

    fun toDoc() = com.sovereignops.sms.core.backup.LedgerDoc(
        date, roomCostCents, booked, showed, grossCents, sessionHours, overheadHours, note,
        basis = if (basis == BASIS_CASH) com.sovereignops.sms.core.backup.LedgerDoc.CASH else com.sovereignops.sms.core.backup.LedgerDoc.LEGACY,
        cashReceivedCents = cashReceivedCents, refundCents = refundCents, expenseCents = expenseCents,
        receivableCents = receivableCents, restrictedCreditCents = restrictedCreditCents,
    )

    companion object {
        const val BASIS_LEGACY = 0
        const val BASIS_CASH = 1

        fun from(e: com.sovereignops.sms.core.ledger.LedgerEntry) = LedgerRow(
            e.id, e.date.toString(), e.roomCostCents, e.clientsBooked, e.clientsShowed, e.grossCents, e.sessionHours, e.overheadHours, e.note,
            basis = if (e.basis == com.sovereignops.sms.core.ledger.Basis.CASH) BASIS_CASH else BASIS_LEGACY,
            cashReceivedCents = e.cashReceivedCents, refundCents = e.refundCents, expenseCents = e.expenseCents,
            receivableCents = e.receivableCents, restrictedCreditCents = e.restrictedCreditCents,
        )

        /** A restored day. The backup was validated first, so basis is LEGACY or CASH. */
        fun fromDoc(d: com.sovereignops.sms.core.backup.LedgerDoc) = LedgerRow(
            0, d.date, d.roomCostCents, d.booked, d.showed, d.grossCents, d.sessionHours, d.overheadHours, d.note,
            basis = if (d.basis == com.sovereignops.sms.core.backup.LedgerDoc.CASH) BASIS_CASH else BASIS_LEGACY,
            cashReceivedCents = d.cashReceivedCents, refundCents = d.refundCents, expenseCents = d.expenseCents,
            receivableCents = d.receivableCents, restrictedCreditCents = d.restrictedCreditCents,
        )
    }
}

@Entity(tableName = "counters")
data class CounterRow(@PrimaryKey val name: String, val value: Long)

@Entity(tableName = "response_times")
data class ResponseTimeRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val millis: Long)

@Dao
interface VaultDao {
    @Query("SELECT * FROM cards WHERE threadId = :threadId")
    fun card(threadId: Long): CardRow?

    @Query("SELECT * FROM cards")
    fun cards(): List<CardRow>

    @Upsert
    fun putCard(row: CardRow)

    @Query("DELETE FROM cards WHERE threadId = :threadId")
    fun deleteCard(threadId: Long)

    @Query("SELECT * FROM quarantine")
    fun quarantine(): List<QuarantineRow>

    @Upsert
    fun putQuarantine(row: QuarantineRow)

    @Query("DELETE FROM quarantine WHERE address = :address")
    fun removeQuarantine(address: String)

    @Query("SELECT * FROM dismissals WHERE messageKey = :messageKey")
    fun dismissals(messageKey: String): List<DismissalRow>

    @Query("SELECT * FROM dismissals")
    fun allDismissals(): List<DismissalRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun dismiss(row: DismissalRow)

    @Insert
    fun addTrace(row: TraceRow)

    @Query("DELETE FROM trace WHERE id NOT IN (SELECT id FROM trace ORDER BY at DESC LIMIT :keep)")
    fun trimTrace(keep: Int)

    @Query("SELECT * FROM trace ORDER BY at DESC LIMIT :limit")
    fun trace(limit: Int): List<TraceRow>

    @Query("DELETE FROM trace")
    fun clearTrace()

    @Insert
    fun addLedger(row: LedgerRow): Long

    @Query("SELECT * FROM ledger ORDER BY date DESC, id DESC")
    fun ledger(): List<LedgerRow>

    @Query("DELETE FROM ledger WHERE id = :id")
    fun deleteLedger(id: Long)

    @Query("SELECT * FROM counters")
    fun counters(): List<CounterRow>

    @Query("SELECT value FROM counters WHERE name = :name")
    fun counter(name: String): Long?

    @Upsert
    fun putCounter(row: CounterRow)

    // Daily summaries: per-day counters named "sum:<yyyy-MM-dd>:<METRIC>". Two statements, each atomic in SQLite,
    // so concurrent receipts can't lose an increment.
    @Query("INSERT OR IGNORE INTO counters(name, value) VALUES (:name, 0)")
    fun ensureCounter(name: String)

    @Query("UPDATE counters SET value = value + 1 WHERE name = :name")
    fun bumpCounter(name: String)

    @Query("SELECT * FROM counters WHERE name LIKE 'sum:%'")
    fun summaryCounters(): List<CounterRow>

    @Query("DELETE FROM counters WHERE name LIKE 'sum:%' AND name < :floor")
    fun pruneSummaryCounters(floor: String)

    @Query("DELETE FROM counters WHERE name LIKE 'sum:%'")
    fun clearSummaryCounters()

    @Insert
    fun addResponseTime(row: ResponseTimeRow)

    @Query("DELETE FROM response_times WHERE id NOT IN (SELECT id FROM response_times ORDER BY id DESC LIMIT :keep)")
    fun trimResponseTimes(keep: Int)

    @Query("SELECT millis FROM response_times")
    fun responseTimes(): List<Long>

    @Query("DELETE FROM cards")
    fun clearCards()

    @Query("DELETE FROM quarantine")
    fun clearQuarantine()

    @Query("DELETE FROM dismissals")
    fun clearDismissals()

    @Query("DELETE FROM ledger")
    fun clearLedger()

    @Query("DELETE FROM counters")
    fun clearCounters()

    @Query("DELETE FROM response_times")
    fun clearResponseTimes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putLedgerRow(row: LedgerRow)
}
