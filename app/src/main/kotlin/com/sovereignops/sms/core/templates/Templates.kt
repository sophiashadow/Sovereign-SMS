package com.sovereignops.sms.core.templates

/** A reusable reply. Inserting one only edits the compose box; sending stays a human tap. */
data class Template(val title: String, val body: String)

object Templates {

    /** Replace the selection (or insert at the cursor) with [body]; returns the new text and cursor. */
    fun insert(text: String, selStart: Int, selEnd: Int, body: String): Pair<String, Int> {
        val start = minOf(selStart, selEnd).coerceIn(0, text.length)
        val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val needsSpace = start > 0 && !text[start - 1].isWhitespace() && body.isNotEmpty() && !body.first().isWhitespace()
        val insert = (if (needsSpace) " " else "") + body
        return (text.substring(0, start) + insert + text.substring(end)) to (start + insert.length)
    }

    /**
     * Text form for editing: templates separated by a line holding only `---`.
     * The first line of each block is the title; the rest is the message.
     */
    fun parse(text: String): List<Template> =
        text.split(Regex("(?m)^---\\s*$")).mapNotNull { block ->
            val lines = block.trim('\n', '\r').lines()
            val title = lines.firstOrNull()?.trim().orEmpty()
            val body = lines.drop(1).joinToString("\n").trim()
            if (title.isEmpty() || body.isEmpty()) null else Template(title, body)
        }

    fun format(templates: List<Template>): String = templates.joinToString("\n---\n") { "${it.title}\n${it.body}" }
}
