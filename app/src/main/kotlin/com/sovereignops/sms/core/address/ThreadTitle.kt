package com.sovereignops.sms.core.address

/**
 * Conversation titles that never show an unrecognized address verbatim. Upstream joins
 * each participant's contact name, or the raw address when there is no contact, so a
 * chat ID written into Android's message store became the title itself.
 *
 * English only, like the rest of Sovereign's strings.
 */
object ThreadTitle {
    const val UNRECOGNIZED = "Unrecognized address"
    const val UNSUPPORTED_ONE = "Unsupported conversation"

    /** [address] null means "no address known; trust the name" (e.g. a contact picked by name). */
    data class Entry(val address: String?, val name: String)

    fun participantName(address: String, name: String): String =
        if (AddressClassifier.isDisplayable(address)) name.ifBlank { address } else UNRECOGNIZED

    fun build(entries: List<Pair<String?, String>>): String = buildEntries(entries.map { Entry(it.first, it.second) })

    fun buildEntries(entries: List<Entry>): String {
        if (entries.isEmpty()) return ""
        val shown = entries.filter { it.address == null || AddressClassifier.isDisplayable(it.address) }
            .map { it.name.ifBlank { it.address.orEmpty() } }
        val unrecognized = entries.size - shown.size
        if (unrecognized == 0) return shown.joinToString(", ")
        val count = if (unrecognized == 1) "1 unrecognized address" else "$unrecognized unrecognized addresses"
        if (shown.isEmpty()) return if (unrecognized == 1) UNSUPPORTED_ONE else "Unsupported group ($count)"
        return (shown + count).joinToString(", ")
    }
}
