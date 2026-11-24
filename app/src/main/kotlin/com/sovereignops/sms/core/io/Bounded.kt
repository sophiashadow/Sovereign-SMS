package com.sovereignops.sms.core.io

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class TooLarge(val limit: Int) : IOException("File is larger than ${limit / (1024 * 1024)} MB")

/** Import size limits: far above any real file, low enough that a wrong or hostile file can't exhaust memory. */
object Limits {
    const val SETTINGS_PACK = 1 * 1024 * 1024
    const val BACKUP = 32 * 1024 * 1024
}

/** Reads the whole stream, or throws [TooLarge] as soon as it passes [max] bytes. */
fun InputStream.readAtMost(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw TooLarge(max)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
