package com.sovereignops.sms.ui

import com.sovereignops.sms.vault.VaultCache
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.extensions.getAddresses
import org.fossify.messages.models.Message

/** The seam between ThreadActivity and Sovereign features, so upstream edits stay one-liners. */
object SovereignThread {

    /** The single other party of a one-to-one thread, or null for groups. */
    fun address(participants: ArrayList<SimpleContact>): String? =
        participants.getAddresses().distinct().singleOrNull()

    fun openCard(activity: SimpleActivity, threadId: Long, participants: ArrayList<SimpleContact>, messages: List<Message>, refreshTitle: () -> Unit) {
        SovereignCard.open(
            activity = activity,
            threadId = threadId,
            address = address(participants),
            messageTimes = messages.map { it.date * 1000L },
            onLogSession = SovereignHooks.logSession?.let { f -> { f(activity, threadId) } },
            onPreserve = SovereignHooks.preserve?.let { f -> { f(activity, threadId) } },
            onSaved = refreshTitle,
        )
    }

    /** What the draft guard knows: recent bodies (both directions) and the card stage. */
    fun guardContext(threadId: Long, messages: List<Message>) = com.sovereignops.sms.core.guard.GuardContext(
        history = messages.takeLast(SovereignFlags.HISTORY).map { it.body },
        stage = VaultCache.card(threadId)?.stage,
    )

    /** Alias for the header when set; null keeps upstream's title. */
    fun title(threadId: Long): String? = VaultCache.card(threadId)?.alias
}

/** Late-bound hooks for features from later slices (ledger, preservation). */
object SovereignHooks {
    var logSession: ((SimpleActivity, Long) -> Unit)? = null
    var preserve: ((SimpleActivity, Long) -> Unit)? = null
}
