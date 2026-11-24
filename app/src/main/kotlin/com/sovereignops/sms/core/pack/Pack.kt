package com.sovereignops.sms.core.pack

import com.sovereignops.sms.core.guard.GuardRule
import com.sovereignops.sms.core.guard.MatchKind
import com.sovereignops.sms.core.guard.Pattern
import com.sovereignops.sms.core.rules.RuleConfig
import com.sovereignops.sms.core.rules.RuleConfigCodec
import com.sovereignops.sms.core.templates.Template
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
)

@Serializable
internal data class PatternDoc(val text: String, val regex: Boolean = false)

@Serializable
internal data class GuardDoc(val name: String, val pattern: PatternDoc, val pair: PatternDoc? = null, val enabled: Boolean = true)

@Serializable
internal data class TemplateDoc(val title: String, val body: String)

@Serializable
internal data class PackDoc(
    val format: String = PackCodec.FORMAT,
    val version: Int = PackCodec.VERSION,
    val rules: JsonElement? = null,
    val guard: List<GuardDoc>? = null,
    val templates: List<TemplateDoc>? = null,
)

object PackCodec {
    const val FORMAT = "sovereign-sms/pack"
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; explicitNulls = false }

    private fun PatternDoc.toPattern() = Pattern(text, if (regex) MatchKind.REGEX else MatchKind.KEYWORD)
    private fun Pattern.toDoc() = PatternDoc(text, kind == MatchKind.REGEX)

    fun encode(pack: Pack): String = json.encodeToString(
        PackDoc.serializer(),
        PackDoc(
            rules = pack.rules?.let { json.parseToJsonElement(RuleConfigCodec.encode(it)) },
            guard = pack.guard?.map { GuardDoc(it.name, it.pattern.toDoc(), it.pair?.toDoc(), it.enabled) },
            templates = pack.templates?.map { TemplateDoc(it.title, it.body) },
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
                    guard = doc.guard?.map { GuardRule(it.name.trim(), it.pattern.toPattern(), it.pair?.toPattern(), it.enabled) }
                        ?.filter { it.name.isNotEmpty() && it.pattern.text.isNotBlank() },
                    templates = doc.templates?.map { Template(it.title.trim(), it.body) }?.filter { it.title.isNotEmpty() && it.body.isNotBlank() },
                )
            }
            else -> error("Not a Sovereign SMS rules or pack file")
        }
    }
}
