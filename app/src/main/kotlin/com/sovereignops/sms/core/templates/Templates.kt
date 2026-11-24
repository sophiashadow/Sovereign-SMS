package com.sovereignops.sms.core.templates

/** Chip groups on the composer's quick-reply bar. */
enum class TemplateCategory(val label: String) {
    INITIAL_INFO("Initial Info"),
    SCREENING_GATE("Screening Gate"),
    VERIFICATION_REQUEST("Verification Request"),
    DEFLECTION("Deflection / Decline"),
    BOOKING_CONFIRM("Booking Confirm"),
    RECONFIRM("Reconfirm"),
    FOLLOW_UP("Follow-Up"),
    ;

    val key get() = name.lowercase()

    companion object {
        fun parse(s: String): TemplateCategory? = entries.firstOrNull { it.key == s.trim().lowercase() || it.name == s.trim() }
    }
}

/** A reusable reply. Inserting one only edits the compose box; sending stays a human tap. */
data class Template(
    val title: String,
    val body: String,
    val category: TemplateCategory? = null,
    val favorite: Boolean = false,
)

object Templates {

    /** Replace the selection (or insert at the cursor) with [body]; returns the new text and cursor. */
    fun insert(text: String, selStart: Int, selEnd: Int, body: String): Pair<String, Int> {
        val start = minOf(selStart, selEnd).coerceIn(0, text.length)
        val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val needsSpace = start > 0 && !text[start - 1].isWhitespace() && body.isNotEmpty() && !body.first().isWhitespace()
        val insert = (if (needsSpace) " " else "") + body
        return (text.substring(0, start) + insert + text.substring(end)) to (start + insert.length)
    }

    private const val OPTS = " | "

    /**
     * Text form for editing: templates separated by a line holding only `---`.
     * The first line of each block is the title, optionally followed by ` | ` and
     * `category=<name>` and/or `favorite`; the rest is the message. If a word after ` | `
     * is not an option, the whole line is the title.
     */
    fun parse(text: String): List<Template> =
        text.split(Regex("(?m)^---\\s*$")).mapNotNull { block ->
            val lines = block.trim('\n', '\r').lines()
            val head = lines.firstOrNull()?.trim().orEmpty()
            val body = lines.drop(1).joinToString("\n").trim()
            val t = titleLine(head)
            if (t.title.isEmpty() || body.isEmpty()) null else t.copy(body = body)
        }

    private fun titleLine(head: String): Template {
        val bar = head.lastIndexOf(OPTS)
        if (bar > 0) {
            var category: TemplateCategory? = null
            var favorite = false
            val ok = head.substring(bar + OPTS.length).trim().split(Regex("\\s+")).filter(String::isNotEmpty).let { words ->
                words.isNotEmpty() && words.all { w ->
                    when {
                        w.equals("favorite", true) || w.equals("fav", true) -> { favorite = true; true }
                        w.startsWith("category=", true) -> TemplateCategory.parse(w.substringAfter('='))?.also { category = it } != null
                        else -> false
                    }
                }
            }
            if (ok) return Template(head.substring(0, bar).trim(), "", category, favorite)
        }
        return Template(head, "")
    }

    fun format(templates: List<Template>): String = templates.joinToString("\n---\n") { t ->
        val opts = listOfNotNull(t.category?.let { "category=${it.key}" }, if (t.favorite) "favorite" else null)
        t.title + (if (opts.isEmpty()) "" else OPTS + opts.joinToString(" ")) + "\n" + t.body
    }

    /** Favorites first (in list order), then the categories that have at least one template. */
    fun bar(templates: List<Template>): Pair<List<Template>, List<TemplateCategory>> =
        templates.filter { it.favorite } to TemplateCategory.entries.filter { c -> templates.any { it.category == c } }
}

/**
 * `{alias}`, `{time_window}` and `{date}` in a template. Known values (from the client card)
 * are filled in; the rest are returned so the UI can ask. Other braces are left untouched.
 */
object Placeholders {
    val KNOWN = listOf("alias", "time_window", "date")
    private val TOKEN = Regex("\\{(alias|time_window|date)\\}")

    fun names(body: String): List<String> = TOKEN.findAll(body).map { it.groupValues[1] }.distinct().toList()

    /** Fills every placeholder found in [values] (blank values count as missing). */
    fun fill(body: String, values: Map<String, String?>): String =
        TOKEN.replace(body) { m -> values[m.groupValues[1]]?.takeIf { it.isNotBlank() } ?: m.value }

    fun missing(body: String, values: Map<String, String?>): List<String> =
        names(body).filter { values[it].isNullOrBlank() }
}
