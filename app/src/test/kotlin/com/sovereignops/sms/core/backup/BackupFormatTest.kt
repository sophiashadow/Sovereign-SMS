package com.sovereignops.sms.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version handling of the backup content inside a `.sovbak`. Synthetic data only. */
class BackupFormatTest {
    private fun fails(json: String) = runCatching { Backup.decode(json.toByteArray()) }.exceptionOrNull()?.message.orEmpty()

    /** Exactly what v0.1 builds before ledger schema 3 wrote: no basis, no cash fields. */
    private val v1 = """
        {"format":"sovereign-sms/backup","version":1,"pack":null,"checklist":null,"cards":[],"quarantine":[],
         "ledger":[{"date":"2026-11-23","roomCostCents":5000,"booked":4,"showed":3,"grossCents":60000,
                    "sessionHours":3.0,"overheadHours":1.0,"note":"synthetic"}]}
    """.trimIndent()

    @Test
    fun `version 1 is still read and its ledger days stay legacy, unreinterpreted`() {
        val c = Backup.decode(v1.toByteArray())
        val d = c.ledger.single()
        assertEquals(LedgerDoc.LEGACY, d.basis)
        assertEquals(60000L, d.grossCents)
        assertEquals(5000L, d.roomCostCents)
        assertEquals(0L, d.cashReceivedCents)
    }

    @Test
    fun `this app writes version 2 and reads it back`() {
        val day = LedgerDoc(
            "2026-11-24", 0, 5, 4, 0, 4.0, 2.0, "MERIDIAN", basis = LedgerDoc.CASH,
            cashReceivedCents = 80000, refundCents = 5000, expenseCents = 9000, receivableCents = 20000, restrictedCreditCents = 15000,
        )
        val c = BackupContent(ledger = listOf(day))
        assertEquals(BackupContent.VERSION, c.version)
        assertEquals(2, BackupContent.VERSION)
        assertEquals(c, Backup.decode(Backup.encode(c)))
        assertTrue(Backup.encode(c).toString(Charsets.UTF_8).contains("\"version\":2"))
    }

    @Test
    fun `a newer version is refused with a clear message, before anything is restored`() {
        val m = fails(v1.replace("\"version\":1", "\"version\":3"))
        assertEquals("This backup was made by a newer version of the app (content version 3; this app reads 1 to 2). Update the app, then restore.", m)
    }

    @Test
    fun `wrong format, bad version and unknown ledger basis are refused`() {
        assertEquals("Not a Sovereign SMS backup", fails(v1.replace("sovereign-sms/backup", "sovereign-sms/pack")))
        assertEquals("Unsupported backup content version 0", fails(v1.replace("\"version\":1", "\"version\":0")))
        assertEquals(
            "Unsupported ledger basis ACCRUAL on 2026-11-23",
            fails(v1.replace("\"version\":1", "\"version\":2").replace("\"note\":\"synthetic\"", "\"note\":\"x\",\"basis\":\"ACCRUAL\"")),
        )
    }

    @Test
    fun `unknown fields from a same-version writer are ignored`() {
        val c = Backup.decode(v1.replace("\"pack\":null", "\"pack\":null,\"futureField\":{\"a\":1}").toByteArray())
        assertEquals(1, c.ledger.size)
    }

    @Test
    fun `a newer container version is refused with a clear message`() {
        val pw = "a long synthetic password".toCharArray()
        val sealed = SovBak.seal(Backup.encode(BackupContent()), pw.copyOf(), 1L, SovBak.MIN_ITERATIONS)
        val text = sealed.toString(Charsets.ISO_8859_1)
        val bumped = text.replaceFirst("\"version\":1", "\"version\":2").toByteArray(Charsets.ISO_8859_1)
        assertEquals(
            "This .sovbak was made by a newer version of the app (file version 2). Update the app, then restore.",
            SovBak.open(bumped, pw).exceptionOrNull()?.message,
        )
    }
}
