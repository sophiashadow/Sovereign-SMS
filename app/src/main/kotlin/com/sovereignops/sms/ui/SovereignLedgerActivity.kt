package com.sovereignops.sms.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * The ledger: one row per working day, typed in by the owner and kept in the encrypted
 * vault. Show rate, net and BCR are derived, never stored. Exports go only where the
 * owner picks in the system file dialog.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.slScroll))
        setupMaterialScrollListener(scrollingView = binding.slScroll, topAppBar = binding.slAppbar)
        binding.slAdd.setOnClickListener { edit(null) }
        binding.slCsv.setOnClickListener { csvLauncher.launch("ledger-${LocalDate.now()}.csv") }
        binding.slMd.setOnClickListener { mdLauncher.launch("ledger-${LocalDate.now()}.md") }
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
        slSummary.text = if (entries.isEmpty()) getString(R.string.sv_ledger_empty) else
            getString(
                R.string.sv_ledger_totals, s.days, Ledger.percent(s.showRate), s.showed, s.booked, Ledger.money(s.netCents),
                Ledger.money(s.grossCents), Ledger.money(s.roomCostCents), Ledger.perHour(s.bcrCentsPerHour), Ledger.percent(s.sessionShare),
                "%.1f".format(s.totalHours),
            )
        slWeekdays.text = Ledger.byDayOfWeek(entries).joinToString("\n") { (d, x) ->
            getString(R.string.sv_ledger_weekday, d.getDisplayName(TextStyle.SHORT, Locale.getDefault()), x.days, Ledger.percent(x.showRate), Ledger.perHour(x.bcrCentsPerHour))
        }
        slRows.removeAllViews()
        val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.medium_margin)
        entries.forEach { e ->
            slRows.addView(MyTextView(this@SovereignLedgerActivity).apply {
                val row = getString(
                    R.string.sv_ledger_row, e.date.toString(), e.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    Ledger.money(e.netCents), Ledger.percent(e.showRate), Ledger.perHour(e.bcrCentsPerHour),
                )
                text = listOfNotNull(row, e.note?.takeIf { it.isNotBlank() }).joinToString("\n")
                setPadding(0, pad, 0, pad)
                setOnClickListener { edit(e) }
                setOnLongClickListener { delete(e); true }
            })
        }
        updateTextColors(slScroll)
    }

    private fun field(hint: Int, value: String, type: Int) = EditText(this).apply {
        setHint(hint)
        setText(value)
        inputType = type
        isSingleLine = type != InputType.TYPE_CLASS_TEXT
    }

    private fun edit(existing: LedgerEntry?) {
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val num = InputType.TYPE_CLASS_NUMBER
        val e = existing
        val date = field(R.string.sv_ledger_date, (e?.date ?: LocalDate.now()).toString(), InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        val room = field(R.string.sv_ledger_room, e?.let { Ledger.money(it.roomCostCents) }.orEmpty(), dec)
        val booked = field(R.string.sv_ledger_booked, e?.clientsBooked?.toString().orEmpty(), num)
        val showed = field(R.string.sv_ledger_showed, e?.clientsShowed?.toString().orEmpty(), num)
        val gross = field(R.string.sv_ledger_gross, e?.let { Ledger.money(it.grossCents) }.orEmpty(), dec)
        val session = field(R.string.sv_ledger_session, e?.sessionHours?.toString().orEmpty(), dec)
        val overhead = field(R.string.sv_ledger_overhead, e?.overheadHours?.toString().orEmpty(), dec)
        val note = field(R.string.sv_ledger_note, e?.note.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(date, room, booked, showed, gross, session, overhead, note).forEach(::addView)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (e == null) R.string.sv_ledger_add else R.string.sv_ledger)
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setPositiveButton(org.fossify.commons.R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Keep the dialog open on a bad field so nothing typed is lost.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            fun hours(t: EditText) = t.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()?.takeIf { it >= 0 && it <= 24 }
            fun count(t: EditText) = t.text.toString().trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 }
            val parsed = runCatching { LocalDate.parse(date.text.toString().trim()) }.getOrNull()
            val problems = listOfNotNull(
                if (parsed == null) getString(R.string.sv_ledger_date) else null,
                if (Ledger.parseMoney(room.text.toString()) == null) getString(R.string.sv_ledger_room) else null,
                if (count(booked) == null) getString(R.string.sv_ledger_booked) else null,
                if (count(showed) == null) getString(R.string.sv_ledger_showed) else null,
                if (Ledger.parseMoney(gross.text.toString()) == null) getString(R.string.sv_ledger_gross) else null,
                if (hours(session) == null) getString(R.string.sv_ledger_session) else null,
                if (hours(overhead) == null) getString(R.string.sv_ledger_overhead) else null,
            )
            if (problems.isNotEmpty()) {
                toast(getString(R.string.sv_ledger_invalid, problems.joinToString(", ")))
                return@setOnClickListener
            }
            val entry = LedgerEntry(
                id = e?.id ?: 0, date = parsed!!, roomCostCents = Ledger.parseMoney(room.text.toString())!!,
                clientsBooked = count(booked)!!, clientsShowed = count(showed)!!, grossCents = Ledger.parseMoney(gross.text.toString())!!,
                sessionHours = hours(session)!!, overheadHours = hours(overhead)!!, note = note.text.toString().trim().ifEmpty { null },
            )
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
