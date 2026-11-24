package com.sovereignops.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sovereignops.sms.core.rules.Alert
import com.sovereignops.sms.core.rules.Decision
import com.sovereignops.sms.core.rules.IncomingMessage
import com.sovereignops.sms.core.rules.RuleEngine
import com.sovereignops.sms.core.screening.Disposition
import com.sovereignops.sms.core.screening.Screening
import com.sovereignops.sms.core.screening.ScreeningInput
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

    fun decide(context: Context, threadId: Long, address: String, body: String): Decision {
        val store = store(context)
        val message = IncomingMessage(
            address = address,
            isKnownContact = !isUnknownSender(context, address),
            body = body,
            conversationPolicy = store.conversationPolicy(threadId),
            receivedAt = LocalDateTime.now(),
            lastAlertAt = store.lastAlertAt(threadId),
        )
        return RuleEngine.evaluate(store.ruleConfig, message)
    }

    /**
     * Apply the rules to a stored message. [post] shows the upstream per-message
     * notification; its argument is true for a quiet (no sound/vibration) post.
     */
    fun announce(context: Context, threadId: Long, address: String, body: String, post: (quiet: Boolean) -> Unit) {
        val decision = runCatching { decide(context, threadId, address, body) }.getOrNull()
        when (decision?.alert ?: Alert.ALERT) { // any rules failure falls back to upstream behaviour
            Alert.ALERT -> {
                store(context).markAlerted(threadId, System.currentTimeMillis())
                post(false)
            }
            Alert.SILENT -> post(true)
            Alert.HELD -> SovereignNotifications.addHeld(context)
        }
    }
}

object SovereignNotifications {
    private const val QUIET_CHANNEL = "sovereign_quiet"
    private const val HELD_CHANNEL = "sovereign_held"
    private const val HELD_ID = 0x5050_0001

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
