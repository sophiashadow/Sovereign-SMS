package com.sovereignops.sms.core.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedTest {
    @Test
    fun readsUpToTheLimit() {
        val bytes = ByteArray(1000) { it.toByte() }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readAtMost(1000))
    }

    @Test
    fun stopsAnEndlessStreamWithoutReadingItAll() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        assertThrows(TooLarge::class.java) { endless.readAtMost(1024 * 1024) }
        assert(served < 2L * 1024 * 1024) { "read $served bytes" }
    }
}
