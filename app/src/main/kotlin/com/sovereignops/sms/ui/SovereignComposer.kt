package com.sovereignops.sms.ui

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
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

    fun pickTemplate(activity: SimpleActivity, input: EditText, preferDeflection: Boolean = false) {
        val all = Sovereign.store(activity).templates
        val templates = if (!preferDeflection) all else all.sortedByDescending { t -> DEFLECT.containsMatchIn(t.title) }
        if (templates.isEmpty()) {
            activity.toast(R.string.sv_no_templates)
            return
        }
        val items = ArrayList(templates.mapIndexed { i, t -> RadioItem(i, t.title, i) })
        RadioGroupDialog(activity, items, -1, R.string.sv_insert_template) { i ->
            val (text, cursor) = Templates.insert(input.text.toString(), input.selectionStart, input.selectionEnd, templates[i as Int].body)
            input.setText(text)
            input.setSelection(cursor.coerceAtMost(text.length))
            input.requestFocus()
        }
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
