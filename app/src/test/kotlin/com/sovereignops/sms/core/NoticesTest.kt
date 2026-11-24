package com.sovereignops.sms.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticesTest {
    private val dir = listOf(File("src/main/assets/licenses"), File("app/src/main/assets/licenses")).first { it.exists() }

    @Test
    fun `every shipped licence file is shown, and the index names each one`() {
        assertEquals(dir.list()!!.toSet(), Notices.FILES.toSet())
        assertEquals(Notices.FILES.size, Notices.FILES.toSet().size)
        val index = File(dir, "NOTICES.txt").readText()
        (Notices.FILES - "NOTICES.txt").forEach { assertTrue(it, index.contains("licenses/$it")) }
    }
}
