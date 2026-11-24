package com.sovereignops.sms.core.preserve

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

// Synthetic thread only.
class ThreadArchiveTest {
    private val png = byteArrayOf(1, 2, 3)
    private val path = ThreadArchive.attachmentPath(11, 0, "../map pic.png")
    private val doc = ArchiveDoc(
        title = "MERIDIAN", participants = listOf("+19195550142"), exportedAtMillis = 1_795_000_000_000,
        messages = listOf(
            ArchiveMessage(12, 1_794_999_000_000, "out", "me", "See you at Ashford.\n\n> quoted <b>tag</b> \\ slash"),
            ArchiveMessage(11, 1_794_998_000_000, "in", "+19195550142", "", listOf(ArchiveAttachment(path, "image/png", "../map pic.png"))),
            ArchiveMessage(13, 1_794_999_500_000, "in", "+19195550142", "lost one", listOf(ArchiveAttachment("attachments/13-0-x.jpg", "image/jpeg", "x.jpg"))),
        ),
    )
    private val entries = ThreadArchive.entries(doc, mapOf(path to png), ZoneOffset.UTC)

    @Test
    fun `archive holds markdown, exact json, attachments and a manifest last`() {
        assertEquals(listOf("thread.md", "messages.json", "attachments/11-0-map_pic.png", "manifest.sha256"), entries.keys.toList())
        assertArrayEquals(png, entries[path])
        val back = ThreadArchive.decode(entries.getValue("messages.json").toString(Charsets.UTF_8))
        assertEquals(doc.messages.map { it.body }, back.messages.map { it.body }) // no text trimmed or rewritten
        assertTrue(back.messages.first { it.id == 13L }.attachments.single().missing)
    }

    @Test
    fun `manifest is sha256sum format and verifies, tampering is reported`() {
        val lines = entries.getValue(ThreadArchive.MANIFEST).toString(Charsets.UTF_8).trimEnd().lines()
        assertEquals(3, lines.size)
        assertTrue(lines.all { Regex("[0-9a-f]{64}  \\S.*").matches(it) })
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81  attachments/11-0-map_pic.png", lines[0])
        assertEquals(emptyList<String>(), ThreadArchive.verify(entries))
        val tampered = LinkedHashMap(entries).apply { put("thread.md", "edited".toByteArray()); put("extra.txt", byteArrayOf()) }
        assertEquals(listOf("extra.txt", "thread.md"), ThreadArchive.verify(tampered))
    }

    @Test
    fun `markdown is chronological, quotes bodies and neutralises markup`() {
        val md = entries.getValue("thread.md").toString(Charsets.UTF_8)
        assertTrue(md.indexOf("Received from +19195550142\n\n- Attachment: [../map pic.png](attachments/11-0-map_pic.png)") < md.indexOf("Sent"))
        assertTrue(md.contains("> See you at Ashford.\n> \n> > quoted &lt;b>tag&lt;/b> \\\\ slash"))
        assertTrue(md.contains("- Attachment not readable: x.jpg (image/jpeg)"))
    }

    @Test
    fun `safe names cannot escape the attachments folder`() {
        assertEquals("passwd", ThreadArchive.safeName("../../etc/passwd").substringAfterLast('_'))
        assertTrue(!ThreadArchive.attachmentPath(1, 0, "../../x").contains(".."))
        assertEquals("file", ThreadArchive.safeName("///"))
    }
}
