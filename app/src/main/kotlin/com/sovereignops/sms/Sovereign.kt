package com.sovereignops.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sovereignops.sms.core.rules.Alert
import com.sovereignops.sms.core.rules.Decision
import com.sovereignops.sms.core.rules.FlagSignal
import com.sovereignops.sms.core.guard.DraftGuard
import com.sovereignops.sms.core.guard.GuardContext
import com.sovereignops.sms.core.rules.IncomingMessage
import com.sovereignops.sms.core.rules.RuleEngine
import com.sovereignops.sms.core.rules.RuleId
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.markThreadMessagesRead
import com.sovereignops.sms.core.screening.Disposition
import com.sovereignops.sms.core.screening.Screening
import com.sovereignops.sms.core.screening.ScreeningInput
import com.sovereignops.sms.core.workspace.ChannelKind
import com.sovereignops.sms.core.workspace.Workspace
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import java.time.LocalDateTime

/**
 * The single seam between upstream receivers and Sovereign SMS policy. Receivers call
 * [screen] before storing and [announce] after storing; nothing else in upstream code
 * needs to know about rules.
 */
object Sovereign {

    fun store(context: Context) = SovereignStore(context)

    /** Definite "not in contacts" only; an undetermined lookup (e.g. permission denied) counts as known. */
    fun isUnknownSender(context: Context, address: String): Boolean = runCatching {
        val cursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        SimpleContactsHelper(context).existsSync(address, cursor) == ContactLookupResult.NotFound
    }.getOrDefault(false)

    fun screen(context: Context, address: String, body: String): Disposition {
        val input = ScreeningInput(
            numberBlocked = runCatching { context.isNumberBlocked(address) }.getOrDefault(false),
            keywordBlocked = isMessageFilteredOut(context, body),
            unknownSender = context.baseConfig.blockUnknownNumbers && isUnknownSender(context, address),
            blockUnknownSenders = context.baseConfig.blockUnknownNumbers,
        )
        return Screening.decide(input, store(context).preserveScreened)
    }

    /** After a screened message is stored: keep it out of the inbox without deleting it. */
    fun fileScreened(context: Context, threadId: Long) {
        if (threadId != 0L && context.config.isArchiveAvailable) {
            runCatching { context.updateConversationArchivedStatus(threadId, true) }
        }
    }

    /** Notification sender: the local alias when set and allowed, else upstream's name. */
    fun senderName(context: Context, threadId: Long, upstream: String): String = runCatching {
        if (!store(context).aliasInNotifications) return upstream
        com.sovereignops.sms.vault.VaultCache.cardFresh(context, threadId)?.alias ?: upstream
    }.getOrDefault(upstream)

    fun decide(context: Context, threadId: Long, address: String, body: String): Pair<Decision, ChannelKind> {
        val store = store(context)
        val card = com.sovereignops.sms.vault.VaultCache.cardFresh(context, threadId)
        val hasCard = card != null
        val known = hasCard || !isUnknownSender(context, address)
        // Incoming red flags. Receipt actions are opt-in per rule; none deletes, forwards or replies.
        val flagHits = store.flagRules.filter { it.enabled }.let { rules ->
            if (rules.isEmpty()) emptyList() else DraftGuard.check(body, rules, GuardContext(stage = card?.stage)).hits
        }
        if (store.category(threadId) == null) flagHits.firstNotNullOfOrNull { it.rule.category }?.let { store.setCategory(threadId, it) }
        val category = Workspace.resolve(store.category(threadId), known, hasCard)
        val message = IncomingMessage(
            address = address,
            isKnownContact = known,
            body = body,
            conversationPolicy = store.conversationPolicy(threadId),
            receivedAt = LocalDateTime.now(),
            lastAlertAt = store.lastAlertAt(threadId),
            category = category,
            workspaceMode = store.workspaceMode,
            flags = flagHits.map { FlagSignal(it.rule.name, it.rule.silent, it.rule.tag) },
            quarantined = com.sovereignops.sms.vault.VaultCache.isQuarantinedFresh(context, address),
            cover = store.coverActive,
        )
        val decision = RuleEngine.evaluate(store.ruleConfig, message)
        store.addThreadTags(threadId, decision.tags)
        trace(context, threadId, decision)
        return decision to Workspace.channel(decision, category, regular = card?.stage == com.sovereignops.sms.core.crm.Stage.REGULAR)
    }

    const val TRACE_KEEP = 200

    /** "Why did this happen?": the rule, outcome and explanation only; never the body or the address. */
    private fun trace(context: Context, threadId: Long, decision: Decision) = org.fossify.commons.helpers.ensureBackgroundThread {
        runCatching {
            val dao = com.sovereignops.sms.vault.Vault.dao(context)
            dao.addTrace(
                com.sovereignops.sms.vault.TraceRow(
                    at = System.currentTimeMillis(), threadId = threadId, rule = decision.decidedBy.name,
                    alert = decision.alert.name, explanation = decision.explanation.joinToString("\n"),
                ),
            )
            dao.trimTrace(TRACE_KEEP)
        }
    }

    /**
     * Apply the rules to a stored message. [post] shows the upstream per-message
     * notification: `quiet` is true for a silent post, `channel` is the Sovereign channel
     * to use (null = upstream's own channel).
     */
    fun announce(context: Context, threadId: Long, address: String, body: String, post: (quiet: Boolean, channel: String?) -> Unit) {
        val result = runCatching { decide(context, threadId, address, body) }.getOrNull()
        val channel = result?.let { runCatching { SovereignNotifications.channel(context, it.second) }.getOrNull() }
        when (result?.first?.alert ?: Alert.ALERT) { // any rules failure falls back to upstream behaviour
            Alert.ALERT -> {
                store(context).markAlerted(threadId, System.currentTimeMillis())
                post(false, channel)
            }
            Alert.SILENT -> if (result?.first?.decidedBy == RuleId.QUARANTINED) {
                // Quarantine: the message is already stored; mark it read and post nothing audible.
                runCatching {
                    context.markThreadMessagesRead(threadId)
                    context.conversationsDB.markRead(threadId)
                }
                post(true, channel)
            } else {
                post(true, null)
            }
            Alert.HELD -> SovereignNotifications.addHeld(context)
        }
    }
}

object SovereignNotifications {
    private const val QUIET_CHANNEL = "sovereign_quiet"
    private const val HELD_CHANNEL = "sovereign_held"
    private const val HELD_ID = 0x5050_0001
    private const val QUARANTINE_CHANNEL = "sovereign_quarantine"

    /** Per-category channels, so sound, vibration and lock-screen display are set per side in system settings. */
    fun channel(context: Context, kind: ChannelKind): String {
        if (kind == ChannelKind.QUIET) return quietChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (kind == ChannelKind.QUARANTINE) {
            nm.createNotificationChannel(
                NotificationChannel(QUARANTINE_CHANNEL, context.getString(R.string.sv_channel_quarantine), NotificationManager.IMPORTANCE_MIN).apply {
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
            return QUARANTINE_CHANNEL
        }
        val (id, name, importance) = when (kind) {
            ChannelKind.PERSONAL -> Triple("sovereign_personal", R.string.sv_channel_personal, NotificationManager.IMPORTANCE_HIGH)
            ChannelKind.PROFESSIONAL -> Triple("sovereign_professional", R.string.sv_channel_professional, NotificationManager.IMPORTANCE_HIGH)
            ChannelKind.UNKNOWN -> Triple("sovereign_unknown", R.string.sv_channel_unknown, NotificationManager.IMPORTANCE_DEFAULT)
            ChannelKind.PRIORITY -> Triple("sovereign_priority", R.string.sv_channel_priority, NotificationManager.IMPORTANCE_HIGH)
            ChannelKind.OTP -> Triple("sovereign_otp", R.string.sv_channel_otp, NotificationManager.IMPORTANCE_HIGH)
            ChannelKind.QUIET, ChannelKind.QUARANTINE -> error("handled above")
        }
        val ch = NotificationChannel(id, context.getString(name), importance).apply {
            // Professional and unknown default to hiding content on the lock screen; the user can change it.
            if (kind == ChannelKind.PROFESSIONAL || kind == ChannelKind.UNKNOWN) lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(ch)
        return id
    }

    /** Channels whose lock-screen form is a neutral "New message" with no sender or text. */
    private val neutral = setOf("sovereign_professional", "sovereign_unknown", QUARANTINE_CHANNEL)

    /**
     * Applied to every message notification: a neutral public version on professional,
     * unknown and quarantine channels, and a "Copy code" action when the text holds a
     * one-time code. Neither sends anything.
     */
    fun decorate(context: Context, builder: NotificationCompat.Builder, channelId: String, body: String, notificationId: Int) {
        if (channelId in neutral) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            builder.setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_messenger)
                    .setContentTitle(context.getString(R.string.new_message))
                    .build(),
            )
        }
        val code = if (Sovereign.store(context).copyCodeAction) com.sovereignops.sms.core.rules.OneTimeCodes.extract(body) else null
        if (code != null) {
            val copy = PendingIntent.getBroadcast(
                context, notificationId xor 0x0C0D,
                Intent(context, CopyCodeReceiver::class.java).putExtra(CopyCodeReceiver.EXTRA_CODE, code),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_messenger, context.getString(R.string.sv_copy_code), copy)
        }
    }

    /** Removes posted notifications that Quick Cover hides (professional, unknown, quarantine). */
    fun clearHidden(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching { nm.activeNotifications.filter { it.notification.channelId in neutral }.forEach { nm.cancel(it.tag, it.id) } }
    }

    fun quietChannel(context: Context): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL, context.getString(R.string.sovereign_channel_quiet), NotificationManager.IMPORTANCE_LOW),
        )
        return QUIET_CHANNEL
    }

    /** One silent summary for every message held by focus mode or quiet hours. */
    fun addHeld(context: Context) {
        val store = Sovereign.store(context)
        val count = store.heldCount + 1
        store.heldCount = count
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(HELD_CHANNEL, context.getString(R.string.sovereign_channel_held), NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            context, HELD_ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.resources.getQuantityString(R.plurals.sovereign_held_messages, count, count)
        val n = NotificationCompat.Builder(context, HELD_CHANNEL)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(context.getString(R.string.sovereign_held_title))
            .setContentText(text)
            .setNumber(count)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(HELD_ID, n)
    }

    /** Called when the inbox is shown: the held messages are now in front of the user. */
    fun clearHeld(context: Context) {
        val store = Sovereign.store(context)
        if (store.heldCount == 0) return
        store.heldCount = 0
        context.getSystemService(NotificationManager::class.java).cancel(HELD_ID)
    }
}
