package com.sovereignops.sms.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.SovereignStore
import com.sovereignops.sms.core.crm.Checklist
import com.sovereignops.sms.core.crm.Stage
import com.sovereignops.sms.core.guard.GuardContext
import com.sovereignops.sms.core.lock.LockTimeout
import com.sovereignops.sms.core.pack.Presets
import com.sovereignops.sms.core.rules.FlagSignal
import com.sovereignops.sms.core.guard.DraftGuard
import com.sovereignops.sms.core.guard.GuardText
import com.sovereignops.sms.core.pack.Starters
import com.sovereignops.sms.core.rules.Alert
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.templates.Templates
import com.sovereignops.sms.core.rules.ConversationPolicy
import com.sovereignops.sms.core.rules.IncomingMessage
import com.sovereignops.sms.core.rules.RuleConfig
import com.sovereignops.sms.core.rules.RuleEngine
import com.sovereignops.sms.core.rules.RuleText
import com.sovereignops.sms.core.rules.FocusTimer
import com.sovereignops.sms.core.rules.ScheduleText
import com.sovereignops.sms.core.rules.TimeWindow
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.appLockManager
import com.sovereignops.sms.core.io.readAtMost
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getTimePickerDialogTheme
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.models.RadioItem
import org.fossify.messages.extensions.messagesDB
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
    private var previewStage: Stage? = null
    private var reloadOnResume = false

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        saveText()
        runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(store.exportPack().toByteArray()) } }
            .onSuccess { toast(R.string.sv_exported) }
            .onFailure { toast(getString(R.string.sv_import_failed, it.message ?: "")) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)!!.use { it.readAtMost(com.sovereignops.sms.core.io.Limits.SETTINGS_PACK).toString(Charsets.UTF_8) } }
        val error = text.fold({ store.importPack(it) }, { if (it is com.sovereignops.sms.core.io.TooLarge) it.message else "unreadable file" })
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
        summaryRequested = intent?.getBooleanExtra(com.sovereignops.sms.SovereignSummary.EXTRA_SHOW, false) == true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(com.sovereignops.sms.SovereignSummary.EXTRA_SHOW, false)) summaryRequested = true
    }

    /** Set by the summary notification; shown on the first resume while the app lock isn't up. */
    private var summaryRequested = false

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.svAppbar, NavigationIcon.Arrow)
        // After a restore the stored settings may have changed; reload so stale text is not saved back over them.
        if (reloadOnResume) {
            reloadOnResume = false
            load()
        }
        updateTextColors(binding.svScrollview)
        if (summaryRequested && !(baseConfig.isAppPasswordProtectionOn && appLockManager.isLocked())) {
            summaryRequested = false
            showSummary()
        }
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
        binding.svGuard.setText(GuardText.format(store.guardRules))
        binding.svTemplates.setText(Templates.format(store.templates))
        binding.svChecklist.setText(Checklist.format(store.checklistItems))
        binding.svFlags.setText(GuardText.format(store.flagRules))
        binding.svSchedules.setText(ScheduleText.format(c.schedules))
        binding.svSchedulesInvalid.beVisibleIf(false)
        loading = false
        render()
    }

    private fun windowsWith(): List<TimeWindow> =
        (if (quietOn && window.days.isNotEmpty()) listOf(window) else emptyList()) + extraWindows

    private fun render() = binding.run {
        loading = true
        val c = store.ruleConfig
        svPreserve.isChecked = store.preserveScreened
        svAliasNotif.isChecked = store.aliasInNotifications
        svLock.text = getString(if (baseConfig.isAppPasswordProtectionOn) R.string.sv_lock_on else R.string.sv_lock_off)
        svLockTimeout.text = getString(timeoutLabel(store.lockTimeout))
        svSecure.isChecked = store.secureScreen
        svCopyCode.isChecked = store.copyCodeAction
        svCover.isChecked = store.coverActive
        svConfirmSend.isChecked = store.confirmFlaggedSend
        svPresets.text = Presets.all.joinToString(" · ") { it.title.substringBefore(" —") }
        svPreviewStage.text = previewStage?.label ?: getString(R.string.sv_stage_none)
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
        svQuietPersonal.isChecked = Category.PERSONAL in c.quietHoursCategories
        svOffDuty.text = alertName(c.offDutyAction)
        val invalid = (DraftGuard.check("", store.guardRules).invalid + DraftGuard.check("", store.flagRules).invalid).map { it.name }
        svGuardInvalid.text = getString(R.string.sv_guard_invalid, invalid.distinct().joinToString(", "))
        svGuardInvalid.beVisibleIf(invalid.isNotEmpty())
        svCodes.isChecked = c.alertOneTimeCodes
        svTrace.text = getString(R.string.sv_trace_summary)
        svLedger.text = getString(R.string.sv_ledger_summary)
        svInsights.text = getString(R.string.sv_insights_summary)
        val sched = store.summarySchedule
        svSummaryEnabled.isChecked = store.summaryEnabled
        svSummaryNow.text = getString(R.string.sv_summary_now_summary)
        svSummaryTime.text = sched.time.format(hhmm)
        svSummaryDays.text = if (sched.days.size == 7) getString(R.string.sv_summary_every_day) else if (sched.days.isEmpty()) getString(R.string.sv_off)
            else DayOfWeek.entries.filter { it in sched.days }.joinToString(" ") { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
        listOf(svSummaryTimeHolder, svSummaryDaysHolder).forEach { it.beVisibleIf(store.summaryEnabled) }
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

    private fun timeoutLabel(t: LockTimeout) = when (t) {
        LockTimeout.IMMEDIATE -> R.string.sv_lock_immediate
        LockTimeout.ONE_MINUTE -> R.string.sv_lock_1m
        LockTimeout.FIVE_MINUTES -> R.string.sv_lock_5m
        LockTimeout.SCREEN_OFF -> R.string.sv_lock_screen_off
    }

    private fun wire() = binding.run {
        // The lock itself is upstream's (Settings → Password protection); this screen sets when it re-locks.
        svLockHolder.setOnClickListener { toast(R.string.sv_lock_where); finish() }
        svLockTimeoutHolder.setOnClickListener {
            val items = ArrayList(LockTimeout.entries.mapIndexed { i, t -> RadioItem(i, getString(timeoutLabel(t)), i) })
            RadioGroupDialog(this@SovereignSettingsActivity, items, store.lockTimeout.ordinal, R.string.sv_lock_timeout) { i ->
                store.lockTimeout = LockTimeout.entries[i as Int]
                com.sovereignops.sms.SovereignLock.apply(this@SovereignSettingsActivity)
                render()
            }
        }
        svSecureHolder.setOnClickListener {
            svSecure.toggle()
            store.secureScreen = svSecure.isChecked
            com.sovereignops.sms.SovereignLock.secure(this@SovereignSettingsActivity)
        }
        svCopyCodeHolder.setOnClickListener { svCopyCode.toggle(); store.copyCodeAction = svCopyCode.isChecked }
        svCoverHolder.setOnClickListener {
            if (store.coverActive) com.sovereignops.sms.SovereignCover.turnOff(this@SovereignSettingsActivity) { render() }
            else { com.sovereignops.sms.SovereignCover.turnOn(this@SovereignSettingsActivity); render() }
        }
        svPreserveHolder.setOnClickListener { svPreserve.toggle(); store.preserveScreened = svPreserve.isChecked }
        svRulesEnabledHolder.setOnClickListener { svRulesEnabled.toggle(); edit { it.copy(enabled = svRulesEnabled.isChecked) } }
        svAliasNotifHolder.setOnClickListener { svAliasNotif.toggle(); store.aliasInNotifications = svAliasNotif.isChecked }
        svConfirmSendHolder.setOnClickListener { svConfirmSend.toggle(); store.confirmFlaggedSend = svConfirmSend.isChecked }
        svPresetsHolder.setOnClickListener { pickPreset() }
        svPreviewStageHolder.setOnClickListener {
            val options = listOf<Stage?>(null) + Stage.entries
            val items = ArrayList(options.mapIndexed { i, s -> RadioItem(i, s?.label ?: getString(R.string.sv_stage_none), i) })
            RadioGroupDialog(this@SovereignSettingsActivity, items, options.indexOf(previewStage), R.string.sv_preview_stage) { i ->
                previewStage = options[i as Int]
                render()
            }
        }
        svCodesHolder.setOnClickListener { svCodes.toggle(); edit { it.copy(alertOneTimeCodes = svCodes.isChecked) } }

        svFocusHolder.setOnClickListener {
            val labels = listOf(getString(R.string.sv_off)) + FocusTimer.presetMinutes.map { resources.getQuantityString(R.plurals.sv_focus_minutes, it, it) } +
                getString(R.string.sv_focus_custom)
            val items = ArrayList(labels.mapIndexed { i, l -> RadioItem(i, l, i) })
            RadioGroupDialog(this@SovereignSettingsActivity, items, -1, R.string.sv_focus) { picked ->
                val i = picked as Int
                fun set(until: LocalDateTime?) {
                    edit { it.copy(focusUntil = until) }
                    com.sovereignops.sms.SovereignFocus.sync(this@SovereignSettingsActivity)
                }
                when {
                    i == 0 -> set(null)
                    i <= FocusTimer.presetMinutes.size -> set(FocusTimer.until(LocalDateTime.now(), FocusTimer.presetMinutes[i - 1]))
                    else -> pickTime(LocalTime.now().plusHours(1).withMinute(0)) { t -> set(FocusTimer.untilClock(LocalDateTime.now(), t)) }
                }
            }
        }
        svTraceHolder.setOnClickListener { showTrace() }
        svInsightsHolder.setOnClickListener { showInsights() }
        svSummaryEnabledHolder.setOnClickListener {
            svSummaryEnabled.toggle()
            com.sovereignops.sms.SovereignSummary.setEnabled(this@SovereignSettingsActivity, svSummaryEnabled.isChecked)
            render()
        }
        svSummaryTimeHolder.setOnClickListener {
            pickTime(store.summarySchedule.time) { t ->
                store.summarySchedule = store.summarySchedule.copy(time = t)
                com.sovereignops.sms.SovereignSummary.sync(this@SovereignSettingsActivity)
                render()
            }
        }
        svSummaryDaysHolder.setOnClickListener {
            val days = DayOfWeek.entries
            val checked = BooleanArray(7) { days[it] in store.summarySchedule.days }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SovereignSettingsActivity)
                .setTitle(R.string.sv_summary_days)
                .setMultiChoiceItems(days.map { it.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    store.summarySchedule = store.summarySchedule.copy(days = days.filterIndexed { i, _ -> checked[i] }.toSet())
                    com.sovereignops.sms.SovereignSummary.sync(this@SovereignSettingsActivity)
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        svSummaryNowHolder.setOnClickListener { showSummary() }
        svLedgerHolder.setOnClickListener {
            startActivity(android.content.Intent(this@SovereignSettingsActivity, SovereignLedgerActivity::class.java))
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
        svQuietPersonalHolder.setOnClickListener {
            svQuietPersonal.toggle()
            edit {
                val cats = if (svQuietPersonal.isChecked) it.quietHoursCategories + Category.PERSONAL else it.quietHoursCategories - Category.PERSONAL
                it.copy(quietHoursCategories = cats)
            }
        }
        svOffDutyHolder.setOnClickListener {
            pickAlert(R.string.sv_off_duty_action, store.ruleConfig.offDutyAction, listOf(Alert.SILENT, Alert.HELD, Alert.ALERT)) { a ->
                edit { it.copy(offDutyAction = a) }
            }
        }
        svStartersHolder.setOnClickListener { addStarters() }
        svPreviewKnownHolder.setOnClickListener { svPreviewKnown.toggle() }
        svPreviewRunHolder.setOnClickListener { preview() }
        svExportHolder.setOnClickListener { exportLauncher.launch("sovereign-sms-pack.json") }
        svImportHolder.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/plain")) }
        svBackupHolder.setOnClickListener {
            saveText()
            SovereignPortabilityActivity.start(this@SovereignSettingsActivity, SovereignPortabilityActivity.ACTION_BACKUP)
        }
        svNoticesHolder.setOnClickListener {
            val text = runCatching {
                listOf("NOTICES.txt", "sqlcipher-android-BSD-3-Clause.txt", "libtomcrypt-Unlicense.txt", "Apache-2.0.txt").joinToString("\n\n————\n\n") { f ->
                    assets.open("licenses/$f").use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }.getOrDefault("")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SovereignSettingsActivity)
                .setTitle(R.string.sv_notices).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
        }
        svUnlinkedHolder.setOnClickListener { SovereignUnlinked.show(this@SovereignSettingsActivity) }
        svRestoreHolder.setOnClickListener {
            saveText()
            reloadOnResume = true
            SovereignPortabilityActivity.start(this@SovereignSettingsActivity, SovereignPortabilityActivity.ACTION_RESTORE)
        }
    }

    /** Text areas are saved when leaving the screen and before any action that reads the config. */
    private fun saveText() {
        val priority = RuleText.parseAddresses(binding.svPriority.text.toString())
        val keywords = RuleText.parseKeywords(binding.svKeywords.text.toString())
        val scheduleText = binding.svSchedules.text.toString()
        val schedules = ScheduleText.parse(scheduleText)
        val badSchedules = ScheduleText.invalid(scheduleText)
        binding.svSchedulesInvalid.text = getString(R.string.sv_schedules_invalid, badSchedules.joinToString("; "))
        binding.svSchedulesInvalid.beVisibleIf(badSchedules.isNotEmpty())
        val c = store.ruleConfig
        if (priority != c.priorityAddresses || keywords != c.keywordRules || schedules != c.schedules) {
            store.ruleConfig = c.copy(priorityAddresses = priority, keywordRules = keywords, schedules = schedules)
        }
        val guard = GuardText.parse(binding.svGuard.text.toString())
        if (guard != store.guardRules) store.guardRules = guard
        val templates = Templates.parse(binding.svTemplates.text.toString())
        if (templates != store.templates) store.templates = templates
        val flags = GuardText.parse(binding.svFlags.text.toString())
        if (flags != store.flagRules) {
            store.flagRules = flags
            SovereignFlags.invalidate()
        }
        val checklist = Checklist.parse(binding.svChecklist.text.toString())
        if (checklist != store.checklistItems) store.checklistItems = checklist
    }

    /** Appends the neutral starter set to the text areas, disabled, for the user to review. */
    private fun addStarters() {
        saveText()
        val c = store.ruleConfig
        val haveKw = c.keywordRules.map { it.keyword.lowercase() }.toSet()
        val kws = c.keywordRules + Starters.keywords.filter { it.keyword.lowercase() !in haveKw }
        store.ruleConfig = c.copy(keywordRules = kws.mapIndexed { i, k -> k.copy(id = "k${i + 1}") })
        val haveGuard = store.guardRules.map { it.name }.toSet()
        store.guardRules = store.guardRules + Starters.guard.filter { it.name !in haveGuard }
        val haveT = store.templates.map { it.title }.toSet()
        store.templates = store.templates + Starters.templates.filter { it.title !in haveT }
        load()
        toast(R.string.sv_starters_added)
    }

    /** Appends a preset's rules, switched off, skipping names that already exist. */
    private fun pickPreset() {
        saveText()
        val items = ArrayList(Presets.all.mapIndexed { i, p -> RadioItem(i, p.title, i) })
        RadioGroupDialog(this, items, -1, R.string.sv_add_preset) { i ->
            val preset = Presets.all[i as Int]
            fun <T> addNew(have: List<T>, add: List<T>, key: (T) -> String): List<T> {
                val names = have.map(key).toSet()
                return have + add.filter { key(it) !in names }.map { it }
            }
            preset.pack.guard?.let { g -> store.guardRules = addNew(store.guardRules, g.map { it.copy(enabled = false) }) { it.name } }
            preset.pack.flags?.let { f -> store.flagRules = addNew(store.flagRules, f.map { it.copy(enabled = false) }) { it.name } }
            if (preset == Presets.sharedSimQuiet) {
                val c = store.ruleConfig
                val kws = addNew(c.keywordRules, Presets.sharedSimKeywords.map { it.copy(enabled = false) }) { it.keyword.lowercase() }
                val sch = addNew(c.schedules, Presets.sharedSimSchedules.map { it.copy(enabled = false) }) { it.name }
                store.ruleConfig = c.copy(keywordRules = kws.mapIndexed { n, k -> k.copy(id = "k${n + 1}") }, schedules = sch)
            }
            SovereignFlags.invalidate()
            load()
            toast(R.string.sv_preset_added)
        }
    }

    /** Today so far and yesterday, built on demand from timestamps and the per-day counters. */
    private fun showSummary() = org.fossify.commons.helpers.ensureBackgroundThread {
        val text = runCatching {
            val today = com.sovereignops.sms.SovereignSummary.today(this)
            val yesterday = com.sovereignops.sms.SovereignSummary.yesterday(this)
            com.sovereignops.sms.SovereignSummary.describe(this, today) + "\n" +
                com.sovereignops.sms.SovereignSummary.describe(this, yesterday) + "\n" + getString(R.string.sv_summary_note)
        }.getOrElse { getString(R.string.sv_summary_failed) }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sv_summary)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Workload figures from message timestamps and direction only; shown, never stored. */
    private fun showInsights() = org.fossify.commons.helpers.ensureBackgroundThread {
        val days = 30 // matches the 30 days written in the sv_insights strings
        val now = System.currentTimeMillis()
        val rows = runCatching { messagesDB.getStamps(((now - days * 86_400_000L) / 1000L).toInt()) }.getOrDefault(emptyList())
        val ins = com.sovereignops.sms.core.insights.Insights.compute(rows.map { it.toStamp() }, now, java.time.ZoneId.systemDefault(), days)
        fun dur(ms: Long) = com.sovereignops.sms.Durations.format(this, ms)
        val text = if (ins.incoming + ins.outgoing == 0) getString(R.string.sv_insights_empty) else buildString {
            appendLine(getString(R.string.sv_insights_volume, ins.incoming, ins.outgoing))
            val hour = ins.busiestHour?.let { getString(R.string.sv_hour_range, it) } ?: "–"
            val weekday = ins.busiestWeekday?.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) ?: "–"
            appendLine(getString(R.string.sv_insights_busiest, hour, weekday))
            appendLine()
            val median = ins.medianResponseMillis
            val p90 = ins.p90ResponseMillis
            appendLine(if (median != null && p90 != null) getString(R.string.sv_insights_replies, ins.responses, dur(median), dur(p90), ins.slowReplies) else getString(R.string.sv_insights_no_replies))
            appendLine(if (ins.waiting.isEmpty()) getString(R.string.sv_insights_none_waiting) else getString(R.string.sv_insights_waiting, ins.waiting.size, dur(ins.waiting.first().waitingMillis)))
            appendLine()
            append(getString(R.string.sv_insights_note))
        }
        runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sv_insights)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** The decision log: newest first, rule and reasons only. Clearing is the owner's choice. */
    private fun showTrace() = org.fossify.commons.helpers.ensureBackgroundThread {
        val rows = runCatching { com.sovereignops.sms.vault.Vault.dao(this).trace(50) }.getOrDefault(emptyList())
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        val text = if (rows.isEmpty()) getString(R.string.sv_trace_empty) else rows.joinToString("\n\n") { r ->
            val at = java.time.Instant.ofEpochMilli(r.at).atZone(java.time.ZoneId.systemDefault()).format(fmt)
            "$at · thread ${r.threadId} · ${r.alert.lowercase()} (${r.rule.lowercase()})\n" + r.explanation
        }
        runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sv_trace)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.sv_trace_clear) { _, _ ->
                    org.fossify.commons.helpers.ensureBackgroundThread {
                        runCatching { com.sovereignops.sms.vault.Vault.dao(this).clearTrace() }
                        runOnUiThread { toast(R.string.sv_trace_cleared) }
                    }
                }
                .show()
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
        val ctx = GuardContext(stage = previewStage)
        val flagHits = DraftGuard.check(message.body, store.flagRules, ctx)
        val withFlags = RuleEngine.evaluate(
            store.ruleConfig,
            message.copy(flags = flagHits.hits.map { FlagSignal(it.rule.name, it.rule.silent, it.rule.tag) }),
        )
        val flagText = if (flagHits.warn) "\nIncoming flags: " + DraftGuard.summary(flagHits) else ""
        val guard = DraftGuard.check(message.body, store.guardRules, ctx)
        val guardText = if (guard.warn) "\nAs a draft: " + DraftGuard.summary(guard) else ""
        val tagText = if (withFlags.tags.isEmpty()) "" else "\nTags: " + withFlags.tags.sorted().joinToString(", ")
        binding.svPreviewRun.text = alertName(withFlags.alert) + " — " + withFlags.explanation.joinToString("\n") + tagText + flagText + guardText
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
