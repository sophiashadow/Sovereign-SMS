package com.sovereignops.sms.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.core.ledger.Basis
import com.sovereignops.sms.core.ledger.Ledger
import com.sovereignops.sms.core.ledger.LedgerEntry
import com.sovereignops.sms.vault.LedgerRow
import com.sovereignops.sms.vault.Vault
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyTextView
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.databinding.ActivitySovereignLedgerBinding
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The ledger: one row per working day, typed in by the owner and kept in the encrypted vault.
 * New days are cash basis: net realized cash = cash received − refunds − attributable cash
 * expenses, per labor hour (service + overhead). Receivables and restricted credits are recorded
 * but never counted as realized. Days saved before schema 3 stay legacy (gross − room cost) and
 * are shown apart. Derived figures are never stored. Exports go only where the owner picks.
 */
class SovereignLedgerActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySovereignLedgerBinding::inflate)
    private var entries: List<LedgerEntry> = emptyList()

    private val csvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) write(uri, Ledger.toCsv(entries))
    }
    private val mdLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) write(uri, Ledger.toMarkdown(entries))
    }

    private val store get() = Sovereign.store(this)
    private val unit get() = store.ledgerUnitTerm ?: getString(R.string.sv_ledger_unit_default)
    private val service get() = store.ledgerServiceTerm ?: getString(R.string.sv_ledger_service_default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.slScroll))
        setupMaterialScrollListener(scrollingView = binding.slScroll, topAppBar = binding.slAppbar)
        binding.slAdd.setOnClickListener { edit(null) }
        binding.slCsv.setOnClickListener {
            Unencrypted.confirm(this, R.string.sv_unencrypted_ledger) { csvLauncher.launch("ledger-${LocalDate.now()}.csv") }
        }
        binding.slMd.setOnClickListener {
            Unencrypted.confirm(this, R.string.sv_unencrypted_ledger) { mdLauncher.launch("ledger-${LocalDate.now()}.md") }
        }
        binding.slTerms.setOnClickListener { editTerms() }
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.slAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.slScroll)
    }

    private fun write(uri: android.net.Uri, text: String) = ensureBackgroundThread {
        val ok = runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
        runOnUiThread { ok.onSuccess { toast(R.string.sv_exported) }.onFailure { toast(getString(R.string.sv_import_failed, it.message ?: "")) } }
    }

    private fun load() = ensureBackgroundThread {
        val rows = runCatching { Vault.dao(this).ledger().mapNotNull { runCatching { it.toEntry() }.getOrNull() } }.getOrDefault(emptyList())
        runOnUiThread {
            entries = rows.sortedByDescending { it.date }
            render()
        }
    }

    private fun render() = binding.run {
        val s = Ledger.summarize(entries)
        slSummary.text = if (entries.isEmpty()) getString(R.string.sv_ledger_empty) else listOfNotNull(
            getString(
                R.string.sv_ledger_totals, s.days, Ledger.percent(s.showRate), s.showed, s.booked, Ledger.money(s.netRealizedCents),
                Ledger.money(s.cashReceivedCents), Ledger.money(s.refundCents), Ledger.money(s.expenseCents),
                Ledger.perHour(s.netCashPerLaborHour), Ledger.hours(s.cashLaborHours),
                Ledger.money(s.receivableCents), Ledger.money(s.restrictedCreditCents), Ledger.percent(s.serviceShare), Ledger.hours(s.laborHours),
            ),
            if (s.legacyDays > 0) getString(R.string.sv_ledger_totals_legacy, s.legacyDays, Ledger.money(s.legacyNetCents), Ledger.perHour(s.legacyPerHour)) else null,
        ).joinToString("\n")
        slWeekdays.text = Ledger.byDayOfWeek(entries).joinToString("\n") { (d, x) ->
            getString(R.string.sv_ledger_weekday, d.getDisplayName(TextStyle.SHORT, Locale.getDefault()), x.days, Ledger.percent(x.showRate), Ledger.perHour(x.netCashPerLaborHour))
        }
        slRows.removeAllViews()
        val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.medium_margin)
        entries.forEach { e ->
            slRows.addView(MyTextView(this@SovereignLedgerActivity).apply {
                val day = e.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val row = if (e.basis == Basis.CASH) {
                    getString(R.string.sv_ledger_row, e.date.toString(), day, Ledger.money(e.netRealizedCents!!), Ledger.percent(e.showRate), Ledger.perHour(e.netCashPerLaborHour))
                } else {
                    getString(R.string.sv_ledger_row_legacy, e.date.toString(), day, Ledger.money(e.legacyNetCents!!), Ledger.percent(e.showRate), Ledger.perHour(e.legacyPerHour))
                }
                text = listOfNotNull(row, e.note?.takeIf { it.isNotBlank() }).joinToString("\n")
                setPadding(0, pad, 0, pad)
                setOnClickListener { edit(e) }
                setOnLongClickListener { delete(e); true }
            })
        }
        updateTextColors(slScroll)
    }

    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        setHint(hint)
        setText(value)
        inputType = type
        isSingleLine = type != InputType.TYPE_CLASS_TEXT
    }

    private fun form(vararg fields: EditText) = android.widget.ScrollView(this).apply {
        val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        addView(LinearLayout(this@SovereignLedgerActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            fields.forEach(::addView)
        })
    }

    private fun editTerms() {
        val u = field(getString(R.string.sv_ledger_terms_unit), store.ledgerUnitTerm.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val h = field(getString(R.string.sv_ledger_terms_service), store.ledgerServiceTerm.orEmpty(), InputType.TYPE_CLASS_TEXT)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sv_ledger_terms)
            .setView(form(u, h))
            .setPositiveButton(org.fossify.commons.R.string.save) { _, _ ->
                store.ledgerUnitTerm = u.text.toString()
                store.ledgerServiceTerm = h.text.toString()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * New days are cash basis. Editing a legacy day keeps it legacy, with its gross and room cost:
     * the app never decides after the fact what an old figure meant.
     */
    private fun edit(existing: LedgerEntry?) {
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val num = InputType.TYPE_CLASS_NUMBER
        val e = existing
        val legacy = e?.basis == Basis.LEGACY
        fun money(hint: Int, v: Long?) = field(getString(hint), v?.let { Ledger.money(it) }.orEmpty(), dec)
        val date = field(getString(R.string.sv_ledger_date), (e?.date ?: LocalDate.now()).toString(), InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        val booked = field(getString(R.string.sv_ledger_booked, unit), e?.clientsBooked?.toString().orEmpty(), num)
        val showed = field(getString(R.string.sv_ledger_showed, unit), e?.clientsShowed?.toString().orEmpty(), num)
        val room = money(R.string.sv_ledger_room, e?.roomCostCents)
        val gross = money(R.string.sv_ledger_gross, e?.grossCents)
        val received = money(R.string.sv_ledger_received, e?.cashReceivedCents)
        val refunds = money(R.string.sv_ledger_refunds, e?.refundCents)
        val expenses = money(R.string.sv_ledger_expenses, e?.expenseCents)
        val receivables = money(R.string.sv_ledger_receivables, e?.receivableCents)
        val credits = money(R.string.sv_ledger_credits, e?.restrictedCreditCents)
        val session = field(service, e?.sessionHours?.toString().orEmpty(), dec)
        val overhead = field(getString(R.string.sv_ledger_overhead), e?.overheadHours?.toString().orEmpty(), dec)
        val note = field(getString(R.string.sv_ledger_note), e?.note.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val moneyFields = if (legacy) listOf(room, gross) else listOf(received, refunds, expenses, receivables, credits)
        val fields = listOf(date, booked, showed) + moneyFields + listOf(session, overhead, note)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (legacy) R.string.sv_ledger_legacy_title else if (e == null) R.string.sv_ledger_add else R.string.sv_ledger)
            .setView(form(*fields.toTypedArray()))
            .setPositiveButton(org.fossify.commons.R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Keep the dialog open on a bad field so nothing typed is lost.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            fun hours(t: EditText) = t.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()?.takeIf { it >= 0 && it <= 24 }
            fun count(t: EditText) = t.text.toString().trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 }
            fun cents(t: EditText) = Ledger.parseMoney(t.text.toString())
            val parsed = runCatching { LocalDate.parse(date.text.toString().trim()) }.getOrNull()
            val problems = buildList {
                if (parsed == null) add(date.hint)
                if (count(booked) == null) add(booked.hint)
                if (count(showed) == null) add(showed.hint)
                moneyFields.filter { cents(it) == null }.forEach { add(it.hint) }
                if (hours(session) == null) add(session.hint)
                if (hours(overhead) == null) add(overhead.hint)
            }
            if (problems.isNotEmpty()) {
                toast(getString(R.string.sv_ledger_invalid, problems.joinToString(", ")))
                return@setOnClickListener
            }
            val base = LedgerEntry(
                id = e?.id ?: 0, date = parsed!!, clientsBooked = count(booked)!!, clientsShowed = count(showed)!!,
                sessionHours = hours(session)!!, overheadHours = hours(overhead)!!, note = note.text.toString().trim().ifEmpty { null },
            )
            val entry = if (legacy) {
                base.copy(basis = Basis.LEGACY, roomCostCents = cents(room)!!, grossCents = cents(gross)!!)
            } else {
                base.copy(
                    basis = Basis.CASH, cashReceivedCents = cents(received)!!, refundCents = cents(refunds)!!, expenseCents = cents(expenses)!!,
                    receivableCents = cents(receivables)!!, restrictedCreditCents = cents(credits)!!,
                )
            }
            dialog.dismiss()
            ensureBackgroundThread {
                val dao = Vault.dao(this)
                val row = LedgerRow.from(entry)
                runCatching { if (row.id == 0L) dao.addLedger(row) else dao.putLedgerRow(row) }
                runOnUiThread { toast(R.string.sv_ledger_saved); load() }
            }
        }
    }

    private fun delete(e: LedgerEntry) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.sv_ledger_delete)
            .setPositiveButton(org.fossify.commons.R.string.delete) { _, _ ->
                ensureBackgroundThread {
                    runCatching { Vault.dao(this).deleteLedger(e.id) }
                    runOnUiThread { toast(R.string.sv_ledger_deleted); load() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
