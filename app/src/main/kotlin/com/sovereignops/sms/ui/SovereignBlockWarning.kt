package com.sovereignops.sms.ui

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sovereignops.sms.vault.VaultCache
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R

/**
 * Shown before Android's own blocking. OS blocking (BlockedNumberContract) makes the system
 * drop messages before this app sees them; the app's Quarantine keeps them, stored and muted.
 */
object SovereignBlockWarning {

    /** [osBlock] runs only when the user explicitly picks Android blocking. */
    fun confirm(activity: Activity, addresses: List<String>, osBlock: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sv_block_warning_title)
            .setMessage(R.string.sv_block_warning)
            .setPositiveButton(R.string.sv_block_quarantine) { _, _ ->
                ensureBackgroundThread {
                    addresses.forEach { VaultCache.setQuarantined(activity, it, true) }
                    activity.runOnUiThread { activity.toast(R.string.sv_quarantined) }
                }
            }
            .setNeutralButton(R.string.sv_block_os_anyway) { _, _ -> osBlock() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Warning before opening the system blocked-numbers manager. */
    fun beforeManager(activity: Activity, open: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sv_block_warning_title)
            .setMessage(R.string.sv_block_manager_warning)
            .setPositiveButton(R.string.sv_continue) { _, _ -> open() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
