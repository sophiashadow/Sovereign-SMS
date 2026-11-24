package com.sovereignops.sms.core.crm

/**
 * The opt-in availability list. It only orders cards for a person to review one at a
 * time; it never sends. Each send is a separate human tap in the review screen.
 */
data class QueueEntry(val card: Card, val lastContact: Long?, val warning: String?)

object AvailabilityQueue {
    /**
     * Cards with availability opt-in, longest-uncontacted first (never contacted first).
     * [lastActivity] gives the thread's latest message time; the latest of it, the card's
     * last contact and its last availability ping is used. Cooldown hits are kept, flagged
     * and sorted to the end, so the person can still choose to send.
     */
    fun build(cards: List<Card>, now: Long, cooldown: Cooldown = Cooldown(), lastActivity: (Card) -> Long? = { null }): List<QueueEntry> =
        cards.filter { it.optInAvailability && it.threadId > 0 } // unlinked (restored, no conversation) cards have no one to send to
            .map { c ->
                val last = listOfNotNull(c.lastContacted, c.lastAvailabilityPing, lastActivity(c)).maxOrNull()
                QueueEntry(c, last, cooldown.warning(c.availabilityPings, now))
            }
            .sortedWith(compareBy<QueueEntry> { it.warning != null }.thenBy { it.lastContact ?: Long.MIN_VALUE })

    /** The card after a send: ping recorded (pruned to 30 days) and last contact set. */
    fun recordSend(card: Card, now: Long, cooldown: Cooldown = Cooldown()): Card =
        card.copy(availabilityPings = cooldown.prune(card.availabilityPings + now, now), lastContacted = now)
}
