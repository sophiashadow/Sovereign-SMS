package com.sovereignops.sms.core.crm

/*
 * Local client card: app-owned metadata about a conversation. It lives only in the
 * encrypted vault, never in Android Contacts or the Telephony provider, so apps that
 * sync device contacts cannot see it.
 */

/** Screening pipeline, in the order a new inquiry normally moves through it. */
enum class Stage(val label: String) {
    NEW("New / Unscreened"),
    INFO_REQUESTED("Info requested"),
    VERIFICATION_PENDING("Verification pending"),
    VERIFIED("Verified / Vetted"),
    REGULAR("Regular"),
    DECLINED("Declined / Do not engage"),
    ;

    /** Stages where sensitive details (e.g. a location template) should not be sent yet. */
    val unscreened get() = this == NEW || this == VERIFICATION_PENDING || this == INFO_REQUESTED

    companion object {
        fun parse(name: String?): Stage = entries.firstOrNull { it.name == name } ?: NEW
    }
}

data class Card(
    val threadId: Long,
    val alias: String? = null,
    val stage: Stage = Stage.NEW,
    /** Checklist items the user has ticked, by label. */
    val checked: Set<String> = emptySet(),
    val tier: String? = null,
    val optInAvailability: Boolean = false,
    val notes: String? = null,
    /** Free text used by the {time_window} template placeholder. */
    val timeWindow: String? = null,
    /** Epoch millis of availability messages sent from the queue, newest last. */
    val availabilityPings: List<Long> = emptyList(),
    /** Epoch millis of the last message the user sent in this thread. */
    val lastContacted: Long? = null,
) {
    val lastAvailabilityPing get() = availabilityPings.maxOrNull()

    /** True when nothing has been filled in, so the card need not be stored. */
    val isBlank
        get() = alias.isNullOrBlank() && stage == Stage.NEW && checked.isEmpty() && tier.isNullOrBlank() &&
            !optInAvailability && notes.isNullOrBlank() && timeWindow.isNullOrBlank() && availabilityPings.isEmpty() && lastContacted == null
}

/**
 * Checklist items shown on every card, edited as text with the same `#off ` convention as
 * the rule lists: a `#off ` line is kept but not shown. Defaults are neutral; the terms
 * item starts switched off so the app implies no policy until the user turns it on.
 */
object Checklist {
    private const val OFF = "#off "

    data class Item(val label: String, val enabled: Boolean = true)

    val DEFAULT = listOf(
        Item("Name / handle"),
        Item("Time & duration"),
        Item("Location verified"),
        Item("Live voice / photo check"),
        Item("Deposit / Terms confirmed", enabled = false),
    )

    fun parse(text: String): List<Item> = text.lines().map(String::trim)
        .filter { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith(OFF)) }
        .map { if (it.startsWith(OFF)) Item(it.removePrefix(OFF).trim(), false) else Item(it) }
        .filter { it.label.isNotEmpty() }
        .distinctBy { it.label }

    fun format(items: List<Item>): String = items.joinToString("\n") { (if (it.enabled) "" else OFF) + it.label }

    fun shown(items: List<Item>): List<String> = items.filter { it.enabled }.map { it.label }

    /** Ticks on shown items only, plus progress for a compact "3/4" cue. */
    fun progress(items: List<Item>, checked: Set<String>): Pair<Int, Int> {
        val labels = shown(items)
        return labels.count { it in checked } to labels.size
    }
}

/** Facts derived from the thread itself, computed on the device, never stored as message content. */
data class Derived(val firstContact: Long?, val lastInteraction: Long?, val messageCount: Int) {
    companion object {
        fun from(messageTimes: List<Long>): Derived =
            Derived(messageTimes.minOrNull(), messageTimes.maxOrNull(), messageTimes.size)
    }
}

/** Anti-spam indicator for the availability queue. Advisory: it never blocks a human send. */
data class Cooldown(val minDays: Int = 7, val maxPer30Days: Int = 4) {
    private val day = 24L * 60 * 60 * 1000

    fun warning(pings: List<Long>, now: Long): String? {
        val last = pings.maxOrNull()
        val recent = pings.count { it > now - 30 * day && it <= now }
        return when {
            recent >= maxPer30Days -> "Contacted $recent times in the last 30 days"
            last != null && now - last < minDays * day -> "Contacted ${((now - last) / day)} day(s) ago"
            else -> null
        }
    }

    /** Keep only what the indicator needs (last 30 days), so the list cannot grow without bound. */
    fun prune(pings: List<Long>, now: Long): List<Long> = pings.filter { it > now - 30 * day }.sorted()
}
