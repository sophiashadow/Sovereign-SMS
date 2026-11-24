package com.sovereignops.sms.ui

import android.app.Activity
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.core.crm.Card
import com.sovereignops.sms.core.crm.Checklist
import com.sovereignops.sms.core.crm.Derived
import com.sovereignops.sms.core.crm.Stage
import com.sovereignops.sms.vault.Vault
import com.sovereignops.sms.vault.VaultCache
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.SovereignCardSheetBinding

/** Client card drawer. Everything here is local and encrypted; nothing touches Android Contacts. */
object SovereignCard {

    fun stageColor(stage: Stage): Int = when (stage) {
        Stage.NEW -> Color.parseColor("#9E9E9E")
        Stage.INFO_REQUESTED -> Color.parseColor("#42A5F5")
        Stage.VERIFICATION_PENDING -> Color.parseColor("#FFB300")
        Stage.VERIFIED -> Color.parseColor("#43A047")
        Stage.REGULAR -> Color.parseColor("#26A69A")
        Stage.DECLINED -> Color.parseColor("#E53935")
    }

    /** Inbox / header title: the alias if set, with a stage dot when a card exists. */
    fun decorate(threadId: Long, title: String, extra: CharSequence? = null): CharSequence {
        val card = VaultCache.card(threadId)
        if (card == null && extra == null) return title
        val out = SpannableStringBuilder()
        if (card != null) {
            out.append("● ")
            out.setSpan(ForegroundColorSpan(stageColor(card.stage)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        out.append(card?.alias ?: title)
        if (extra != null) out.append(" ").append(extra)
        return out
    }

    fun alias(threadId: Long): String? = VaultCache.card(threadId)?.alias

    /**
     * Open the card for [threadId]. [messageTimes] are the thread's message dates (millis)
     * for the derived facts; [address] is the sender for quarantine.
     */
    fun open(
        activity: Activity,
        threadId: Long,
        address: String?,
        messageTimes: List<Long>,
        onLogSession: (() -> Unit)? = null,
        onPreserve: (() -> Unit)? = null,
        onSaved: () -> Unit = {},
    ) {
        if (threadId == 0L) return
        ensureBackgroundThread {
            val card = runCatching { VaultCache.cardFresh(activity, threadId) ?: Card(threadId) }.getOrNull()
            val quarantined = address?.let { VaultCache.isQuarantinedFresh(activity, it) } ?: false
            val recovered = runCatching { Vault.recoveredAt(activity) }.getOrDefault(0L)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (card == null) {
                    activity.toast(R.string.sv_vault_recovered)
                    return@runOnUiThread
                }
                if (recovered > 0) {
                    activity.toast(R.string.sv_vault_recovered)
                    Vault.clearRecoveredNotice(activity)
                }
                show(activity, card, address, quarantined, messageTimes, onLogSession, onPreserve, onSaved)
            }
        }
    }

    private fun show(
        activity: Activity,
        card: Card,
        address: String?,
        quarantined: Boolean,
        messageTimes: List<Long>,
        onLogSession: (() -> Unit)?,
        onPreserve: (() -> Unit)?,
        onSaved: () -> Unit,
    ) {
        val b = SovereignCardSheetBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(b.root)

        val d = Derived.from(messageTimes)
        b.svCardDerived.text = if (d.messageCount == 0) "" else activity.getString(
            R.string.sv_card_derived,
            d.firstContact!!.formatDateOrTime(activity, hideTimeOnOtherDays = true, showCurrentYear = true),
            d.lastInteraction!!.formatDateOrTime(activity, hideTimeOnOtherDays = true, showCurrentYear = true),
            d.messageCount,
        )
        b.svCardAlias.setText(card.alias.orEmpty())
        Stage.entries.forEach { st ->
            val chip = Chip(activity).apply {
                id = 0x5300 + st.ordinal
                text = st.label
                isCheckable = true
                isChecked = st == card.stage
            }
            b.svCardStage.addView(chip)
        }
        val items = Checklist.shown(Sovereign.store(activity).checklistItems)
        items.forEachIndexed { i, label ->
            b.svCardChecklist.addView(Chip(activity).apply {
                id = 0x5400 + i
                text = label
                isCheckable = true
                isChecked = label in card.checked
            })
        }
        b.svCardTier.setText(card.tier.orEmpty())
        b.svCardWindow.setText(card.timeWindow.orEmpty())
        b.svCardOptin.isChecked = card.optInAvailability
        b.svCardQuarantine.isChecked = quarantined
        b.svCardQuarantine.beVisibleIf(address != null)
        b.svCardNotes.setText(card.notes.orEmpty())
        b.svCardLogSession.beVisibleIf(onLogSession != null)
        b.svCardLogSession.setOnClickListener { dialog.dismiss(); onLogSession?.invoke() }
        b.svCardPreserve.beVisibleIf(onPreserve != null)
        b.svCardPreserve.setOnClickListener { dialog.dismiss(); onPreserve?.invoke() }

        b.svCardSave.setOnClickListener {
            val stage = Stage.entries.firstOrNull { b.svCardStage.checkedChipId == 0x5300 + it.ordinal } ?: card.stage
            val checked = items.filterIndexed { i, _ -> b.svCardChecklist.findViewById<Chip>(0x5400 + i)?.isChecked == true }.toSet() +
                card.checked.filter { it !in items } // keep ticks for items removed from the list
            val updated = card.copy(
                alias = b.svCardAlias.text.toString().trim().ifEmpty { null },
                stage = stage,
                checked = checked,
                tier = b.svCardTier.text.toString().trim().ifEmpty { null },
                timeWindow = b.svCardWindow.text.toString().trim().ifEmpty { null },
                optInAvailability = b.svCardOptin.isChecked,
                notes = b.svCardNotes.text.toString().ifBlank { null },
            )
            val q = b.svCardQuarantine.isChecked
            ensureBackgroundThread {
                val ok = runCatching {
                    VaultCache.saveCard(activity, updated)
                    if (address != null && q != quarantined) VaultCache.setQuarantined(activity, address, q)
                }.isSuccess
                activity.runOnUiThread {
                    activity.toast(if (ok) R.string.sv_card_saved else org.fossify.commons.R.string.unknown_error_occurred)
                    if (ok) {
                        dialog.dismiss()
                        onSaved()
                    }
                }
            }
        }
        dialog.show()
    }

    /** Compact "3/5" checklist cue for previews. */
    fun progress(activity: Activity, card: Card): String {
        val (done, total) = Checklist.progress(Sovereign.store(activity).checklistItems, card.checked)
        return "$done/$total"
    }

    /** One-time notice after the vault was set aside and recreated. */
    fun recoveredNotice(activity: android.app.Activity) {
        if (com.sovereignops.sms.vault.Vault.recoveredAt(activity) == 0L) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.sv_vault_recovered)
            .setPositiveButton(android.R.string.ok) { _, _ -> com.sovereignops.sms.vault.Vault.clearRecoveredNotice(activity) }
            .setCancelable(false)
            .show()
    }
}
