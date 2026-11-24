package com.sovereignops.sms.core.screening

/*
 * What happens to an inbound message that upstream's filters would reject.
 *
 * Upstream Fossify Messages returns early for blocked numbers, blocked keywords and
 * (optionally) unknown senders, so the message is never written to the Telephony
 * provider: as the default SMS app, that silently destroys it. Sovereign SMS keeps
 * it instead (stored as read, conversation archived, no notification), because a
 * screened message can be evidence, a misfire, or something the user needs later.
 * The upstream behaviour stays available only as an explicit, off-by-default option.
 */

enum class ScreenReason { BLOCKED_NUMBER, BLOCKED_KEYWORD, UNKNOWN_SENDER }

sealed interface Disposition {
    /** Normal path: store, then apply notification rules. */
    data object Deliver : Disposition

    /** Store as read, archive the conversation, post no notification. */
    data class Screen(val reasons: List<ScreenReason>) : Disposition

    /** Upstream behaviour: do not store. Only reachable when the user turned preservation off. */
    data class Discard(val reasons: List<ScreenReason>) : Disposition
}

data class ScreeningInput(
    val numberBlocked: Boolean,
    val keywordBlocked: Boolean,
    val unknownSender: Boolean,
    val blockUnknownSenders: Boolean,
)

object Screening {
    fun decide(input: ScreeningInput, preserveScreened: Boolean): Disposition {
        val reasons = buildList {
            if (input.numberBlocked) add(ScreenReason.BLOCKED_NUMBER)
            if (input.keywordBlocked) add(ScreenReason.BLOCKED_KEYWORD)
            if (input.unknownSender && input.blockUnknownSenders) add(ScreenReason.UNKNOWN_SENDER)
        }
        return when {
            reasons.isEmpty() -> Disposition.Deliver
            preserveScreened -> Disposition.Screen(reasons)
            else -> Disposition.Discard(reasons)
        }
    }
}
