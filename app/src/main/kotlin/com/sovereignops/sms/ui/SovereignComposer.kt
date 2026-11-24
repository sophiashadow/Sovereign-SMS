package com.sovereignops.sms.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.view.isNotEmpty
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sovereignops.sms.core.templates.Placeholders
import com.sovereignops.sms.core.templates.Template
import com.sovereignops.sms.core.templates.TemplateCategory
import com.sovereignops.sms.vault.VaultCache
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import android.widget.TextView
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.core.guard.DraftGuard
import com.sovereignops.sms.core.guard.GuardContext
import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.templates.Templates
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity

/** Composer hooks: draft guard banner, template insertion, conversation category. Nothing here sends. */
object SovereignComposer {
    private const val DEBOUNCE_MS = 300L
    private val DEFLECT = Regex("deflect|decline|not available", RegexOption.IGNORE_CASE)

    /** Rules are read once per screen; settings changes apply when the conversation reopens. */
    private var rules: List<GuardRule> = emptyList()
    private var context: () -> GuardContext = { GuardContext() }
    private var armedText: String? = null
    private var armedAt = 0L
    private const val ARM_MS = 8000L

    fun attachGuard(activity: SimpleActivity, input: EditText, banner: TextView, guardContext: () -> GuardContext = { GuardContext() }) {
        rules = Sovereign.store(activity).guardRules
        context = guardContext
        armedText = null
        val check = Runnable {
            val result = if (rules.isEmpty()) null else DraftGuard.check(input.text.toString(), rules, context())
            banner.text = result?.let(DraftGuard::summary).orEmpty()
            banner.beVisibleIf(result?.warn == true)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                input.removeCallbacks(check)
                input.postDelayed(check, DEBOUNCE_MS)
            }
        })
        input.post(check)
    }

    fun isArmed(text: String) = armedText == text && System.currentTimeMillis() - armedAt < ARM_MS

    /**
     * Two-tap confirm for a flagged draft: the first tap only explains; a second tap (or a
     * long-press) within a few seconds sends. Returns true when sending may go ahead. It
     * never edits or blocks the text, and it is off when the user turns it off.
     */
    fun confirmSend(activity: SimpleActivity, text: String, banner: TextView): Boolean {
        if (rules.isEmpty() || text.isBlank() || !Sovereign.store(activity).confirmFlaggedSend) return true
        if (isArmed(text)) {
            armedText = null
            return true
        }
        val result = DraftGuard.check(text, rules, context())
        if (!result.warn) return true
        armedText = text
        armedAt = System.currentTimeMillis()
        banner.text = DraftGuard.summary(result) + "\n" + activity.getString(R.string.sv_confirm_send)
        banner.beVisibleIf(true)
        return false
    }

    fun pickTemplate(activity: SimpleActivity, input: EditText, preferDeflection: Boolean = false, threadId: Long? = null) {
        val all = Sovereign.store(activity).templates
        val templates = if (!preferDeflection) all else all.sortedByDescending { t -> t.category == TemplateCategory.DEFLECTION || DEFLECT.containsMatchIn(t.title) }
        choose(activity, input, templates, R.string.sv_insert_template, threadId)
    }

    private fun choose(activity: SimpleActivity, input: EditText, templates: List<Template>, title: Int, threadId: Long?) {
        if (templates.isEmpty()) {
            activity.toast(R.string.sv_no_templates)
            return
        }
        val items = ArrayList(templates.mapIndexed { i, t -> RadioItem(i, t.title, i) })
        RadioGroupDialog(activity, items, -1, title) { i -> insertTemplate(activity, input, templates[i as Int], threadId) }
    }

    /** Values the client card can supply for placeholders. */
    fun cardValues(threadId: Long?): Map<String, String?> {
        val card = threadId?.let(VaultCache::card)
        return mapOf("alias" to card?.alias, "time_window" to card?.timeWindow, "date" to null)
    }

    /**
     * Fill placeholders from the card, ask for the rest, then insert at the cursor.
     * Never sends; the draft guard checks the result like typed text.
     */
    fun insertTemplate(activity: SimpleActivity, input: EditText, template: Template, threadId: Long?) {
        val values = cardValues(threadId)
        val missing = Placeholders.missing(template.body, values)
        if (missing.isEmpty()) {
            put(input, Placeholders.fill(template.body, values))
            return
        }
        val fields = missing.associateWith { name ->
            EditText(activity).apply {
                hint = activity.getString(placeholderLabel(name))
                if (name == "date") setText(LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            }
        }
        val pad = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            fields.values.forEach(::addView)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(template.title)
            .setView(form)
            .setPositiveButton(R.string.sv_insert) { _, _ ->
                val filled = values + fields.mapValues { it.value.text.toString().trim() }
                put(input, Placeholders.fill(template.body, filled))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun placeholderLabel(name: String) = when (name) {
        "alias" -> R.string.sv_ph_alias
        "time_window" -> R.string.sv_ph_time_window
        else -> R.string.sv_ph_date
    }

    private fun put(input: EditText, body: String) {
        val (text, cursor) = Templates.insert(input.text.toString(), input.selectionStart, input.selectionEnd, body)
        input.setText(text)
        input.setSelection(cursor.coerceAtMost(text.length))
        input.requestFocus()
    }

    /** Favorite chips insert directly; category chips open that category's list. Hidden with no templates. */
    fun attachQuickBar(activity: SimpleActivity, scroll: View, bar: ChipGroup, input: EditText, threadId: Long) {
        bar.removeAllViews()
        val templates = Sovereign.store(activity).templates
        val (favorites, categories) = Templates.bar(templates)
        favorites.forEach { t ->
            bar.addView(Chip(activity).apply {
                text = activity.getString(R.string.sv_favorite_chip, t.title)
                setOnClickListener { insertTemplate(activity, input, t, threadId) }
            })
        }
        categories.forEach { c ->
            bar.addView(Chip(activity).apply {
                text = c.label
                setOnClickListener { choose(activity, input, templates.filter { it.category == c }, R.string.sv_insert_template, threadId) }
            })
        }
        scroll.beVisibleIf(bar.isNotEmpty())
    }

    fun pickCategory(activity: SimpleActivity, threadId: Long) {
        val store = Sovereign.store(activity)
        val options = listOf<Category?>(null, Category.PERSONAL, Category.PROFESSIONAL, Category.UNKNOWN)
        val labels = listOf(R.string.sv_category_auto, R.string.sv_mode_personal, R.string.sv_mode_professional, R.string.sv_category_unknown)
        val items = ArrayList(labels.mapIndexed { i, l -> RadioItem(i, activity.getString(l), i) })
        RadioGroupDialog(activity, items, options.indexOf(store.category(threadId)), R.string.sv_category) { i ->
            store.setCategory(threadId, options[i as Int])
        }
    }
}
