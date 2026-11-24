package com.sovereignops.sms.core.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleTextTest {

    @Test
    fun `parses keyword lines with optional tag and effect`() {
        val rules = RuleText.parseKeywords(
            """
            invoice => billing
            urgent !priority
              promo => ads !quiet

            # a comment
            plain
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                KeywordRule("k1", "invoice", "billing", KeywordEffect.NONE),
                KeywordRule("k2", "urgent", null, KeywordEffect.PRIORITY),
                KeywordRule("k3", "promo", "ads", KeywordEffect.SILENT),
                KeywordRule("k4", "plain", null, KeywordEffect.NONE),
            ),
            rules,
        )
    }

    @Test
    fun `format and parse round trip`() {
        val rules = listOf(
            KeywordRule("k1", "invoice", "billing", KeywordEffect.NONE),
            KeywordRule("k2", "urgent", null, KeywordEffect.PRIORITY),
            KeywordRule("k3", "promo", "ads", KeywordEffect.SILENT),
        )
        assertEquals(rules, RuleText.parseKeywords(RuleText.formatKeywords(rules)))
    }

    @Test
    fun `disabled rules round trip as disabled, not lost and not re-enabled`() {
        val rules = listOf(KeywordRule("k1", "old", "x", KeywordEffect.SILENT, enabled = false))
        assertEquals(rules, RuleText.parseKeywords(RuleText.formatKeywords(rules)))
    }

    @Test
    fun `keyword containing arrow-like text keeps the first separator`() {
        val r = RuleText.parseKeywords("a => b => c").single()
        assertEquals("a", r.keyword)
        assertEquals("b => c", r.tag)
    }

    @Test
    fun `priority list ignores blanks and comments`() {
        assertEquals(setOf("+19195550142", "72345"), RuleText.parseAddresses(" +19195550142 \n\n# note\n72345\n"))
    }
}
