package com.sovereignops.sms.core.guard

import com.sovereignops.sms.core.crm.Stage
import com.sovereignops.sms.core.rules.Category

/**
 * One pattern engine, used both ways:
 *  - **Outgoing** (Pre-Send Draft Guard): checks the compose box. Advisory: a match shows a
 *    banner and asks for a confirming second tap; it never blocks, edits or sends anything.
 *  - **Incoming** (red flags): checks a received message. A match shows a badge and can,
 *    if the user opted in per rule, tag, categorise or silence the alert. It never
 *    deletes, forwards or replies.
 * Both lists ship empty.
 */
enum class MatchKind { KEYWORD, REGEX }

data class Pattern(val text: String, val kind: MatchKind) {
    /** Every match as a character range in [draft]; throws on an invalid regex. */
    fun find(draft: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        return when (kind) {
            MatchKind.KEYWORD -> {
                val out = ArrayList<IntRange>()
                val k = text.trim()
                var i = draft.indexOf(k, ignoreCase = true)
                while (i >= 0) {
                    out += i until i + k.length
                    i = draft.indexOf(k, i + 1, ignoreCase = true)
                }
                out
            }
            MatchKind.REGEX -> RegexRunner.find(text, draft)
        }
    }
}

class RegexTimeout : RuntimeException("Pattern took too long")

/**
 * Bounds user-written regexes, which run on every incoming message. Two layers:
 *  - each match runs on a worker thread and the caller waits at most [BUDGET_MS]; a pattern that
 *    runs out is reported invalid (never as a match) and isn't run again in this process, so a
 *    bad pattern can stall at most one background thread, never message receipt;
 *  - on the JVM the input is read through [Budgeted], which also stops the worker itself.
 *    (Inference: Android's regex engine copies the input natively, so there only the first layer applies.)
 */
object RegexRunner {
    const val BUDGET_MS = 100L
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "sv-regex").apply { isDaemon = true } }
    private val timedOut: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun find(pattern: String, input: String): List<IntRange> {
        if (pattern in timedOut) throw RegexTimeout()
        val regex = Regex(pattern, RegexOption.IGNORE_CASE) // compile errors surface here, as before
        val deadline = System.nanoTime() + BUDGET_MS * 1_000_000
        val job = pool.submit(java.util.concurrent.Callable {
            regex.findAll(Budgeted(input, deadline)).filter { it.value.isNotEmpty() }.map { it.range }.toList()
        })
        return try {
            job.get(BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            job.cancel(true)
            timedOut += pattern
            throw RegexTimeout()
        } catch (e: java.util.concurrent.ExecutionException) {
            if (e.cause is RegexTimeout) timedOut += pattern
            throw e.cause ?: e
        }
    }
}

/**
 * Text that stops a regex which backtracks too long: java.util.regex reads the input through
 * charAt, so checking the clock there bounds any pattern, however it is written. Checked every
 * 1024 reads to keep ordinary matching fast.
 */
private class Budgeted(private val s: CharSequence, private val deadline: Long) : CharSequence {
    private var reads = 0
    override val length get() = s.length
    override fun get(index: Int): Char {
        if (++reads and 1023 == 0 && System.nanoTime() > deadline) throw RegexTimeout()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Budgeted(s.subSequence(startIndex, endIndex), deadline)
    override fun toString() = s.toString()
}

/** Derived from the rule's fields; shown in the UI and in exported files. */
enum class GuardType { FORBIDDEN_TERM, PAIR_MATCH, CONTEXT_GUARD }

enum class Severity { INFO, CAUTION, HIGH_RISK }

/**
 * - [pair]: B must appear in the text, and A either in the text too or, when [window] > 0,
 *   in one of the thread's last [window] messages.
 * - [stages]: the rule only applies when the thread's card stage is one of these.
 * - [severity], [silent], [tag], [category]: incoming-flag display and opt-in receipt actions.
 */
data class GuardRule(
    val name: String,
    val pattern: Pattern,
    val pair: Pattern? = null,
    val enabled: Boolean = true,
    val window: Int = 0,
    val stages: Set<Stage> = emptySet(),
    val severity: Severity = Severity.CAUTION,
    val silent: Boolean = false,
    val tag: String? = null,
    val category: Category? = null,
) {
    val type: GuardType
        get() = when {
            stages.isNotEmpty() -> GuardType.CONTEXT_GUARD
            pair != null -> GuardType.PAIR_MATCH
            else -> GuardType.FORBIDDEN_TERM
        }
}

/** What the guard knows about the thread. A thread without a card has no stage. */
data class GuardContext(val history: List<String> = emptyList(), val stage: Stage? = null)

data class GuardHit(
    val rule: GuardRule,
    val spans: List<IntRange>,
    val matched: List<String>,
    /** Text matched in earlier messages of the thread (pair rules with a window). */
    val earlier: List<String> = emptyList(),
)

data class GuardResult(val hits: List<GuardHit>, val invalid: List<GuardRule>) {
    val warn get() = hits.isNotEmpty()
    val highest: Severity? get() = hits.maxOfOrNull { it.rule.severity }
}

object DraftGuard {
    /** Texts longer than this are checked only in their first part, so typing stays fast. */
    const val MAX_CHECKED = 5000

    fun check(draft: String, rules: List<GuardRule>, context: GuardContext = GuardContext()): GuardResult {
        val text = clip(draft)
        val hits = ArrayList<GuardHit>()
        val invalid = ArrayList<GuardRule>()
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.stages.isNotEmpty() && context.stage !in rule.stages) continue
            val hit = runCatching { match(rule, text, context) }.getOrElse { invalid += rule; continue }
            if (hit != null) hits += hit
        }
        return GuardResult(hits, invalid)
    }

    private fun clip(s: String) = if (s.length > MAX_CHECKED) s.substring(0, MAX_CHECKED) else s

    private fun match(rule: GuardRule, text: String, context: GuardContext): GuardHit? {
        val a = rule.pattern.find(text)
        val pair = rule.pair
        if (pair == null) return if (a.isEmpty()) null else hit(rule, text, a)
        val b = pair.find(text)
        if (b.isEmpty()) return null
        if (a.isNotEmpty()) return hit(rule, text, a + b)
        if (rule.window <= 0) return null
        val earlier = context.history.takeLast(rule.window).flatMap { m -> clip(m).let { t -> rule.pattern.find(t).map { t.substring(it) } } }
        return if (earlier.isEmpty()) null else hit(rule, text, b, earlier.distinct())
    }

    private fun hit(rule: GuardRule, text: String, spans: List<IntRange>, earlier: List<String> = emptyList()): GuardHit {
        val sorted = spans.sortedBy { it.first }
        return GuardHit(rule, sorted, sorted.map { text.substring(it) }.distinct(), earlier)
    }

    /** Short banner text: rule names and what matched. */
    fun summary(result: GuardResult): String = result.hits.joinToString("\n") { h ->
        val now = h.matched.take(4).joinToString(", ") { "“${it.take(40)}”" }
        val before = if (h.earlier.isEmpty()) "" else " (earlier: " + h.earlier.take(2).joinToString(", ") { "“${it.take(40)}”" } + ")"
        "${badge(h.rule.severity)} ${h.rule.name}: $now$before"
    }

    fun badge(s: Severity) = when (s) {
        Severity.INFO -> "ⓘ"
        Severity.CAUTION -> "⚠"
        Severity.HIGH_RISK -> "⛔"
    }
}

/**
 * Text form, one rule per line, shared by the outgoing guard and incoming flags:
 *
 *     Name: keyword
 *     Name: /regex/
 *     Name: keyword && /regex/                 (pair: both in the same text)
 *     Name: a && b | window=5                  (pair: or A in the last 5 messages)
 *     Name: keyword | stages=new,verification_pending   (context guard)
 *     Name: /regex/ | high silent tag=links category=professional
 *     #off Name: keyword                       (kept, disabled)
 *     # comment
 *
 * Options after the last ` | `: `info`, `caution`, `high`, `silent`, `window=N`,
 * `stages=a,b`, `tag=x`, `category=personal|professional|unknown`. If any word after
 * ` | ` is not an option, the whole line is the pattern (so regexes may contain ` | `).
 */
object GuardText {
    private const val OFF = "#off "
    private const val AND = "&&"
    private const val OPTS = " | "

    private fun parsePattern(raw: String): Pattern? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return if (t.length >= 2 && t.startsWith("/") && t.endsWith("/")) Pattern(t.substring(1, t.length - 1), MatchKind.REGEX)
        else Pattern(t, MatchKind.KEYWORD)
    }

    private fun formatPattern(p: Pattern) = if (p.kind == MatchKind.REGEX) "/${p.text}/" else p.text

    /** Applies option words to [base]; null if any word is not an option. */
    private fun applyOptions(base: GuardRule, words: List<String>): GuardRule? {
        var r = base
        for (w in words) {
            val (k, v) = w.indexOf('=').let { if (it < 0) w.lowercase() to "" else w.substring(0, it).lowercase() to w.substring(it + 1) }
            r = when (k) {
                "info" -> r.copy(severity = Severity.INFO)
                "caution" -> r.copy(severity = Severity.CAUTION)
                "high" -> r.copy(severity = Severity.HIGH_RISK)
                "silent" -> r.copy(silent = true)
                "window" -> r.copy(window = v.toIntOrNull()?.coerceIn(0, 50) ?: return null)
                "stages" -> r.copy(stages = v.split(',').filter(String::isNotBlank).map { s -> Stage.entries.firstOrNull { it.name.equals(s.trim(), true) } ?: return null }.toSet())
                "tag" -> r.copy(tag = v.trim().ifEmpty { return null })
                "category" -> r.copy(category = Category.entries.firstOrNull { it.name.equals(v.trim(), true) } ?: return null)
                else -> return null
            }
        }
        return r
    }

    fun parse(text: String): List<GuardRule> = text.lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith(OFF)) }
        .mapNotNull { raw ->
            val enabled = !raw.startsWith(OFF)
            val line = if (enabled) raw else raw.removePrefix(OFF).trim()
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = line.substring(0, colon).trim()
            val rest = line.substring(colon + 1)
            val bar = rest.lastIndexOf(OPTS)
            val words = if (bar >= 0) rest.substring(bar + OPTS.length).trim().split(Regex("\\s+")).filter(String::isNotEmpty) else emptyList()
            val build = { body: String ->
                val and = body.indexOf(AND)
                parsePattern(if (and >= 0) body.substring(0, and) else body)?.let { a ->
                    GuardRule(name, a, if (and >= 0) parsePattern(body.substring(and + AND.length)) else null, enabled)
                }
            }
            if (bar >= 0 && words.isNotEmpty()) {
                build(rest.substring(0, bar))?.let { applyOptions(it, words) }?.let { return@mapNotNull it }
            }
            build(rest)
        }.toList()

    fun options(r: GuardRule): List<String> = buildList {
        when (r.severity) {
            Severity.INFO -> add("info")
            Severity.HIGH_RISK -> add("high")
            Severity.CAUTION -> Unit
        }
        if (r.silent) add("silent")
        if (r.window > 0) add("window=${r.window}")
        if (r.stages.isNotEmpty()) add("stages=" + r.stages.sortedBy { it.ordinal }.joinToString(",") { it.name.lowercase() })
        r.tag?.let { add("tag=${it.replace(' ', '_')}") }
        r.category?.let { add("category=${it.name.lowercase()}") }
    }

    fun format(rules: List<GuardRule>): String = rules.joinToString("\n") { r ->
        val opts = options(r)
        (if (r.enabled) "" else OFF) + r.name + ": " + formatPattern(r.pattern) +
            (r.pair?.let { " $AND " + formatPattern(it) } ?: "") +
            (if (opts.isEmpty()) "" else OPTS + opts.joinToString(" "))
    }
}
