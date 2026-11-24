package com.sovereignops.sms.core.crm

import com.sovereignops.sms.core.rules.Addresses

/** A conversation reduced to its id and the numbers of its participants. */
data class ConversationNumbers(val threadId: Long, val numbers: List<String>)

/**
 * Matching a card's backed-up numbers to this phone's conversations. Shared by restore (which
 * links on the spot) and the unlinked-cards screen (which retries later and lets the owner link
 * by hand). Never guesses: the set of numbers must match exactly (same size, every number found).
 */
object Relink {
    data class Move(val from: Long, val to: Long)

    fun find(numbers: List<String>, conversations: List<ConversationNumbers>): Long? {
        if (numbers.isEmpty()) return null
        return conversations.firstOrNull { c ->
            c.numbers.size == numbers.size && numbers.all { n -> c.numbers.any { Addresses.sameNumber(it, n) } }
        }?.threadId
    }

    /**
     * Automatic links for unlinked cards (negative ids). A conversation that already has a card
     * ([taken]) or was claimed earlier in the list is never used, so nothing is overwritten.
     */
    fun plan(unlinked: List<Pair<Long, List<String>>>, taken: Set<Long>, conversations: List<ConversationNumbers>): List<Move> {
        val used = taken.toMutableSet()
        val moves = ArrayList<Move>()
        for ((id, numbers) in unlinked) {
            val t = find(numbers, conversations.filter { it.threadId !in used }) ?: continue
            used += t
            moves += Move(id, t)
        }
        return moves
    }
}
