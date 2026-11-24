package com.sovereignops.sms

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.rules.Decision
import com.sovereignops.sms.core.summary.DailySummary
import com.sovereignops.sms.core.summary.DaySummary
import com.sovereignops.sms.core.summary.Metric
import com.sovereignops.sms.core.summary.Period
import com.sovereignops.sms.core.summary.SummaryCounters
import com.sovereignops.sms.core.summary.SummaryTiming
import com.sovereignops.sms.ui.SovereignSettingsActivity
import com.sovereignops.sms.vault.Vault
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.R
import org.fossify.messages.extensions.messagesDB
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Daily summary delivery. An inexact alarm window (no exact-alarm permission) fires at the chosen
 * time; [SummaryReceiver] also reschedules after reboot, a clock or zone change and an app update,
 * and catches up a missed summary the same local day. The notification is silent, low importance,
 * private on the lock screen, and never carries a body, a name or an address.
 */
object SovereignSummary {
    const val ACTION_ALARM = "com.sovereignops.sms.action.DAILY_SUMMARY"
    const val EXTRA_SHOW = "sovereign_show_summary"
    private const val CHANNEL = "sovereign_summary"
    private const val REQUEST = 0x5050_0020
    private const val NOTIFICATION_ID = 0x5050_0021
    private const val WINDOW_MS = 15 * 60_000L

    private fun alarm(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, SummaryReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())

    /** Called from the rules' background block for each decided incoming message while summaries are on. */
    fun count(context: Context, decision: Decision, category: Category) {
        val day = LocalDate.now()
        val dao = Vault.dao(context)
        Vault.database(context).runInTransaction {
            for (m in SummaryCounters.metricsFor(decision, category)) {
                val key = SummaryCounters.key(day, m)
                dao.ensureCounter(key)
                dao.bumpCounter(key)
            }
        }
    }

    /** Turning summaries off stops counting and deletes the counters: nothing is kept that isn't used. */
    fun setEnabled(context: Context, on: Boolean) {
        val store = Sovereign.store(context)
        store.summaryEnabled = on
        if (on) {
            store.summarySince = System.currentTimeMillis()
            // Enabling after today's time doesn't send one straight away; the next slot is the first.
            if (SummaryTiming.due(now(), store.summarySchedule, store.summaryLastDay)) store.summaryLastDay = LocalDate.now()
        } else {
            store.summarySince = 0L
            org.fossify.commons.helpers.ensureBackgroundThread { runCatching { Vault.dao(context).clearSummaryCounters() } }
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
        sync(context)
    }

    /** Schedules the next delivery, or cancels it when summaries are off. Call after any settings change. */
    fun sync(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarm(context)
        am.cancel(pi)
        val store = Sovereign.store(context)
        if (!store.summaryEnabled) return
        val next = SummaryTiming.next(now(), store.summarySchedule) ?: return
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, DailySummary.epochMillis(next), WINDOW_MS, pi) }
    }

    /** Alarm or system event: deliver if today's summary is owed, prune old counters, schedule the next. Background thread. */
    fun onEvent(context: Context) {
        val store = Sovereign.store(context)
        if (store.summaryEnabled) {
            val now = now()
            if (SummaryTiming.due(now, store.summarySchedule, store.summaryLastDay)) {
                val slot = SummaryTiming.slot(now.toLocalDate(), store.summarySchedule, now.zone) ?: now
                runCatching { post(context, build(context, SummaryTiming.period(slot))) }
                store.summaryLastDay = now.toLocalDate()
            }
            runCatching { Vault.dao(context).pruneSummaryCounters(SummaryCounters.pruneFloor(now.toLocalDate())) }
        }
        sync(context)
    }

    /** Reads timestamps and counters for [period]; never message bodies or addresses. Background thread. */
    fun build(context: Context, period: Period): DaySummary {
        val since = ((period.fromMillis - DailySummary.LOOKBACK_DAYS * 86_400_000L) / 1000L).toInt()
        val stamps = runCatching { context.messagesDB.getStamps(since) }.getOrDefault(emptyList()).map { it.toStamp() }
        val counters = runCatching { Vault.dao(context).summaryCounters() }.getOrDefault(emptyList()).associate { it.name to it.value }
        val since0 = Sovereign.store(context).summarySince.takeIf { it > 0 }
        return DailySummary.build(period, stamps, counters, since0, ZoneId.systemDefault())
    }

    fun today(context: Context) = build(context, Period.todaySoFar(now()))
    fun yesterday(context: Context) = build(context, Period.yesterday(now()))

    private fun post(context: Context, summary: DaySummary) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.sv_summary_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
        val store = Sovereign.store(context)
        val text = when (val n = DailySummary.notice(summary, store.coverActive, context.baseConfig.isAppPasswordProtectionOn)) {
            DailySummary.Notice.Generic -> context.getString(R.string.sv_summary_ready)
            is DailySummary.Notice.Counts -> context.getString(R.string.sv_summary_counts, n.received, n.awaiting)
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, SovereignSettingsActivity::class.java).putExtra(EXTRA_SHOW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(context.getString(R.string.sv_summary_title))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(context.getString(R.string.sv_summary_title))
            .setContentText(text)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** The in-app detail. Shown behind upstream's app lock. */
    fun describe(context: Context, s: DaySummary): String = buildString {
        appendLine(context.getString(if (s.period.kind == Period.Kind.YESTERDAY) R.string.sv_summary_yesterday else R.string.sv_summary_today))
        if (s.isEmpty) {
            appendLine(context.getString(R.string.sv_summary_nothing))
        } else {
            appendLine(context.getString(R.string.sv_summary_volume, s.incoming, s.outgoing))
            appendLine(context.getString(R.string.sv_summary_conversations, s.activeConversations, s.newConversations))
            appendLine(context.getString(R.string.sv_summary_awaiting, s.awaitingResponse))
            val median = s.medianResponseMillis
            appendLine(
                if (median != null) context.getString(R.string.sv_summary_median, Durations.format(context, median), s.responses)
                else context.getString(R.string.sv_summary_no_median, s.responses, DailySummary.MIN_RESPONSES),
            )
        }
        val c = s.counts
        if (c == null) {
            appendLine(context.getString(R.string.sv_summary_not_counted))
        } else {
            appendLine(context.getString(R.string.sv_summary_categories, c.getValue(Metric.PERSONAL), c.getValue(Metric.PROFESSIONAL), c.getValue(Metric.UNKNOWN)))
            appendLine(context.getString(R.string.sv_summary_held, c.getValue(Metric.HELD_FOCUS), c.getValue(Metric.HELD_SCHEDULE), c.getValue(Metric.HELD_QUIET)))
            appendLine(context.getString(R.string.sv_summary_quarantined, c.getValue(Metric.QUARANTINED)))
            if (s.partialCounts) appendLine(context.getString(R.string.sv_summary_partial))
        }
    }
}

/** Formats a duration as minutes, hours and minutes, or days and hours. */
object Durations {
    fun format(context: Context, ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 60 -> context.getString(R.string.sv_dur_min, m)
            m < 48 * 60 -> context.getString(R.string.sv_dur_h_min, m / 60, m % 60)
            else -> context.getString(R.string.sv_dur_d_h, m / (24 * 60), m / 60 % 24)
        }
    }
}

/**
 * Not exported: only the alarm (our own PendingIntent) and system broadcasts reach it. On every event
 * it also re-arms the focus-ending notice, which otherwise wouldn't survive a reboot or a zone change.
 */
class SummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SovereignSummary.ACTION_ALARM, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (intent.action != SovereignSummary.ACTION_ALARM) runCatching { SovereignFocus.sync(app) }
                runCatching { SovereignSummary.onEvent(app) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
