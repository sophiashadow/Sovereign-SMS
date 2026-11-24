package com.sovereignops.sms.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CardDoc(
    val threadId: Long,
    /** The conversation's numbers when backed up, so the card can find its thread on another phone. */
    val addresses: List<String> = emptyList(),
    val alias: String? = null,
    val stage: String,
    val checked: String = "",
    val tier: String? = null,
    val optIn: Boolean = false,
    val notes: String? = null,
    val timeWindow: String? = null,
    val pings: String = "",
    val lastContacted: Long? = null,
)

@Serializable
data class QuarantineDoc(val address: String, val addedAt: Long)

@Serializable
data class LedgerDoc(
    val date: String,
    val roomCostCents: Long,
    val booked: Int,
    val showed: Int,
    val grossCents: Long,
    val sessionHours: Double,
    val overheadHours: Double,
    val note: String? = null,
)

/** What a `.sovbak` holds: the settings pack and checklist, and the vault's cards, quarantine list and ledger. */
@Serializable
data class BackupContent(
    val format: String = FORMAT,
    val version: Int = 1,
    val pack: String? = null,
    val checklist: String? = null,
    val cards: List<CardDoc> = emptyList(),
    val quarantine: List<QuarantineDoc> = emptyList(),
    val ledger: List<LedgerDoc> = emptyList(),
) {
    companion object {
        const val FORMAT = "sovereign-sms/backup"
    }
}

enum class RestoreMode { MERGE, OVERWRITE }

data class VaultPart(val cards: List<CardDoc>, val quarantine: List<QuarantineDoc>, val ledger: List<LedgerDoc>)

object Backup {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(c: BackupContent): ByteArray = json.encodeToString(BackupContent.serializer(), c).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): BackupContent {
        val c = json.decodeFromString(BackupContent.serializer(), bytes.toString(Charsets.UTF_8))
        require(c.format == BackupContent.FORMAT && c.version == 1) { "Unsupported backup content" }
        return c
    }

    /**
     * Point backed-up cards at this phone's threads: [threadFor] finds a local thread for
     * a card's numbers (null = keep the backed-up id). Two cards landing on one thread keep
     * the first.
     */
    /**
     * Re-links backed-up cards to this phone's conversations by number. A card whose numbers match
     * no conversation here, or whose conversation already has a card, becomes *unlinked*: it gets
     * a negative id (thread ids are positive, so it can never attach to an unrelated conversation
     * that happens to reuse the old id) and keeps its numbers so a later restore can re-link it.
     * Nothing is dropped.
     */
    fun remap(cards: List<CardDoc>, threadFor: (List<String>) -> Long?): List<CardDoc> {
        val taken = HashSet<Long>()
        var orphan = 0L
        return cards.map { c ->
            val t = threadFor(c.addresses)
            if (t != null && t > 0 && taken.add(t)) c.copy(threadId = t) else c.copy(threadId = --orphan)
        }
    }

    /**
     * MERGE keeps everything on this phone: a card already on a thread wins, quarantine is the
     * union (earliest date), and ledger days are added unless an identical day is present.
     * OVERWRITE replaces the vault with the backup.
     */
    /** Incoming unlinked cards, minus ones identical to an unlinked card here, renumbered below this phone's. */
    private fun mergeOrphans(device: List<CardDoc>, incoming: List<CardDoc>): List<CardDoc> {
        val here = device.filter { it.threadId < 0 }.map { it.copy(threadId = 0) }.toSet()
        var next = minOf(0L, device.minOfOrNull { it.threadId } ?: 0L)
        return incoming.filter { it.copy(threadId = 0) !in here }.distinctBy { it.copy(threadId = 0) }.map { it.copy(threadId = --next) }
    }

    fun combine(device: VaultPart, incoming: VaultPart, mode: RestoreMode): VaultPart = when (mode) {
        RestoreMode.OVERWRITE -> incoming.copy(cards = incoming.cards.distinctBy { it.threadId })
        RestoreMode.MERGE -> VaultPart(
            cards = device.cards + mergeOrphans(device.cards, incoming.cards.filter { it.threadId < 0 }) +
                incoming.cards.filter { c -> c.threadId > 0 && device.cards.none { it.threadId == c.threadId } }.distinctBy { it.threadId },
            quarantine = (device.quarantine + incoming.quarantine).groupBy { it.address }.map { (_, v) -> v.minBy { it.addedAt } },
            ledger = device.ledger + incoming.ledger.filter { it !in device.ledger }.distinct(),
        )
    }
}
