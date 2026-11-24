package com.sovereignops.sms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import org.fossify.messages.R

/**
 * "Copy code" notification action. Puts the one-time code on the clipboard, marked
 * sensitive so Android 13+ hides it in the clipboard preview. Not exported.
 */
class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE)?.takeIf { it.all(Char::isDigit) && it.length in 4..8 } ?: return
        val clip = ClipData.newPlainText(context.getString(R.string.sv_copy_code), code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.sv_code_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
