package com.sovereignops.sms.core.pack

import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.guard.MatchKind
import com.sovereignops.sms.core.guard.Pattern
import com.sovereignops.sms.core.rules.KeywordEffect
import com.sovereignops.sms.core.rules.KeywordRule
import com.sovereignops.sms.core.templates.Template

/**
 * Optional starter content, all generic, all added **disabled** (or as plain templates)
 * for the user to review, edit and switch on. Nothing here identifies a person, place,
 * price or account; the owner's own patterns belong in a private imported pack.
 */
object Starters {

    val guard: List<GuardRule> = listOf(
        GuardRule("Street address", Pattern("\\b\\d{1,5}\\s+(\\w+\\s){1,3}(st|street|ave|avenue|rd|road|dr|drive|ln|lane|blvd|ct|court|way|pl|place|cir|circle)\\b", MatchKind.REGEX), enabled = false),
        GuardRule("Phone number", Pattern("\\b\\d{3}[-. )]{0,2}\\d{3}[-. ]?\\d{4}\\b", MatchKind.REGEX), enabled = false),
        GuardRule("Email address", Pattern("[\\w.+-]+@[\\w-]+\\.[\\w.]+", MatchKind.REGEX), enabled = false),
        GuardRule("Payment handle", Pattern("(cash\\s?app|venmo|zelle|paypal|\\$[a-z][a-z0-9_]{2,})", MatchKind.REGEX), enabled = false),
        GuardRule("Price next to a listed term", Pattern("(\\$\\s?\\d+|\\d+\\s?(dollars|usd|bucks))", MatchKind.REGEX), Pattern("REPLACE_WITH_YOUR_TERM", MatchKind.KEYWORD), enabled = false),
    )

    val keywords: List<KeywordRule> = listOf(
        KeywordRule("s1", "running late", "schedule", KeywordEffect.PRIORITY, enabled = false),
        KeywordRule("s2", "cancel", "schedule", KeywordEffect.PRIORITY, enabled = false),
        KeywordRule("s3", "reschedule", "schedule", KeywordEffect.NONE, enabled = false),
        KeywordRule("s4", "unsubscribe", "promo", KeywordEffect.SILENT, enabled = false),
    )

    val templates: List<Template> = listOf(
        Template("Info request", "Thanks for reaching out. To check availability, please send your name, the time you'd like, how long, and the general area."),
        Template("Not available", "I'm not available then. Feel free to suggest another time."),
        Template("Reconfirm", "Confirming we're still on for the time we agreed. Please reply to confirm."),
        Template("Decline", "Thanks for your interest. I'm not able to take this booking."),
        Template("Running late", "Running about 10 minutes late. Thanks for your patience."),
    )
}
