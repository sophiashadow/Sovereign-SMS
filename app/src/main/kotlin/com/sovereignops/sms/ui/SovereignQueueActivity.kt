package com.sovereignops.sms.ui

import android.os.Bundle
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.core.crm.AvailabilityQueue
import com.sovereignops.sms.core.crm.QueueEntry
import com.sovereignops.sms.core.guard.DraftGuard
import com.sovereignops.sms.core.guard.GuardContext
import com.sovereignops.sms.core.templates.Placeholders
import com.sovereignops.sms.core.templates.Template
import com.sovereignops.sms.core.templates.TemplateCategory
import com.sovereignops.sms.vault.VaultCache
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.databinding.ActivitySovereignQueueBinding
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getAddresses
import org.fossify.messages.extensions.getThreadParticipants
import org.fossify.messages.messaging.sendMessageCompat
import java.text.DateFormat
import java.util.Date

/**
 * Sequential review of the opt-in availability list. Nothing is sent automatically:
 * each message is shown, can be edited, and goes out only when Send is tapped; a
 * flagged draft or a cooldown warning needs a second tap. Skip moves on without sending.
 */
class SovereignQueueActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySovereignQueueBinding::inflate)
    private var queue: List<QueueEntry> = emptyList()
    private var index = 0
    private var template: Template? = null
    private var armed: String? = null
    private var sent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.sqScroll))
        setupMaterialScrollListener(scrollingView = binding.sqScroll, topAppBar = binding.sqAppbar)
        binding.sqTemplate.setOnClickListener { pickTemplate() }
        binding.sqSkip.setOnClickListener { next() }
        binding.sqEdit.setOnClickListener { binding.sqMessage.isEnabled = true; binding.sqMessage.requestFocus() }
        binding.sqSend.setOnClickListener { send() }
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.sqAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.sqScroll)
    }

    private fun load() = VaultCache.load(this) {
        ensureBackgroundThread {
            val dates = runCatching { conversationsDB.getNonArchived().associate { it.threadId to it.date * 1000L } }.getOrDefault(emptyMap())
            val q = AvailabilityQueue.build(VaultCache.cards.values.toList(), System.currentTimeMillis()) { dates[it.threadId] }
            runOnUiThread {
                queue = q
                index = 0
                if (template == null) template = Sovereign.store(this).templates.firstOrNull { it.category == TemplateCategory.FOLLOW_UP }
                show()
            }
        }
    }

    private fun current() = queue.getOrNull(index)

    private fun show() = binding.run {
        armed = null
        sqGuard.beVisibleIf(false)
        sqTemplate.text = template?.let { getString(R.string.sv_queue_template, it.title) } ?: getString(R.string.sv_queue_pick_template)
        val e = current()
        listOf(sqSkip, sqEdit, sqSend).forEach { it.isEnabled = e != null }
        if (e == null) {
            sqProgress.text = ""
            sqName.text = getString(if (queue.isEmpty()) R.string.sv_queue_empty else R.string.sv_queue_done, sent)
            sqLast.text = ""
            sqWarning.beVisibleIf(false)
            sqMessage.setText("")
            return@run
        }
        sqProgress.text = getString(R.string.sv_queue_progress, index + 1, queue.size)
        sqName.text = e.card.alias ?: getString(R.string.sv_queue_no_alias)
        sqLast.text = e.lastContact?.let { getString(R.string.sv_queue_last, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
            ?: getString(R.string.sv_queue_never)
        sqWarning.text = e.warning.orEmpty()
        sqWarning.beVisibleIf(e.warning != null)
        val values = mapOf("alias" to e.card.alias, "time_window" to e.card.timeWindow, "date" to null)
        sqMessage.setText(template?.let { Placeholders.fill(it.body, values) }.orEmpty())
        sqMessage.isEnabled = template == null || Placeholders.missing(template!!.body, values).isNotEmpty()
    }

    private fun pickTemplate() {
        val all = Sovereign.store(this).templates.sortedByDescending { it.category == TemplateCategory.FOLLOW_UP }
        if (all.isEmpty()) {
            toast(R.string.sv_no_templates)
            return
        }
        val items = ArrayList(all.mapIndexed { i, t -> RadioItem(i, t.title, i) })
        RadioGroupDialog(this, items, -1, R.string.sv_queue_pick_template) { i ->
            template = all[i as Int]
            show()
        }
    }

    private fun next() {
        index++
        show()
    }

    private fun send() {
        val e = current() ?: return
        val text = binding.sqMessage.text.toString().trim()
        if (text.isEmpty() || Placeholders.names(text).isNotEmpty()) {
            toast(R.string.sv_queue_fill)
            binding.sqMessage.isEnabled = true
            return
        }
        // Second tap confirms a flagged draft or a cooldown warning; the text is never changed.
        if (armed != text) {
            val guard = DraftGuard.check(text, Sovereign.store(this).guardRules, GuardContext(stage = e.card.stage))
            val notes = listOfNotNull(e.warning, if (guard.warn) DraftGuard.summary(guard) else null)
            if (notes.isNotEmpty()) {
                armed = text
                binding.sqGuard.text = (notes + getString(R.string.sv_queue_confirm)).joinToString("\n")
                binding.sqGuard.beVisibleIf(true)
                return
            }
        }
        val threadId = e.card.threadId
        binding.sqSend.isEnabled = false
        ensureBackgroundThread {
            val addresses = runCatching { getThreadParticipants(threadId, null).getAddresses() }.getOrDefault(emptyList())
            if (addresses.isEmpty()) {
                runOnUiThread { toast(R.string.sv_queue_no_address); binding.sqSend.isEnabled = true }
                return@ensureBackgroundThread
            }
            val ok = runCatching { sendMessageCompat(text, addresses, null, emptyList()) }.isSuccess
            if (ok) VaultCache.update(this, threadId) { AvailabilityQueue.recordSend(it, System.currentTimeMillis()) }
            runOnUiThread {
                if (ok) {
                    sent++
                    next()
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    binding.sqSend.isEnabled = true
                }
            }
        }
    }
}
