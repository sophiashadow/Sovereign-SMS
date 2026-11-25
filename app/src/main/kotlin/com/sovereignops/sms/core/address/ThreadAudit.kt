package com.sovereignops.sms.core.address

import com.sovereignops.sms.core.rules.Addresses

data class AuditThread(val threadId: Long, val addresses: List<String>)

data class UnrecognizedThread(val threadId: Long, val participants: Int, val shapes: List<String>)

data class AuditReport(
    val threads: Int,
    val groups: Int,
    val unrecognized: List<UnrecognizedThread>,
    /** Shape of each unrecognized address, counted per occurrence. */
    val shapes: Map<String, Int>,
    /** Thread IDs whose participants are the same set once formats and the own number are ignored. */
    val possibleDuplicates: List<List<Long>>,
    val includesOwnNumber: List<Long>,
    val ownNumberKnown: Boolean,
)

/**
 * A read-only check of the threads in Android's message store. It reports shapes and
 * thread IDs only, never an address, a name or message text, and it never merges or
 * moves anything: duplicates are listed for the person to judge.
 */
object ThreadAudit {
    fun audit(threads: List<AuditThread>, ownNumbers: List<String>): AuditReport {
        val own = ownNumbers.filter(AddressClassifier::isPhoneLike)
        fun isOwn(a: String) = AddressClassifier.isPhoneLike(a) && own.any { Addresses.sameNumber(it, a) }

        val unrecognized = threads.mapNotNull { t ->
            val bad = t.addresses.filter(AddressClassifier::isUnrecognized)
            if (bad.isEmpty()) null else UnrecognizedThread(t.threadId, t.addresses.size, bad.map(AddressClassifier::shape))
        }
        val shapes = unrecognized.flatMap { it.shapes }.groupingBy { it }.eachCount()

        val duplicates = threads
            .mapNotNull { t ->
                val keys = t.addresses.filterNot(::isOwn).map(::key).toSortedSet()
                if (keys.isEmpty()) null else keys.joinToString("|") to t.threadId
            }
            .groupBy({ it.first }, { it.second })
            .values.filter { it.size > 1 }
            .map { it.sorted() }
            .sortedBy { it.first() }

        return AuditReport(
            threads = threads.size,
            groups = threads.count { it.addresses.size > 1 },
            unrecognized = unrecognized,
            shapes = shapes,
            possibleDuplicates = duplicates,
            includesOwnNumber = threads.filter { t -> t.addresses.any(::isOwn) }.map { it.threadId },
            ownNumberKnown = own.isNotEmpty(),
        )
    }

    /** Comparison key: format-insensitive for numbers, exact for anything unrecognized. */
    private fun key(a: String): String = when (AddressClassifier.classify(a)) {
        AddressKind.PHONE -> "n:" + Addresses.normalize(a).takeLast(10)
        AddressKind.SHORT_CODE -> "s:" + a.filter(Char::isDigit)
        AddressKind.EMAIL -> "e:" + a.trim().lowercase()
        else -> "?:" + a.trim()
    }

    fun render(r: AuditReport): String = buildString {
        appendLine("Conversation check. Shapes and thread numbers only: no phone numbers, names or message text.")
        appendLine()
        appendLine("Threads: ${r.threads} (groups: ${r.groups})")
        if (r.unrecognized.isEmpty()) {
            appendLine("Unrecognized addresses: none")
        } else {
            appendLine("Threads with an unrecognized address: ${r.unrecognized.size}")
            r.unrecognized.forEach { appendLine("  #${it.threadId}: ${it.participants} participant(s); ${it.shapes.joinToString("; ")}") }
            appendLine("Shapes: " + r.shapes.entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} ×${it.value}" })
            appendLine("Tip: if these are RCS chats from another app, turn off RCS chats there so new replies arrive as SMS/MMS.")
        }
        if (r.possibleDuplicates.isEmpty()) {
            appendLine("Possible duplicate threads: none")
        } else {
            appendLine("Possible duplicate threads (same participants; nothing was merged):")
            r.possibleDuplicates.forEach { ids -> appendLine("  " + ids.joinToString(" + ") { "#$it" }) }
        }
        if (r.ownNumberKnown) {
            appendLine(if (r.includesOwnNumber.isEmpty()) "Threads that include your own number: none"
            else "Threads that include your own number: " + r.includesOwnNumber.joinToString(", ") { "#$it" })
        } else {
            appendLine("Your own number: not known to Android on this phone, so incoming group MMS may list you as a participant and start a separate thread.")
        }
    }
}
