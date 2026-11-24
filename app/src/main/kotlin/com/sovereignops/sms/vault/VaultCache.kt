package com.sovereignops.sms.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.sovereignops.sms.core.crm.Card
import com.sovereignops.sms.core.rules.Addresses
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * In-memory copy of the small vault tables the UI reads while drawing lists (cards and
 * the quarantine list), so list rows never touch the database on the main thread.
 * All writes go to the vault first, then update this copy.
 */
object VaultCache {
    @Volatile var cards: Map<Long, Card> = emptyMap(); private set
    @Volatile var quarantine: Set<String> = emptySet(); private set
    @Volatile var loaded = false; private set
    @Volatile var version = 0; private set

    private val main = Handler(Looper.getMainLooper())

    /** Load (or reload) in the background; [then] runs on the main thread. */
    fun load(context: Context, then: (() -> Unit)? = null) = ensureBackgroundThread {
        runCatching {
            val dao = Vault.dao(context)
            cards = dao.cards().associate { it.threadId to it.toCard() }
            quarantine = dao.quarantine().map { it.address }.toSet()
            loaded = true
            version++
        }
        then?.let { main.post(it) }
    }

    fun ensure(context: Context, then: () -> Unit) = if (loaded) then() else load(context, then)

    fun card(threadId: Long): Card? = cards[threadId]

    fun isQuarantined(address: String): Boolean {
        val n = Addresses.normalize(address).ifEmpty { address.trim().lowercase() }
        return n in quarantine
    }

    // ---- writes (call off the main thread) ----

    fun saveCard(context: Context, card: Card) {
        val dao = Vault.dao(context)
        if (card.isBlank) dao.deleteCard(card.threadId) else dao.putCard(CardRow.from(card))
        cards = if (card.isBlank) cards - card.threadId else cards + (card.threadId to card)
        version++
    }

    fun update(context: Context, threadId: Long, change: (Card) -> Card) {
        val current = Vault.dao(context).card(threadId)?.toCard() ?: Card(threadId)
        saveCard(context, change(current))
    }

    fun setQuarantined(context: Context, address: String, on: Boolean) {
        val key = Addresses.normalize(address).ifEmpty { address.trim().lowercase() }
        if (key.isEmpty()) return
        val dao = Vault.dao(context)
        if (on) dao.putQuarantine(QuarantineRow(key, System.currentTimeMillis())) else dao.removeQuarantine(key)
        quarantine = if (on) quarantine + key else quarantine - key
        version++
    }

    /** Read on a background thread: the receiver path may run before the UI ever loaded the cache. */
    fun isQuarantinedFresh(context: Context, address: String): Boolean {
        if (!loaded) runCatching {
            quarantine = Vault.dao(context).quarantine().map { it.address }.toSet()
        }
        return isQuarantined(address)
    }

    fun cardFresh(context: Context, threadId: Long): Card? =
        runCatching { Vault.dao(context).card(threadId)?.toCard() }.getOrNull()
}
