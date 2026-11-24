package com.sovereignops.sms.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.core.backup.Backup
import com.sovereignops.sms.core.backup.BackupContent
import com.sovereignops.sms.core.backup.CardDoc
import com.sovereignops.sms.core.backup.LedgerDoc
import com.sovereignops.sms.core.backup.QuarantineDoc
import com.sovereignops.sms.core.backup.RestoreMode
import com.sovereignops.sms.core.backup.SovBak
import com.sovereignops.sms.core.backup.VaultPart
import com.sovereignops.sms.core.crm.Checklist
import com.sovereignops.sms.core.preserve.ArchiveAttachment
import com.sovereignops.sms.core.preserve.ArchiveDoc
import com.sovereignops.sms.core.preserve.ArchiveMessage
import com.sovereignops.sms.core.preserve.ThreadArchive
import com.sovereignops.sms.vault.CardRow
import com.sovereignops.sms.vault.LedgerRow
import com.sovereignops.sms.vault.QuarantineRow
import com.sovereignops.sms.vault.Vault
import com.sovereignops.sms.vault.VaultCache
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import com.sovereignops.sms.core.crm.ConversationNumbers
import com.sovereignops.sms.core.crm.Relink
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.messagesDB
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Owner-initiated portability, all through the system file picker:
 * - Preserve thread: one conversation as a ZIP (Markdown, exact JSON, attachments,
 *   manifest.sha256), re-read and checked after writing. The messages are not changed.
 * - Backup: an encrypted `.sovbak` of settings and the vault, re-opened after writing.
 * - Restore: merge into, or overwrite, this phone's Sovereign data in one transaction;
 *   nothing is changed until the file has decrypted and parsed.
 * Not exported; started only from this app's own menus.
 */
class SovereignPortabilityActivity : SimpleActivity() {
    private lateinit var status: TextView
    private var password: CharArray? = null

    private val zipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) finish() else preserve(uri)
    }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) finish() else backup(uri)
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else askPassword(confirm = false) { restore(uri) }
    }

    private val threadId get() = intent.getLongExtra(EXTRA_THREAD, 0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        status = TextView(this).apply { setPadding(pad, pad * 3, pad, pad) }
        setContentView(status)
        if (savedInstanceState != null) return
        when (intent.action) {
            ACTION_PRESERVE -> zipLauncher.launch("thread-${threadId}-${LocalDate.now()}.zip")
            ACTION_BACKUP -> askPassword(confirm = true) { backupLauncher.launch("sovereign-sms-${LocalDate.now()}.sovbak") }
            ACTION_RESTORE -> restoreLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            else -> finish()
        }
    }

    override fun onDestroy() {
        password?.fill('\u0000')
        super.onDestroy()
    }

    private fun say(text: String) = runOnUiThread { status.text = text }

    private fun done(message: String) = runOnUiThread {
        toast(message)
        finish()
    }

    // --- Preserve thread ---------------------------------------------------------------

    private fun preserve(uri: Uri) {
        say(getString(R.string.sv_working))
        ensureBackgroundThread {
            val result = runCatching {
                val messages = messagesDB.getThreadMessages(threadId)
                val files = HashMap<String, ByteArray>()
                val archived = messages.map { m ->
                    val atts = m.attachment?.attachments.orEmpty().mapIndexed { i, a ->
                        val name = a.filename.ifBlank { "attachment" }
                        val path = ThreadArchive.attachmentPath(m.id, i, name)
                        readCapped(a.getUri())?.let { files[path] = it }
                        ArchiveAttachment(path, a.mimetype, name)
                    }
                    ArchiveMessage(
                        id = m.id, atMillis = m.millis(), direction = if (m.isReceivedMessage()) "in" else "out",
                        sender = if (m.isReceivedMessage()) m.senderPhoneNumber else "me", body = m.body, attachments = atts,
                    )
                }
                val participants = messages.flatMap { it.participants }.flatMap { c -> c.phoneNumbers.map { it.normalizedNumber.ifEmpty { it.value } } }.distinct()
                val title = VaultCache.card(threadId)?.alias ?: getString(R.string.sv_preserve_title, threadId)
                val doc = ArchiveDoc(title = title, participants = participants, exportedAtMillis = System.currentTimeMillis(), messages = archived)
                val entries = ThreadArchive.entries(doc, files, ZoneId.systemDefault())
                contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    ZipOutputStream(out).use { zip -> entries.forEach { (p, b) -> zip.putNextEntry(ZipEntry(p)); zip.write(b); zip.closeEntry() } }
                }
                // Read back what actually landed on storage and check it against the manifest.
                val back = LinkedHashMap<String, ByteArray>()
                contentResolver.openInputStream(uri)!!.use { input ->
                    ZipInputStream(input).use { zip -> generateSequence { zip.nextEntry }.forEach { e -> back[e.name] = zip.readBytes() } }
                }
                val bad = ThreadArchive.verify(back)
                check(bad.isEmpty()) { "check failed: " + bad.joinToString() }
                archived.size to archived.sumOf { m -> m.attachments.count { it.path !in files } }
            }
            result.fold(
                onSuccess = { (n, missing) -> done(getString(R.string.sv_preserve_done, n, missing)) },
                onFailure = { done(getString(R.string.sv_import_failed, it.message ?: "")) },
            )
        }
    }

    private fun readCapped(uri: Uri): ByteArray? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_ATTACHMENT) return null
            }
            out.toByteArray()
        }
    }.getOrNull()

    // --- Backup / restore --------------------------------------------------------------

    private fun askPassword(confirm: Boolean, then: () -> Unit) {
        fun field(hint: Int) = EditText(this).apply {
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val first = field(R.string.sv_backup_password)
        val second = field(R.string.sv_backup_password_again)
        val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(first)
            if (confirm) addView(second)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (confirm) R.string.sv_backup else R.string.sv_restore)
            .setMessage(if (confirm) R.string.sv_backup_note else R.string.sv_restore_note)
            .setView(form)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val a = first.text.toString()
            when {
                confirm && a.length < SovBak.MIN_PASSWORD -> toast(getString(R.string.sv_backup_short, SovBak.MIN_PASSWORD))
                confirm && a != second.text.toString() -> toast(R.string.sv_backup_mismatch)
                a.isEmpty() -> Unit
                else -> {
                    password = a.toCharArray()
                    first.text.clear()
                    second.text.clear()
                    dialog.dismiss()
                    then()
                }
            }
        }
    }

    private fun snapshot(): BackupContent {
        val dao = Vault.dao(this)
        val conversations = runCatching { conversationsDB.getNonArchived() + conversationsDB.getAllArchived() }.getOrDefault(emptyList())
        val numbers = conversations.associate { it.threadId to it.phoneNumber }
        val store = Sovereign.store(this)
        return BackupContent(
            pack = store.exportPack(),
            checklist = Checklist.format(store.checklistItems),
            cards = dao.cards().map { r ->
                val addresses = (numbers[r.threadId] ?: r.addresses.takeIf { r.threadId < 0 }).orEmpty()
                CardDoc(r.threadId, addresses.split(',').map(String::trim).filter(String::isNotEmpty), r.alias, r.stage,
                    r.checked, r.tier, r.optIn, r.notes, r.timeWindow, r.pings, r.lastContacted)
            },
            quarantine = dao.quarantine().map { QuarantineDoc(it.address, it.addedAt) },
            ledger = dao.ledger().map { LedgerDoc(it.date, it.roomCostCents, it.booked, it.showed, it.grossCents, it.sessionHours, it.overheadHours, it.note) },
        )
    }

    private fun backup(uri: Uri) {
        val pw = password ?: return finish()
        say(getString(R.string.sv_working))
        ensureBackgroundThread {
            val result = runCatching {
                val plain = Backup.encode(snapshot())
                val file = SovBak.seal(plain, pw.copyOf(), System.currentTimeMillis())
                plain.fill(0)
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(file) }
                val back = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val reopened = SovBak.open(back, pw.copyOf()).getOrThrow()
                Backup.decode(reopened).cards.size
            }
            result.fold(
                onSuccess = { done(getString(R.string.sv_backup_done)) },
                onFailure = { done(getString(R.string.sv_import_failed, it.message ?: "")) },
            )
        }
    }

    private fun restore(uri: Uri) {
        val pw = password ?: return finish()
        say(getString(R.string.sv_working))
        ensureBackgroundThread {
            val content = runCatching {
                val file = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                Backup.decode(SovBak.open(file, pw.copyOf()).getOrThrow())
            }
            content.fold(
                onSuccess = { c -> runOnUiThread { chooseMode(c) } },
                onFailure = { done(getString(R.string.sv_import_failed, it.message ?: "")) },
            )
        }
    }

    private fun chooseMode(c: BackupContent) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sv_restore)
            .setMessage(getString(R.string.sv_restore_found, c.cards.size, c.quarantine.size, c.ledger.size))
            .setPositiveButton(R.string.sv_restore_merge) { _, _ -> apply(c, RestoreMode.MERGE) }
            .setNeutralButton(R.string.sv_restore_overwrite) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.sv_restore_overwrite_confirm)
                    .setPositiveButton(R.string.sv_restore_overwrite) { _, _ -> apply(c, RestoreMode.OVERWRITE) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun apply(c: BackupContent, mode: RestoreMode) {
        say(getString(R.string.sv_working))
        ensureBackgroundThread {
            val result = runCatching {
                val conversations = runCatching { conversationsDB.getNonArchived() + conversationsDB.getAllArchived() }.getOrDefault(emptyList())
                val numbered = conversations.map { ConversationNumbers(it.threadId, it.phoneNumber.split(',').map(String::trim).filter(String::isNotEmpty)) }
                val incomingCards = Backup.remap(c.cards) { numbers -> Relink.find(numbers, numbered) }
                val current = snapshot()
                val merged = Backup.combine(
                    VaultPart(current.cards, current.quarantine, current.ledger),
                    VaultPart(incomingCards, c.quarantine, c.ledger), mode,
                )
                val db = Vault.database(this)
                val dao = db.dao()
                db.runInTransaction {
                    dao.clearCards()
                    dao.clearQuarantine()
                    dao.clearLedger()
                    merged.cards.forEach { d ->
                        dao.putCard(CardRow(d.threadId, d.alias, d.stage, d.checked, d.tier, d.optIn, d.notes, d.timeWindow, d.pings, d.lastContacted,
                            addresses = if (d.threadId < 0) d.addresses.joinToString(",").ifEmpty { null } else null))
                    }
                    merged.quarantine.forEach { dao.putQuarantine(QuarantineRow(it.address, it.addedAt)) }
                    merged.ledger.forEach { l ->
                        dao.addLedger(LedgerRow(0, l.date, l.roomCostCents, l.booked, l.showed, l.grossCents, l.sessionHours, l.overheadHours, l.note))
                    }
                }
                if (mode == RestoreMode.OVERWRITE) {
                    val store = Sovereign.store(this)
                    c.pack?.let { p -> store.importPack(p)?.let { error("settings: $it") } }
                    c.checklist?.let { store.checklistItems = Checklist.parse(it) }
                    SovereignFlags.invalidate()
                }
                VaultCache.load(this)
                merged.cards.size
            }
            result.fold(
                onSuccess = { done(getString(R.string.sv_restore_done, it)) },
                onFailure = { done(getString(R.string.sv_import_failed, it.message ?: "")) },
            )
        }
    }

    companion object {
        const val ACTION_PRESERVE = "com.sovereignops.sms.PRESERVE_THREAD"
        const val ACTION_BACKUP = "com.sovereignops.sms.BACKUP"
        const val ACTION_RESTORE = "com.sovereignops.sms.RESTORE"
        const val EXTRA_THREAD = "thread_id"
        private const val MAX_ATTACHMENT = 64 * 1024 * 1024

        fun start(context: Context, action: String, threadId: Long = 0L) =
            context.startActivity(Intent(context, SovereignPortabilityActivity::class.java).setAction(action).putExtra(EXTRA_THREAD, threadId))
    }
}
