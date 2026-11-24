package com.sovereignops.sms.core.preserve

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class ArchiveAttachment(val path: String, val mimeType: String, val originalName: String, val missing: Boolean = false)

@Serializable
data class ArchiveMessage(
    val id: Long,
    val atMillis: Long,
    val direction: String, // "in" or "out"
    val sender: String,
    val body: String,
    val attachments: List<ArchiveAttachment> = emptyList(),
)

@Serializable
data class ArchiveDoc(
    val format: String = FORMAT,
    val version: Int = 1,
    val title: String,
    val participants: List<String>,
    val exportedAtMillis: Long,
    val messages: List<ArchiveMessage>,
) {
    companion object {
        const val FORMAT = "sovereign-sms/thread-archive"
    }
}

/**
 * A preservation copy of one conversation, made only when the owner asks: `thread.md`
 * (readable), `messages.json` (exact bodies, nothing trimmed), the attachments, and
 * `manifest.sha256` in `sha256sum -c` format covering every other entry. Nothing here
 * deletes, edits or sends anything; the original messages stay where they are.
 */
object ThreadArchive {
    const val MANIFEST = "manifest.sha256"
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")

    fun safeName(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").trim('.', '_')
        return cleaned.take(80).ifEmpty { "file" }
    }

    fun attachmentPath(messageId: Long, index: Int, originalName: String) = "attachments/$messageId-$index-${safeName(originalName)}"

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Lines of `<hex>  <path>`, sorted by path, LF endings: checkable with `sha256sum -c manifest.sha256`. */
    fun manifest(entries: Map<String, ByteArray>): String =
        entries.filterKeys { it != MANIFEST }.toSortedMap().entries.joinToString("") { (path, bytes) -> "${sha256(bytes)}  $path\n" }

    /** Paths whose content no longer matches the manifest, plus manifest lines with no entry and entries with no line. */
    fun verify(entries: Map<String, ByteArray>): List<String> {
        val listed = entries[MANIFEST]?.toString(Charsets.UTF_8)?.lines()?.filter(String::isNotBlank)?.associate {
            it.substringAfter("  ") to it.substringBefore("  ")
        } ?: return listOf(MANIFEST)
        val others = entries.filterKeys { it != MANIFEST }
        return (listed.keys + others.keys).toSortedSet().filter { p -> others[p]?.let(::sha256) != listed[p] }
    }

    private fun mdText(s: String) = s.replace("\\", "\\\\").replace("<", "&lt;").lines().joinToString("\n") { "> " + it.ifEmpty { "" } }

    fun markdown(doc: ArchiveDoc, zone: ZoneId): String = buildString {
        append("# ").append(doc.title.replace("\n", " ")).append("\n\n")
        append("- Participants: ").append(doc.participants.joinToString(", ")).append('\n')
        append("- Messages: ").append(doc.messages.size).append('\n')
        append("- Exported: ").append(Instant.ofEpochMilli(doc.exportedAtMillis).atZone(zone).format(stamp)).append('\n')
        append("- Exact text is in `messages.json`; `manifest.sha256` checks every file.\n")
        doc.messages.sortedWith(compareBy({ it.atMillis }, { it.id })).forEach { m ->
            append("\n### ").append(Instant.ofEpochMilli(m.atMillis).atZone(zone).format(stamp))
                .append(" · ").append(if (m.direction == "out") "Sent" else "Received from " + m.sender).append("\n\n")
            if (m.body.isNotEmpty()) append(mdText(m.body)).append('\n')
            m.attachments.forEach { a ->
                append(if (m.body.isNotEmpty()) "\n" else "")
                append(if (a.missing) "- Attachment not readable: ${a.originalName} (${a.mimeType})\n" else "- Attachment: [${a.originalName}](${a.path}) (${a.mimeType})\n")
            }
        }
    }

    /** Every entry of the archive, manifest last. [files] holds attachment bytes by archive path; absent ones are marked missing. */
    fun entries(doc: ArchiveDoc, files: Map<String, ByteArray>, zone: ZoneId): LinkedHashMap<String, ByteArray> {
        val marked = doc.copy(
            messages = doc.messages.map { m -> m.copy(attachments = m.attachments.map { it.copy(missing = it.path !in files) }) },
        )
        val out = LinkedHashMap<String, ByteArray>()
        out["thread.md"] = markdown(marked, zone).toByteArray()
        out["messages.json"] = json.encodeToString(ArchiveDoc.serializer(), marked).toByteArray()
        marked.messages.flatMap { it.attachments }.filter { !it.missing }.forEach { out[it.path] = files.getValue(it.path) }
        out[MANIFEST] = manifest(out).toByteArray()
        return out
    }

    fun decode(messagesJson: String): ArchiveDoc = Json { ignoreUnknownKeys = true }.decodeFromString(ArchiveDoc.serializer(), messagesJson)
}
