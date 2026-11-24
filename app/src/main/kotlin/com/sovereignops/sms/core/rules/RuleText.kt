package com.sovereignops.sms.core.rules

/**
 * One-rule-per-line text form used by the settings screen, so rules are edited as
 * plain text without a rebuild:
 *
 *     keyword                    tag nothing, just match (shown in previews)
 *     keyword => tag             add a tag
 *     keyword !priority          alert even in quiet hours / focus
 *     keyword => tag !quiet      tag and post silently
 *     #off keyword => tag        kept but disabled
 *     # comment                  ignored
 */
object RuleText {
    private const val SEP = "=>"
    private const val PRIORITY = "!priority"
    private const val QUIET = "!quiet"
    private const val OFF = "#off "

    fun parseKeywords(text: String): List<KeywordRule> {
        val out = ArrayList<KeywordRule>()
        text.lineSequence().map(String::trim).filter { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith(OFF)) }.forEach { raw ->
            val enabled = !raw.startsWith(OFF)
            var line = if (enabled) raw else raw.removePrefix(OFF).trim()
            var effect = KeywordEffect.NONE
            when {
                line.endsWith(PRIORITY, ignoreCase = true) -> { effect = KeywordEffect.PRIORITY; line = line.dropLast(PRIORITY.length).trim() }
                line.endsWith(QUIET, ignoreCase = true) -> { effect = KeywordEffect.SILENT; line = line.dropLast(QUIET.length).trim() }
            }
            val sep = line.indexOf(SEP)
            val keyword = (if (sep >= 0) line.substring(0, sep) else line).trim()
            val tag = if (sep >= 0) line.substring(sep + SEP.length).trim().ifEmpty { null } else null
            if (keyword.isNotEmpty()) out += KeywordRule("k${out.size + 1}", keyword, tag, effect, enabled)
        }
        return out
    }

    fun formatKeywords(rules: List<KeywordRule>): String = rules.joinToString("\n") { r ->
        buildString {
            if (!r.enabled) append(OFF)
            append(r.keyword.trim())
            r.tag?.takeIf(String::isNotBlank)?.let { append(" $SEP ").append(it.trim()) }
            when (r.effect) {
                KeywordEffect.PRIORITY -> append(" $PRIORITY")
                KeywordEffect.SILENT -> append(" $QUIET")
                KeywordEffect.NONE -> Unit
            }
        }
    }

    fun parseAddresses(text: String): Set<String> =
        text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    fun formatAddresses(addresses: Set<String>): String = addresses.sorted().joinToString("\n")
}
