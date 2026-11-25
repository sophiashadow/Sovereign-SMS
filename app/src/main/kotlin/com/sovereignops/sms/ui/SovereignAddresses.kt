package com.sovereignops.sms.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.Telephony.MmsSms
import android.provider.Telephony.Threads
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.EditText
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sovereignops.sms.Sovereign
import com.sovereignops.sms.core.address.AuditThread
import com.sovereignops.sms.core.address.ThreadAudit
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R

/**
 * Android side of the address checks: reads Android's message store, read-only, and
 * hands plain lists to [ThreadAudit]. Nothing here writes, merges or moves a thread,
 * and the report holds no address.
 */
object SovereignAddresses {
    private val simpleThreads: Uri get() = "${Threads.CONTENT_URI}?simple=true".toUri()

    /** More than one recipient on the thread. False when it can't be read. */
    fun isGroupThread(context: Context, threadId: Long): Boolean = runCatching {
        context.contentResolver.query(simpleThreads, arrayOf(Threads.RECIPIENT_IDS), "${Threads._ID} = ?", arrayOf(threadId.toString()), null)?.use { c ->
            c.moveToFirst() && recipientIds(c.getString(0)).size > 1
        } ?: false
    }.getOrDefault(false)

    private fun recipientIds(raw: String?): List<Long> =
        raw.orEmpty().split(" ").mapNotNull { it.toLongOrNull() }

    /** Every thread with messages, as its recipients' raw addresses. */
    fun readThreads(context: Context): List<AuditThread> {
        val canonical = HashMap<Long, String>()
        context.contentResolver.query(Uri.withAppendedPath(MmsSms.CONTENT_URI, "canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) canonical[c.getLong(0)] = c.getString(1).orEmpty()
        }
        val threads = ArrayList<AuditThread>()
        context.contentResolver.query(simpleThreads, arrayOf(Threads._ID, Threads.RECIPIENT_IDS), "${Threads.MESSAGE_COUNT} > 0", null, null)?.use { c ->
            while (c.moveToNext()) {
                // A recipient id with no canonical row reads as "" (upstream does the same).
                threads += AuditThread(c.getLong(0), recipientIds(c.getString(1)).map { canonical[it].orEmpty() })
            }
        }
        return threads
    }

    /** This phone's own numbers as far as Android knows them; often none. Used only for comparison. */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun ownNumbers(context: Context): List<String> {
        val out = ArrayList<String>()
        runCatching {
            val subs = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            val tm = context.getSystemService(TelephonyManager::class.java)
            for (s in subs) {
                @Suppress("DEPRECATION")
                s.number?.let(out::add)
                runCatching { tm?.createForSubscriptionId(s.subscriptionId)?.line1Number }.getOrNull()?.let(out::add)
            }
        }
        out.addAll(Sovereign.store(context).ownNumbers)
        return out.filter { it.isNotBlank() }.distinct()
    }

    fun editOwnNumbers(activity: FragmentActivity) {
        val store = Sovereign.store(activity)
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.sv_own_numbers_hint)
            setText(store.ownNumbers.joinToString(", "))
            setPadding(48, 32, 48, 24)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sv_own_numbers)
            .setMessage(R.string.sv_own_numbers_summary)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                store.ownNumbers = input.text?.toString().orEmpty().split(",")
                activity.toast(R.string.sv_own_numbers_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showCheck(activity: FragmentActivity) = ensureBackgroundThread {
        val text = runCatching {
            ThreadAudit.render(ThreadAudit.audit(readThreads(activity), ownNumbers(activity)))
        }.getOrElse { activity.getString(R.string.sv_check_failed) }
        activity.runOnUiThread {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sv_check_conversations)
                .setMessage(text + "\n\n" + activity.getString(R.string.sv_check_note))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.sv_copy_report) { _, _ -> activity.copyToClipboard(text) }
                .show()
        }
    }
}
