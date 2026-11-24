package com.sovereignops.sms.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovBakTest {
    private val pw = "correct horse battery".toCharArray()
    private val plain = "synthetic MERIDIAN payload".toByteArray()
    private val fast = SovBak.MIN_ITERATIONS

    @Test
    fun `round trip at the shipped 600k iterations, header as documented`() {
        val file = SovBak.seal(plain, pw.copyOf(), 1L)
        val h = SovBak.header(file)
        assertEquals(600_000, h.iterations)
        assertEquals("PBKDF2-HMAC-SHA256", h.kdf)
        assertEquals("AES-256-GCM", h.cipher)
        assertArrayEquals(plain, SovBak.open(file, pw.copyOf()).getOrThrow())
    }

    @Test
    fun `wrong password and flipped ciphertext fail`() {
        val file = SovBak.seal(plain, pw.copyOf(), 1L, fast)
        assertEquals("Wrong password or damaged file", SovBak.open(file, "wrong password!".toCharArray()).exceptionOrNull()?.message)
        val flipped = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(SovBak.open(flipped, pw.copyOf()).isFailure)
    }

    @Test
    fun `header is authenticated - editing the iteration count or creation time fails`() {
        val file = SovBak.seal(plain, pw.copyOf(), 1L, fast)
        val text = String(file, Charsets.ISO_8859_1)
        val edited = text.replace("\"createdAtMillis\":1", "\"createdAtMillis\":2").toByteArray(Charsets.ISO_8859_1)
        assertNotEquals(text, String(edited, Charsets.ISO_8859_1))
        assertEquals("Wrong password or damaged file", SovBak.open(edited, pw.copyOf()).exceptionOrNull()?.message)
        val weak = text.replace("\"iterations\":10000", "\"iterations\":1").toByteArray(Charsets.ISO_8859_1)
        assertEquals("Iteration count out of range", SovBak.open(weak, pw.copyOf()).exceptionOrNull()?.message)
    }

    @Test
    fun `salt and nonce are fresh per file, garbage is rejected`() {
        val a = SovBak.header(SovBak.seal(plain, pw.copyOf(), 1L, fast))
        val b = SovBak.header(SovBak.seal(plain, pw.copyOf(), 1L, fast))
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.nonce, b.nonce)
        assertEquals("Not a .sovbak file", SovBak.open("hello".toByteArray(), pw).exceptionOrNull()?.message)
    }
}

class BackupContentTest {
    private fun card(t: Long, alias: String, vararg a: String) = CardDoc(t, a.toList(), alias, "NEW")
    private val day = LedgerDoc("2026-11-23", 5000, 4, 3, 60000, 3.0, 1.0, "synthetic")

    @Test
    fun `content survives encode and seal`() {
        val c = BackupContent(pack = "{}", checklist = "Deposit / Terms confirmed", cards = listOf(card(5, "MERIDIAN", "+19195550142")),
            quarantine = listOf(QuarantineDoc("+19195550199", 9)), ledger = listOf(day))
        val pw = "a long synthetic password".toCharArray()
        assertEquals(c, Backup.decode(SovBak.open(SovBak.seal(Backup.encode(c), pw.copyOf(), 1, SovBak.MIN_ITERATIONS), pw).getOrThrow()))
    }

    @Test
    fun `cards remap to this phone's threads by number`() {
        val r = Backup.remap(listOf(card(5, "A", "+19195550142"), card(6, "B", "+19195550143"), card(7, "C", "919-555-0142"))) { a ->
            if (a.any { it.endsWith("0142") }) 40L else null
        }
        // B has no conversation here and C's thread is already taken by A: both are kept as
        // unlinked cards (negative ids never match a conversation), never attached to whatever
        // thread happens to have their old id on this phone, and never dropped.
        assertEquals(listOf(40L to "A", -1L to "B", -2L to "C"), r.map { it.threadId to it.alias })
        assertEquals(listOf("+19195550143"), r[1].addresses)
    }

    @Test
    fun `merge renumbers incoming unlinked cards below this phone's and skips identical ones`() {
        val device = VaultPart(listOf(card(1, "phone"), card(-1, "old orphan", "+19195550150")), emptyList(), emptyList())
        val incoming = VaultPart(listOf(card(-1, "new orphan", "+19195550151"), card(-2, "old orphan", "+19195550150")), emptyList(), emptyList())
        val m = Backup.combine(device, incoming, RestoreMode.MERGE)
        assertEquals(listOf(1L to "phone", -1L to "old orphan", -2L to "new orphan"), m.cards.map { it.threadId to it.alias })
    }

    @Test
    fun `merge keeps this phone's data, overwrite replaces it`() {
        val device = VaultPart(listOf(card(1, "phone")), listOf(QuarantineDoc("x", 5)), listOf(day))
        val incoming = VaultPart(listOf(card(1, "backup"), card(2, "new")), listOf(QuarantineDoc("x", 3), QuarantineDoc("y", 4)), listOf(day, day.copy(date = "2026-11-24")))
        val m = Backup.combine(device, incoming, RestoreMode.MERGE)
        assertEquals(listOf("phone", "new"), m.cards.map { it.alias })
        assertEquals(setOf(QuarantineDoc("x", 3), QuarantineDoc("y", 4)), m.quarantine.toSet())
        assertEquals(2, m.ledger.size)
        assertEquals(incoming, Backup.combine(device, incoming, RestoreMode.OVERWRITE))
    }
}
