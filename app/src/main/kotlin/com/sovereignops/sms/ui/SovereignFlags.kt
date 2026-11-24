package com.sovereignops.sms.ui

import android.app.Activity
import android.graphics.Color
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.core.guard.DraftGuard
import com.sovereignops.sms.core.guard.GuardContext
import com.sovereignops.sms.core.guard.GuardHit
import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.guard.Severity
import com.sovereignops.sms.core.rules.ConversationPolicy
import com.sovereignops.sms.vault.VaultCache
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.models.Message
import java.lang.ref.WeakReference

/**
 * Incoming red-flag badges on received messages, and the three triage actions:
 * Quarantine & Mute (reversible), Insert a deflection template (never sends) and
 * Dismiss this match. Nothing here deletes, forwards or replies.
 */
object SovereignFlags {
    const val HISTORY = 10

    @Volatile private var cached: List<GuardRule>? = null

    /** The open conversation's compose box, for "Insert deflection template". */
    var composer: WeakReference<EditText>? = null

    fun invalidate() {
        cached = null
    }

    fun rules(activity: Activity): List<GuardRule> =
        cached ?: Sovereign.store(activity).flagRules.filter { it.enabled }.also { cached = it }

    fun key(m: Message) = (if (m.isMMS) "mms:" else "sms:") + m.id

    fun needsHistory(rules: List<GuardRule>) = rules.any { it.pair != null && it.window > 0 }

    /** Bodies of up to [HISTORY] messages before [message] in [items], oldest first. */
    fun historyBefore(items: List<Any>, message: Message): List<String> {
        val i = items.indexOf(message)
        if (i <= 0) return emptyList()
        return items.subList(maxOf(0, i - HISTORY * 2), i).filterIsInstance<Message>().takeLast(HISTORY).map { it.body }
    }

    fun hits(activity: Activity, message: Message, history: () -> List<String>): List<GuardHit> {
        val rules = rules(activity)
        if (rules.isEmpty() || message.body.isBlank()) return emptyList()
        val ctx = GuardContext(if (needsHistory(rules)) history() else emptyList(), VaultCache.card(message.threadId)?.stage)
        val k = key(message)
        return DraftGuard.check(message.body, rules, ctx).hits.filterNot { VaultCache.isDismissed(k, it.rule.name) }
    }

    fun color(s: Severity) = when (s) {
        Severity.INFO -> Color.rgb(0x60, 0x7D, 0x8B)
        Severity.CAUTION -> Color.rgb(0xB2, 0x6A, 0x00)
        Severity.HIGH_RISK -> Color.rgb(0xC6, 0x28, 0x28)
    }

    fun bind(activity: SimpleActivity, badge: TextView, message: Message, history: () -> List<String>, refresh: () -> Unit) {
        val hits = runCatching { hits(activity, message, history) }.getOrDefault(emptyList())
        if (hits.isEmpty()) {
            badge.beGone()
            badge.setOnClickListener(null)
            return
        }
        val top = hits.maxOf { it.rule.severity }
        badge.text = DraftGuard.summary(com.sovereignops.sms.core.guard.GuardResult(hits, emptyList()))
        badge.setTextColor(color(top))
        badge.beVisible()
        badge.setOnClickListener { triage(activity, message, hits, refresh) }
    }

    private fun triage(activity: SimpleActivity, message: Message, hits: List<GuardHit>, refresh: () -> Unit) {
        val labels = arrayOf(
            activity.getString(R.string.sv_flag_quarantine),
            activity.getString(R.string.sv_flag_deflect),
            activity.getString(R.string.sv_flag_dismiss),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(DraftGuard.summary(com.sovereignops.sms.core.guard.GuardResult(hits, emptyList())))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> quarantine(activity, message, refresh)
                    1 -> deflect(activity)
                    2 -> ensureBackgroundThread {
                        val k = key(message)
                        hits.forEach { VaultCache.dismiss(activity, k, it.rule.name) }
                        activity.runOnUiThread(refresh)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun quarantine(activity: SimpleActivity, message: Message, refresh: () -> Unit) {
        val address = message.senderPhoneNumber.ifBlank { message.participants.firstOrNull()?.phoneNumbers?.firstOrNull()?.normalizedNumber.orEmpty() }
        if (address.isBlank()) return
        ensureBackgroundThread {
            VaultCache.setQuarantined(activity, address, true)
            Sovereign.store(activity).setConversationPolicy(message.threadId, ConversationPolicy.MUTED)
            activity.runOnUiThread {
                activity.toast(R.string.sv_quarantined)
                refresh()
            }
        }
    }

    private fun deflect(activity: SimpleActivity) {
        val input = composer?.get() ?: return
        SovereignComposer.pickTemplate(activity, input, preferDeflection = true)
    }
}
