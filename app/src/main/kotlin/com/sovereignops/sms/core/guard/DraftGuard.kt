package com.sovereignops.sms.core.guard

/**
 * Pre-Send Draft Guard: user-defined checks run on the compose box. Advisory only:
 * a match shows a warning, it never blocks, edits or sends anything. Ships empty.
 */
enum class MatchKind { KEYWORD, REGEX }

data class Pattern(val text: String, val kind: MatchKind) {
    /** Every match as a character range in [draft]; throws on an invalid regex. */
    fun find(draft: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        return when (kind) {
            MatchKind.KEYWORD -> {
                val out = ArrayList<IntRange>()
                var i = draft.indexOf(text.trim(), ignoreCase = true)
                while (i >= 0) {
                    out += i until i + text.trim().length
                    i = draft.indexOf(text.trim(), i + 1, ignoreCase = true)
                }
                out
            }
            MatchKind.REGEX -> Regex(text, RegexOption.IGNORE_CASE).findAll(draft).filter { it.value.isNotEmpty() }.map { it.range }.toList()
        }
    }
}

/** [pair] set: warns only when both patterns appear in the same draft. */
data class GuardRule(
    val name: String,
    val pattern: Pattern,
    val pair: Pattern? = null,
    val enabled: Boolean = true,
)

data class GuardHit(val rule: GuardRule, val spans: List<IntRange>, val matched: List<String>)

data class GuardResult(val hits: List<GuardHit>, val invalid: List<GuardRule>) {
    val warn get() = hits.isNotEmpty()
}

object DraftGuard {
    /** Drafts longer than this are checked only in their first part, so typing stays fast. */
    const val MAX_CHECKED = 5000

    fun check(draft: String, rules: List<GuardRule>): GuardResult {
        val text = if (draft.length > MAX_CHECKED) draft.substring(0, MAX_CHECKED) else draft
        val hits = ArrayList<GuardHit>()
        val invalid = ArrayList<GuardRule>()
        for (rule in rules) {
            if (!rule.enabled) continue
            val spans = runCatching {
                val a = rule.pattern.find(text)
                val b = rule.pair?.find(text)
                when {
                    a.isEmpty() -> emptyList()
                    b == null -> a
                    b.isEmpty() -> emptyList()
                    else -> a + b
                }
            }.getOrElse { invalid += rule; continue }
            if (spans.isNotEmpty()) {
                val sorted = spans.sortedBy { it.first }
                hits += GuardHit(rule, sorted, sorted.map { text.substring(it) }.distinct())
            }
        }
        return GuardResult(hits, invalid)
    }

    /** Short banner text: rule names and what matched. */
    fun summary(result: GuardResult): String = result.hits.joinToString("\n") { h ->
        "⚠ ${h.rule.name}: " + h.matched.take(4).joinToString(", ") { "“${it.take(40)}”" }
    }
}

/**
 * Text form, one rule per line, for the settings screen:
 *
 *     Name: keyword
 *     Name: /regex/
 *     Name: keyword && /regex/      (pair: both must appear)
 *     #off Name: keyword            (kept, disabled)
 *     # comment
 */
object GuardText {
    private const val OFF = "#off "
    private const val AND = "&&"

    private fun parsePattern(raw: String): Pattern? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return if (t.length >= 2 && t.startsWith("/") && t.endsWith("/")) Pattern(t.substring(1, t.length - 1), MatchKind.REGEX)
        else Pattern(t, MatchKind.KEYWORD)
    }

    private fun formatPattern(p: Pattern) = if (p.kind == MatchKind.REGEX) "/${p.text}/" else p.text

    fun parse(text: String): List<GuardRule> = text.lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith(OFF)) }
        .mapNotNull { raw ->
            val enabled = !raw.startsWith(OFF)
            val line = if (enabled) raw else raw.removePrefix(OFF).trim()
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = line.substring(0, colon).trim()
            val body = line.substring(colon + 1)
            val and = body.indexOf(AND)
            val a = parsePattern(if (and >= 0) body.substring(0, and) else body) ?: return@mapNotNull null
            val b = if (and >= 0) parsePattern(body.substring(and + AND.length)) else null
            GuardRule(name, a, b, enabled)
        }.toList()

    fun format(rules: List<GuardRule>): String = rules.joinToString("\n") { r ->
        (if (r.enabled) "" else OFF) + r.name + ": " + formatPattern(r.pattern) + (r.pair?.let { " $AND " + formatPattern(it) } ?: "")
    }
}
