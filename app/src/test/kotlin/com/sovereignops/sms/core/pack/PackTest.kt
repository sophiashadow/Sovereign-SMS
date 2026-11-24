package com.sovereignops.sms.core.pack

import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.guard.MatchKind
import com.sovereignops.sms.core.guard.Pattern
import com.sovereignops.sms.core.rules.RuleConfig
import com.sovereignops.sms.core.rules.RuleConfigCodec
import com.sovereignops.sms.core.templates.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackTest {
    @Test
    fun `full pack round trips`() {
        val p = Pack(
            rules = RuleConfig(cooldownMinutes = 15),
            guard = listOf(GuardRule("Protected", Pattern("Ashford", MatchKind.KEYWORD))),
            templates = listOf(Template("Info", "Please send your name.")),
        )
        assertEquals(p, PackCodec.decode(PackCodec.encode(p)).getOrThrow())
    }

    @Test
    fun `partial pack leaves other sections null so import keeps them`() {
        val p = PackCodec.decode("""{"format":"sovereign-sms/pack","version":1,"templates":[{"title":"T","body":"B"}]}""").getOrThrow()
        assertNull(p.rules)
        assertNull(p.guard)
        assertEquals(listOf(Template("T", "B")), p.templates)
    }

    @Test
    fun `plain rules file imports as a pack`() {
        val p = PackCodec.decode(RuleConfigCodec.encode(RuleConfig(cooldownMinutes = 5))).getOrThrow()
        assertEquals(5, p.rules!!.cooldownMinutes)
    }

    @Test
    fun `bad files fail without throwing`() {
        listOf("", "[]", "{}", """{"format":"other"}""", """{"format":"sovereign-sms/pack","version":99}""", "{not json")
            .forEach { assertTrue(it, PackCodec.decode(it).isFailure) }
    }

    @Test
    fun `starters are neutral and ship disabled`() {
        assertTrue(Starters.guard.none { it.enabled })
        assertTrue(Starters.keywords.none { it.enabled })
        val digits = Regex("\\d{7,}")
        (Starters.templates.map { it.body } + Starters.guard.map { it.pattern.text }).forEach { assertTrue(it, !digits.containsMatchIn(it)) }
    }
}

class OwnerTemplateTest {
    @Test
    fun `shipped owner-rules template imports cleanly`() {
        val file = listOf("../docs/owner-rules.template.json", "docs/owner-rules.template.json").map { java.io.File(it) }.first { it.exists() }
        val p = PackCodec.decode(file.readText()).getOrThrow()
        assertEquals(4, p.guard!!.size)
        assertEquals(listOf(com.sovereignops.sms.core.guard.GuardType.FORBIDDEN_TERM, com.sovereignops.sms.core.guard.GuardType.PAIR_MATCH,
            com.sovereignops.sms.core.guard.GuardType.PAIR_MATCH, com.sovereignops.sms.core.guard.GuardType.CONTEXT_GUARD), p.guard!!.map { it.type })
        assertEquals(2, p.flags!!.size)
        assertTrue(p.flags!!.none { it.enabled })
        assertEquals(setOf(com.sovereignops.sms.core.rules.Category.PROFESSIONAL, com.sovereignops.sms.core.rules.Category.UNKNOWN), p.rules!!.quietHoursCategories)
        assertEquals(7, p.templates!!.size)
        assertEquals(com.sovereignops.sms.core.templates.TemplateCategory.entries.toSet(), p.templates!!.mapNotNull { it.category }.toSet())
        assertEquals(2, p.templates!!.count { it.favorite })
        assertEquals(listOf("After hours", "Weekend"), p.rules!!.schedules.map { it.name })
        assertTrue(p.rules!!.schedules.none { it.enabled })
    }

    @Test
    fun `template category and favorite round trip, unknown category rejects the file`() {
        val t = listOf(Template("C", "Confirmed {date}", com.sovereignops.sms.core.templates.TemplateCategory.BOOKING_CONFIRM, favorite = true))
        assertEquals(t, PackCodec.decode(PackCodec.encode(Pack(templates = t))).getOrThrow().templates)
        assertTrue(PackCodec.decode("""{"format":"sovereign-sms/pack","version":1,"templates":[{"title":"T","body":"B","category":"nope"}]}""").isFailure)
    }
}

class PresetAndFlagPackTest {
    @Test
    fun `flags and extended guard fields round trip through a pack`() {
        val flags = listOf(
            com.sovereignops.sms.core.guard.GuardRule(
                "Link", com.sovereignops.sms.core.guard.Pattern("https?://\\S+", com.sovereignops.sms.core.guard.MatchKind.REGEX),
                severity = com.sovereignops.sms.core.guard.Severity.INFO, silent = true, tag = "link",
                category = com.sovereignops.sms.core.rules.Category.UNKNOWN,
            ),
        )
        val guard = listOf(
            com.sovereignops.sms.core.guard.GuardRule(
                "Ctx", com.sovereignops.sms.core.guard.Pattern("Ashford", com.sovereignops.sms.core.guard.MatchKind.KEYWORD),
                stages = setOf(com.sovereignops.sms.core.crm.Stage.NEW), window = 3,
            ),
        )
        val p = PackCodec.decode(PackCodec.encode(Pack(guard = guard, flags = flags))).getOrThrow()
        assertEquals(flags, p.flags)
        assertEquals(guard, p.guard)
        assertTrue(PackCodec.encode(Pack(guard = guard)).contains("\"type\": \"CONTEXT_GUARD\""))
    }

    @Test
    fun `unknown severity or stage rejects the file`() {
        val bad = """{"format":"sovereign-sms/pack","version":1,"flags":[{"name":"x","pattern":{"text":"y"},"severity":"LOUD"}]}"""
        assertTrue(PackCodec.decode(bad).isFailure)
        val bad2 = """{"format":"sovereign-sms/pack","version":1,"guard":[{"name":"x","pattern":{"text":"y"},"stages":["NOPE"]}]}"""
        assertTrue(PackCodec.decode(bad2).isFailure)
    }

    @Test
    fun `presets ship disabled and neutral`() {
        assertEquals(3, Presets.all.size)
        val digits = Regex("\\d{7,}")
        Presets.all.forEach { preset ->
            val rules = preset.pack.guard.orEmpty() + preset.pack.flags.orEmpty()
            assertTrue(preset.title, rules.isNotEmpty())
            assertTrue(preset.title, rules.none { it.enabled })
            rules.forEach { assertTrue(it.name, !digits.containsMatchIn(it.pattern.text)) }
            // Every regex compiles.
            rules.filter { it.pattern.kind == com.sovereignops.sms.core.guard.MatchKind.REGEX }.forEach { Regex(it.pattern.text) }
            assertTrue(PackCodec.decode(PackCodec.encode(preset.pack)).isSuccess)
        }
        assertTrue(Presets.sharedSimKeywords.none { it.enabled })
    }

    @Test
    fun `scam preset flags synthetic samples when switched on`() {
        val on = Presets.scamSignals.pack.flags!!.map { it.copy(enabled = true) }
        val r = com.sovereignops.sms.core.guard.DraftGuard.check("Account locked, verify your account at bit.ly/xyz", on)
        assertEquals(setOf("Link", "Shortened link", "Account-verify bait"), r.hits.map { it.rule.name }.toSet())
    }
}

class LinkPresetTest {
    private val link = Presets.scamSignals.pack.flags!!.first { it.name == "Link" }.copy(enabled = true)
    private fun hits(s: String) = com.sovereignops.sms.core.guard.DraftGuard.check(s, listOf(link)).warn

    @Test
    fun `link rule catches schemes, www and bare domains but not plain prose`() {
        listOf("go to https://example.invalid/x", "www.example.com", "see example.com/path", "short.ly").forEach { assertTrue(it, hits(it)) }
        listOf("see you at 7. ok", "e.g. tomorrow", "fine.thanks").forEach { assertTrue(it, !hits(it)) }
    }
}
