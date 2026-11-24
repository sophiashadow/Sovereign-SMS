package com.sovereignops.sms.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SovBakHeader(
    val format: String = FORMAT,
    val version: Int = 1,
    val kdf: String = KDF,
    val iterations: Int,
    val salt: String,
    val cipher: String = CIPHER,
    val nonce: String,
    val createdAtMillis: Long,
) {
    companion object {
        const val FORMAT = "sovereign-sms/sovbak"
        const val KDF = "PBKDF2-HMAC-SHA256"
        const val CIPHER = "AES-256-GCM"
    }
}

/**
 * `.sovbak`: a password-encrypted backup file, built only from standard primitives.
 *
 * Layout: one line of JSON header (UTF-8), `\n`, then AES-256-GCM ciphertext with its
 * 128-bit tag. The key is PBKDF2-HMAC-SHA256 (600,000 iterations, 16-byte random salt)
 * of the password; the 12-byte nonce is random per file. The exact header bytes are the
 * GCM additional authenticated data, so changing any header field (iterations, salt,
 * nonce, version) makes decryption fail instead of weakening it.
 */
object SovBak {
    const val ITERATIONS = 600_000
    const val MIN_ITERATIONS = 10_000
    const val MAX_ITERATIONS = 10_000_000
    const val MIN_PASSWORD = 10
    private const val SALT = 16
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    class BadFile(message: String) : Exception(message)

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(plain: ByteArray, password: CharArray, createdAtMillis: Long, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): ByteArray {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val header = SovBakHeader(iterations = iterations, salt = b64.encodeToString(salt), nonce = b64.encodeToString(nonce), createdAtMillis = createdAtMillis)
        val headerBytes = json.encodeToString(SovBakHeader.serializer(), header).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(headerBytes)
        return headerBytes + '\n'.code.toByte() + cipher.doFinal(plain)
    }

    fun header(file: ByteArray): SovBakHeader {
        val nl = file.indexOf('\n'.code.toByte())
        if (nl <= 0 || nl > 4096) throw BadFile("Not a .sovbak file")
        val h = runCatching { json.decodeFromString(SovBakHeader.serializer(), file.copyOfRange(0, nl).toString(Charsets.UTF_8)) }
            .getOrElse { throw BadFile("Not a .sovbak file") }
        if (h.format != SovBakHeader.FORMAT) throw BadFile("Not a .sovbak file")
        if (h.version != 1) throw BadFile("Unsupported .sovbak version ${h.version}")
        if (h.kdf != SovBakHeader.KDF || h.cipher != SovBakHeader.CIPHER) throw BadFile("Unsupported algorithms")
        if (h.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BadFile("Iteration count out of range")
        return h
    }

    /** Fails with [BadFile] for a malformed header and with "Wrong password or damaged file" when GCM authentication fails. */
    fun open(file: ByteArray, password: CharArray): Result<ByteArray> = runCatching {
        val h = header(file)
        val nl = file.indexOf('\n'.code.toByte())
        val salt = runCatching { unb64.decode(h.salt) }.getOrNull()
        val nonce = runCatching { unb64.decode(h.nonce) }.getOrNull()
        if (salt?.size != SALT || nonce?.size != NONCE) throw BadFile("Malformed header")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, h.iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(file.copyOfRange(0, nl))
        runCatching { cipher.doFinal(file, nl + 1, file.size - nl - 1) }.getOrElse { throw BadFile("Wrong password or damaged file") }
    }
}
