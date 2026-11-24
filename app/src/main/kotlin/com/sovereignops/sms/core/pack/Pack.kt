package com.sovereignops.sms.core.pack

import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.guard.MatchKind
import com.sovereignops.sms.core.guard.Pattern
import com.sovereignops.sms.core.guard.Severity
import com.sovereignops.sms.core.crm.Stage
import com.sovereignops.sms.core.rules.Category
import com.sovereignops.sms.core.rules.RuleConfig
import com.sovereignops.sms.core.rules.RuleConfigCodec
import com.sovereignops.sms.core.templates.Template
import com.sovereignops.sms.core.templates.TemplateCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One portable file for everything a user configures: notification rules, draft-guard
 * rules and templates. This is the shape of a private `owner-rules.json`: kept on the
 * device, imported through the file picker, never in the repository. Every section is
 * optional; an import replaces only the sections the file contains.
 */
data class Pack(
    val rules: RuleConfig? = null,
    val guard: List<GuardRule>? = null,
    val templates: List<Template>? = null,
    /** Incoming red-flag rules; same rule shape as [guard]. */
    val flags: List<GuardRule>? = null,
)

@Serializable
internal data class PatternDoc(val text: String, val regex: Boolean = false)

@Serializable
internal data class GuardDoc(
    val name: String,
    val pattern: PatternDoc,
    val pair: PatternDoc? = null,
    val enabled: Boolean = true,
    /** Informational on export; derived from the fields below on import. */
    val type: String? = null,
    val window: Int = 0,
    val stages: List<String> = emptyList(),
    val severity: String = "CAUTION",
    val silent: Boolean = false,
    val tag: String? = null,
    val category: String? = null,
)

@Serializable
internal data class TemplateDoc(
    val title: String,
    val body: String,
    /** One of TemplateCategory keys, e.g. `booking_confirm`. Optional. */
    val category: String? = null,
    val favorite: Boolean = false,
)

@Serializable
internal data class PackDoc(
    val format: String = PackCodec.FORMAT,
    val version: Int = PackCodec.VERSION,
    val rules: JsonElement? = null,
    val guard: List<GuardDoc>? = null,
    val templates: List<TemplateDoc>? = null,
    val flags: List<GuardDoc>? = null,
)

object PackCodec {
    const val FORMAT = "sovereign-sms/pack"
    const val VERSION = 1

    enum class Section { RULES, GUARD, TEMPLATES, FLAGS }

    /** The sections an import of [p] replaces, in file order; every other section is left as it is. */
    fun sections(p: Pack): List<Section> = listOfNotNull(
        Section.RULES.takeIf { p.rules != null },
        Section.GUARD.takeIf { p.guard != null },
        Section.TEMPLATES.takeIf { p.templates != null },
        Section.FLAGS.takeIf { p.flags != null },
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; explicitNulls = false }

    private fun PatternDoc.toPattern() = Pattern(text, if (regex) MatchKind.REGEX else MatchKind.KEYWORD)
    private fun Pattern.toDoc() = PatternDoc(text, kind == MatchKind.REGEX)

    private fun GuardRule.toDoc() = GuardDoc(
        name, pattern.toDoc(), pair?.toDoc(), enabled, type.name, window,
        stages.sortedBy { it.ordinal }.map { it.name }, severity.name, silent, tag, category?.name,
    )

    /** Unknown stage, severity or category names are rejected, so a typo can't silently widen a rule. */
    private fun rules(docs: List<GuardDoc>): List<GuardRule> = docs.map { d ->
        GuardRule(
            name = d.name.trim(),
            pattern = d.pattern.toPattern(),
            pair = d.pair?.toPattern(),
            enabled = d.enabled,
            window = d.window.coerceIn(0, 50),
            stages = d.stages.map { s -> Stage.entries.firstOrNull { it.name.equals(s, true) } ?: error("Unknown stage “$s” in rule “${d.name}”") }.toSet(),
            severity = Severity.entries.firstOrNull { it.name.equals(d.severity, true) } ?: error("Unknown severity “${d.severity}” in rule “${d.name}”"),
            silent = d.silent,
            tag = d.tag?.trim()?.ifEmpty { null },
            category = d.category?.let { c -> Category.entries.firstOrNull { it.name.equals(c, true) } ?: error("Unknown category “$c” in rule “${d.name}”") },
        )
    }.filter { it.name.isNotEmpty() && it.pattern.text.isNotBlank() }

    fun encode(pack: Pack): String = json.encodeToString(
        PackDoc.serializer(),
        PackDoc(
            rules = pack.rules?.let { json.parseToJsonElement(RuleConfigCodec.encode(it)) },
            guard = pack.guard?.map { it.toDoc() },
            templates = pack.templates?.map { TemplateDoc(it.title, it.body, it.category?.key, it.favorite) },
            flags = pack.flags?.map { it.toDoc() },
        ),
    )

    /** Accepts a pack or a plain rules file. Never throws; a bad file changes nothing. */
    fun decode(text: String): Result<Pack> = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("Not a JSON object")
        when (root["format"]?.jsonPrimitive?.content) {
            RuleConfigCodec.FORMAT -> Pack(rules = RuleConfigCodec.decode(text).getOrThrow())
            FORMAT -> {
                val doc = json.decodeFromJsonElement(PackDoc.serializer(), root)
                require(doc.version in 1..VERSION) { "Pack version ${doc.version} is newer than this app supports" }
                Pack(
                    rules = doc.rules?.let { RuleConfigCodec.decode(it.toString()).getOrThrow() },
                    guard = doc.guard?.let(::rules),
                    flags = doc.flags?.let(::rules),
                    templates = doc.templates?.map { d ->
                        Template(
                            d.title.trim(), d.body,
                            d.category?.let { c -> TemplateCategory.parse(c) ?: error("Unknown template category “$c” in “${d.title}”") },
                            d.favorite,
                        )
                    }?.filter { it.title.isNotEmpty() && it.body.isNotBlank() },
                )
            }
            else -> error("Not a Sovereign SMS rules or pack file")
        }
    }
}
