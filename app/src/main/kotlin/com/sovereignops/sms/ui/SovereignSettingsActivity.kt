package com.sovereignops.sms.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.SovereignStore
import com.sovereignops.sms.core.rules.Alert
import com.sovereignops.sms.core.rules.ConversationPolicy
import com.sovereignops.sms.core.rules.IncomingMessage
import com.sovereignops.sms.core.rules.RuleConfig
import com.sovereignops.sms.core.rules.RuleEngine
import com.sovereignops.sms.core.rules.RuleText
import com.sovereignops.sms.core.rules.TimeWindow
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getTimePickerDialogTheme
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.databinding.ActivitySovereignSettingsBinding
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Sovereign SMS rules and screening settings. Every change is saved immediately to
 * [SovereignStore]; nothing here sends a message or touches message content.
 */
class SovereignSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySovereignSettingsBinding::inflate)
    private val store by lazy { SovereignStore(this) }
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    /** The window this screen edits; any further windows from an imported file are kept as-is. */
    private var window = TimeWindow(DayOfWeek.entries.toSet(), LocalTime.of(22, 0), LocalTime.of(7, 0))
    private var quietOn = false
    private var extraWindows: List<TimeWindow> = emptyList()
    private var loading = false

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        saveText()
        runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(store.exportRules().toByteArray()) } }
            .onSuccess { toast(R.string.sv_exported) }
            .onFailure { toast(getString(R.string.sv_import_failed, it.message ?: "")) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val error = if (text == null) "unreadable file" else store.importRules(text)
        if (error == null) {
            toast(R.string.sv_imported)
            load()
        } else {
            toast(getString(R.string.sv_import_failed, error))
        }
    }

    private val dayBoxes: List<Pair<DayOfWeek, CheckBox>> by lazy {
        binding.run {
            listOf(svDay1, svDay2, svDay3, svDay4, svDay5, svDay6, svDay7).mapIndexed { i, box -> DayOfWeek.of(i + 1) to box }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.svScrollview))
        setupMaterialScrollListener(scrollingView = binding.svScrollview, topAppBar = binding.svAppbar)
        wire()
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.svAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.svScrollview)
    }

    override fun onPause() {
        saveText()
        super.onPause()
    }

    private fun edit(change: (RuleConfig) -> RuleConfig) {
        if (loading) return
        store.ruleConfig = change(store.ruleConfig)
        render()
    }

    private fun load() {
        val c = store.ruleConfig
        c.quietHours.firstOrNull()?.let { window = it }
        quietOn = c.quietHours.isNotEmpty()
        extraWindows = c.quietHours.drop(1)
        loading = true
        binding.svPriority.setText(RuleText.formatAddresses(c.priorityAddresses))
        binding.svKeywords.setText(RuleText.formatKeywords(c.keywordRules))
        loading = false
        render()
    }

    private fun windowsWith(): List<TimeWindow> =
        (if (quietOn && window.days.isNotEmpty()) listOf(window) else emptyList()) + extraWindows

    private fun render() = binding.run {
        loading = true
        val c = store.ruleConfig
        svPreserve.isChecked = store.preserveScreened
        svRulesEnabled.isChecked = c.enabled
        svFocus.text = c.focusUntil?.takeIf { it.isAfter(LocalDateTime.now()) }
            ?.let { getString(R.string.sv_focus_until, it.format(DateTimeFormatter.ofPattern("EEE HH:mm"))) }
            ?: getString(R.string.sv_off)
        svQuietEnabled.isChecked = quietOn
        svQuietStart.text = window.start.format(hhmm)
        svQuietEnd.text = window.end.format(hhmm)
        dayBoxes.forEach { (day, box) -> box.isChecked = day in window.days }
        val extra = extraWindows.size
        svQuietAction.text = alertName(c.quietHoursAction) +
            if (extra > 0) "\n" + getString(R.string.sv_extra_windows, extra) else ""
        svUnknownAction.text = alertName(c.unknownSenderAction)
        svCodes.isChecked = c.alertOneTimeCodes
        svCooldown.text = if (c.cooldownMinutes == 0) getString(R.string.sv_off) else getString(R.string.sv_minutes, c.cooldownMinutes)
        listOf(svQuietStartHolder, svQuietEndHolder, svDaysHolder).forEach { it.beVisibleIf(quietOn) }
        loading = false
    }

    private fun alertName(a: Alert) = getString(
        when (a) {
            Alert.ALERT -> R.string.sv_alert
            Alert.SILENT -> R.string.sv_silent
            Alert.HELD -> R.string.sv_held
        },
    )

    private fun pickAlert(title: Int, current: Alert, choices: List<Alert>, done: (Alert) -> Unit) {
        val items = ArrayList(choices.mapIndexed { i, a -> RadioItem(i, alertName(a), a) })
        RadioGroupDialog(this, items, choices.indexOf(current), title) { done(it as Alert) }
    }

    private fun pickTime(current: LocalTime, done: (LocalTime) -> Unit) {
        TimePickerDialog(this, getTimePickerDialogTheme(), { _, h, m -> done(LocalTime.of(h, m)) }, current.hour, current.minute, true).show()
    }

    private fun wire() = binding.run {
        svPreserveHolder.setOnClickListener { svPreserve.toggle(); store.preserveScreened = svPreserve.isChecked }
        svRulesEnabledHolder.setOnClickListener { svRulesEnabled.toggle(); edit { it.copy(enabled = svRulesEnabled.isChecked) } }
        svCodesHolder.setOnClickListener { svCodes.toggle(); edit { it.copy(alertOneTimeCodes = svCodes.isChecked) } }

        svFocusHolder.setOnClickListener {
            val now = LocalDateTime.now()
            val morning = now.toLocalDate().plusDays(if (now.toLocalTime() < LocalTime.of(8, 0)) 0 else 1).atTime(8, 0)
            val options = listOf<LocalDateTime?>(null, now.plusHours(1), now.plusHours(3), morning)
            val labels = listOf(R.string.sv_off, R.string.sv_focus_1h, R.string.sv_focus_3h, R.string.sv_focus_morning)
            val items = ArrayList(labels.mapIndexed { i, l -> RadioItem(i, getString(l), i) })
            RadioGroupDialog(this@SovereignSettingsActivity, items, -1, R.string.sv_focus) { i ->
                edit { it.copy(focusUntil = options[i as Int]) }
            }
        }

        svQuietEnabledHolder.setOnClickListener {
            svQuietEnabled.toggle()
            quietOn = svQuietEnabled.isChecked
            edit { it.copy(quietHours = windowsWith()) }
        }
        svQuietStartHolder.setOnClickListener { pickTime(window.start) { t -> window = window.copy(start = t); edit { it.copy(quietHours = windowsWith()) } } }
        svQuietEndHolder.setOnClickListener { pickTime(window.end) { t -> window = window.copy(end = t); edit { it.copy(quietHours = windowsWith()) } } }
        dayBoxes.forEach { (day, box) ->
            box.setOnCheckedChangeListener { _, checked ->
                if (loading) return@setOnCheckedChangeListener
                window = window.copy(days = if (checked) window.days + day else window.days - day)
                edit { it.copy(quietHours = windowsWith()) }
            }
        }
        svQuietActionHolder.setOnClickListener {
            pickAlert(R.string.sv_quiet_action, store.ruleConfig.quietHoursAction, listOf(Alert.SILENT, Alert.HELD, Alert.ALERT)) { a ->
                edit { it.copy(quietHoursAction = a) }
            }
        }
        svUnknownActionHolder.setOnClickListener {
            pickAlert(R.string.sv_unknown_action, store.ruleConfig.unknownSenderAction, listOf(Alert.ALERT, Alert.SILENT, Alert.HELD)) { a ->
                edit { it.copy(unknownSenderAction = a) }
            }
        }
        svCooldownHolder.setOnClickListener {
            val minutes = listOf(0, 5, 15, 30, 60)
            val items = ArrayList(minutes.mapIndexed { i, m -> RadioItem(i, if (m == 0) getString(R.string.sv_off) else getString(R.string.sv_minutes, m), m) })
            RadioGroupDialog(this@SovereignSettingsActivity, items, minutes.indexOf(store.ruleConfig.cooldownMinutes), R.string.sv_cooldown) { m ->
                edit { it.copy(cooldownMinutes = m as Int) }
            }
        }
        svPreviewKnownHolder.setOnClickListener { svPreviewKnown.toggle() }
        svPreviewRunHolder.setOnClickListener { preview() }
        svExportHolder.setOnClickListener { exportLauncher.launch("sovereign-sms-rules.json") }
        svImportHolder.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/plain")) }
    }

    /** Text areas are saved when leaving the screen and before any action that reads the config. */
    private fun saveText() {
        val priority = RuleText.parseAddresses(binding.svPriority.text.toString())
        val keywords = RuleText.parseKeywords(binding.svKeywords.text.toString())
        val c = store.ruleConfig
        if (priority != c.priorityAddresses || keywords != c.keywordRules) {
            store.ruleConfig = c.copy(priorityAddresses = priority, keywordRules = keywords)
        }
    }

    private fun preview() {
        saveText()
        val message = IncomingMessage(
            address = binding.svPreviewAddress.text.toString().trim(),
            isKnownContact = binding.svPreviewKnown.isChecked,
            body = binding.svPreviewBody.text.toString(),
            conversationPolicy = ConversationPolicy.DEFAULT,
            receivedAt = LocalDateTime.now(),
            lastAlertAt = null,
        )
        val d = RuleEngine.evaluate(store.ruleConfig, message)
        val tags = if (d.tags.isEmpty()) "" else "\nTags: " + d.tags.sorted().joinToString(", ")
        binding.svPreviewRun.text = alertName(d.alert) + " — " + d.explanation.joinToString("\n") + tags
    }

    companion object {
        /** Used by the conversation screen; kept here so policy labels live in one place. */
        fun policyLabels(activity: SimpleActivity) = listOf(
            ConversationPolicy.DEFAULT to activity.getString(R.string.sv_policy_default),
            ConversationPolicy.PRIORITY to activity.getString(R.string.sv_policy_priority),
            ConversationPolicy.MUTED to activity.getString(R.string.sv_policy_muted),
        )

        fun pickPolicy(activity: SimpleActivity, threadId: Long) {
            val store = Sovereign.store(activity)
            val labels = policyLabels(activity)
            val items = ArrayList(labels.mapIndexed { i, (p, l) -> RadioItem(i, l, p) })
            val current = labels.indexOfFirst { it.first == store.conversationPolicy(threadId) }
            RadioGroupDialog(activity, items, current, R.string.sv_policy) { store.setConversationPolicy(threadId, it as ConversationPolicy) }
        }
    }
}
