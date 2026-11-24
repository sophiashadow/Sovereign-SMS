package com.sovereignops.sms

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sovereignops.sms.core.rules.FocusTimer
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The discreet "focus ends soon" notice. An inexact alarm window (no exact-alarm
 * permission) fires about five minutes before focus ends and posts one silent,
 * low-importance notification with no message content. Turning focus off cancels it.
 */
object SovereignFocus {
    private const val REQUEST = 0x5050_0010
    private const val NOTIFICATION_ID = 0x5050_0011
    private const val WINDOW_MS = 60_000L

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, FocusEndingReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Call after every focus change: schedules the warning, or cancels it when focus is off or too short. */
    fun sync(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context)
        am.cancel(pi)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        val at = FocusTimer.warnAt(LocalDateTime.now(), Sovereign.store(context).ruleConfig.focusUntil) ?: return
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, ms, WINDOW_MS, pi) }
    }

    fun warn(context: Context) {
        val until = Sovereign.store(context).ruleConfig.focusUntil ?: return
        if (!until.isAfter(LocalDateTime.now())) return
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, SovereignNotifications.quietChannel(context))
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(context.getString(R.string.sv_focus_ending_title))
            .setContentText(context.getString(R.string.sv_focus_ending_text, until.format(DateTimeFormatter.ofPattern("HH:mm"))))
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter(FocusTimer.WARNING.toMillis() + WINDOW_MS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n) }
    }
}

class FocusEndingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SovereignFocus.warn(context)
}
