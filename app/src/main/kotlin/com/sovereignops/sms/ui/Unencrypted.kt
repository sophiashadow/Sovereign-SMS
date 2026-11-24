package com.sovereignops.sms.ui

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fossify.messages.R

/**
 * Shown before every export that leaves the app unencrypted (thread ZIP, settings pack, ledger
 * CSV/Markdown, upstream message export). It warns; it never blocks: Continue always proceeds to the system file dialog.
 */
object Unencrypted {
    fun confirm(activity: Activity, message: Int, onCancel: () -> Unit = {}, onContinue: () -> Unit) {
        var proceeded = false
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sv_unencrypted_title)
            .setMessage(message)
            .setPositiveButton(R.string.sv_continue) { _, _ -> proceeded = true; onContinue() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!proceeded) onCancel() }
            .show()
    }
}
