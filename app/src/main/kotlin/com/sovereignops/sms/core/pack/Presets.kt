package com.sovereignops.sms.core.pack

import com.sovereignops.sms.core.crm.Stage
import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.guard.MatchKind
import com.sovereignops.sms.core.guard.Pattern
import com.sovereignops.sms.core.guard.Severity
import com.sovereignops.sms.core.rules.KeywordEffect
import com.sovereignops.sms.core.rules.KeywordRule

/**
 * Named preset packs. Every rule is generic, written for this app, and added
 * **disabled**: the user reads each one, edits it and switches it on. Nothing names a
 * person, place, price, service or account. Owner-specific wording belongs in a private
 * pack imported from the device, never here.
 */
data class Preset(val id: String, val title: String, val description: String, val pack: Pack)

object Presets {
    private fun kw(t: String) = Pattern(t, MatchKind.KEYWORD)
    private fun re(t: String) = Pattern(t, MatchKind.REGEX)

    val soloOperator = Preset(
        id = "solo-operator",
        title = "Solo Operator — Boundary & Screening Starter",
        description = "Flags pressure, refusal to screen and haggling in incoming messages; warns before sending personal details or screening-only text to an unscreened thread.",
        pack = Pack(
            flags = listOf(
                GuardRule("Pressure / urgency", re("\\b(right now|asap|hurry|immediately|last minute)\\b"), enabled = false, severity = Severity.CAUTION),
                GuardRule("Refuses screening", re("\\b(no|won'?t|don'?t do|skip)\\s+(screening|verification|references|id)\\b"), enabled = false, severity = Severity.HIGH_RISK),
                GuardRule("Negotiating terms", re("\\b(discount|cheaper|lower (your )?rate|best price|deal)\\b"), enabled = false, severity = Severity.CAUTION),
                GuardRule("Asks for personal details", re("\\b(real name|where do you live|your address|home address)\\b"), enabled = false, severity = Severity.HIGH_RISK),
                GuardRule("Boundary push after decline", kw("why not"), kw("please"), enabled = false, window = 3, severity = Severity.CAUTION),
            ),
            guard = listOf(
                GuardRule("Personal detail in draft", re("\\b(my (real|last|full) name|my address|i live (on|at|in))\\b"), enabled = false),
                GuardRule("Screening-only text to unscreened thread", kw("REPLACE_WITH_SENSITIVE_TERM"), enabled = false,
                    stages = setOf(Stage.NEW, Stage.INFO_REQUESTED, Stage.VERIFICATION_PENDING)),
            ),
        ),
    )

    val scamSignals = Preset(
        id = "scam-signals",
        title = "Unsolicited Link & Scam Signals",
        description = "Flags links, shortened URLs, payment-pressure and code-sharing requests in incoming messages.",
        pack = Pack(
            flags = listOf(
                GuardRule("Link", re("https?://\\S+|\\bwww\\.\\S+|\\b[a-z0-9-]+\\.(com|net|org|ly|io|co|me|app|xyz|info|link|site|top)(/\\S*)?(?![\\w.])"), enabled = false, severity = Severity.INFO, tag = "link"),
                GuardRule("Shortened link", re("\\b(bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl|is\\.gd|ow\\.ly|rb\\.gy)/\\S+"), enabled = false, severity = Severity.CAUTION, tag = "link"),
                GuardRule("Gift card / crypto payment", re("\\b(gift ?cards?|itunes card|steam card|bitcoin|crypto|usdt)\\b"), enabled = false, severity = Severity.HIGH_RISK),
                GuardRule("Account-verify bait", re("\\b(verify your (account|identity)|account (is )?(locked|suspended)|confirm your (details|password))\\b"), enabled = false, severity = Severity.HIGH_RISK),
                GuardRule("Asks for a code you received", re("\\b(send|tell|give|forward)( me)? (the|your|that) (code|otp|pin)\\b"), enabled = false, severity = Severity.HIGH_RISK),
                GuardRule("Deposit before contact", re("\\b(deposit|pay) (first|upfront|in advance)\\b"), enabled = false, severity = Severity.CAUTION),
            ),
        ),
    )

    /** Flag wording; its schedule and keyword rules are [sharedSimSchedules] and [sharedSimKeywords]. */
    val sharedSimQuiet = Preset(
        id = "shared-sim-quiet",
        title = "Shared-SIM Quiet Hours Starter",
        description = "Keyword rules that keep schedule changes audible and promotional traffic quiet; pair with quiet hours scoped to Professional.",
        pack = Pack(
            rules = null,
            flags = listOf(
                GuardRule("Late-night first contact", re("\\b(tonight|late night|after midnight|right now)\\b"), enabled = false, severity = Severity.INFO,
                    stages = setOf(Stage.NEW), silent = true),
            ),
        ),
    )

    /** Schedule rules for the Shared-SIM preset; merged into the rules config, disabled. */
    val sharedSimSchedules = com.sovereignops.sms.core.rules.ScheduleText.parse(
        """
        #off Professional after hours: weekdays 21:00-09:00 professional,unknown held
        #off Professional weekends: weekends 00:00-00:00 professional,unknown silent
        #off Personal during sessions: weekdays 10:00-18:00 personal silent
        """.trimIndent(),
    )

    /** Keyword rules for the Shared-SIM preset; merged into the rules config, disabled. */
    val sharedSimKeywords = listOf(
        KeywordRule("p1", "running late", "schedule", KeywordEffect.PRIORITY, enabled = false),
        KeywordRule("p2", "cancel", "schedule", KeywordEffect.PRIORITY, enabled = false),
        KeywordRule("p3", "stop", "opt-out", KeywordEffect.SILENT, enabled = false),
        KeywordRule("p4", "promo", "promo", KeywordEffect.SILENT, enabled = false),
    )

    val all = listOf(soloOperator, scamSignals, sharedSimQuiet)
}
