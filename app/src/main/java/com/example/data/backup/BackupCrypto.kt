package com.example.data.backup

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password protection for backup files (they hold guests' ID / passport numbers).
 * Pure JVM crypto, streamed so large backups never have to fit in memory. Unit-tested.
 *
 * File layout: "RABK" | version(1) | kdf(1: 1=PBKDF2-SHA256, 2=PBKDF2-SHA1) | iterations(4) | salt(16) | iv(16)
 *              | AES-256-CTR ciphertext | HMAC-SHA256(header + ciphertext)(32)
 * Encrypt-then-MAC: a wrong password or a changed file is detected BEFORE anything is decrypted.
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val KDF_SHA256: Byte = 1
    private const val KDF_SHA1: Byte = 2
    private const val HEADER = 4 + 1 + 1 + 4 + 16 + 16
    private const val MAC_LEN = 32
    const val ITERATIONS = 120_000
    const val MIN_PASSWORD = 6

    class WrongPasswordException : IllegalStateException("รหัสผ่านไฟล์สำรองไม่ถูกต้อง (หรือไฟล์เสีย)")

    fun isEncrypted(file: File): Boolean = file.length() > HEADER + MAC_LEN &&
        file.inputStream().use { i -> ByteArray(4).also { i.read(it) }.contentEquals(MAGIC) }

    fun passwordProblem(password: String): String? =
        if (password.length < MIN_PASSWORD) "รหัสผ่านต้องยาวอย่างน้อย $MIN_PASSWORD ตัว" else null

    fun encrypt(input: File, output: File, password: String, iterations: Int = ITERATIONS) {
        passwordProblem(password)?.let { throw IllegalArgumentException(it) }
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val (kdf, keys) = deriveKeys(password, salt, iterations, null)
        val header = MAGIC + byteArrayOf(VERSION, kdf) + intBytes(iterations) + salt + iv
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.copyOfRange(0, 32), "AES"), IvParameterSpec(iv))
        }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keys.copyOfRange(32, 64), "HmacSHA256")) }
        mac.update(header)
        output.outputStream().buffered().use { out ->
            out.write(header)
            input.inputStream().buffered().use { inp ->
                pump(inp) { buf, n ->
                    val c = cipher.update(buf, 0, n)
                    if (c != null) { mac.update(c); out.write(c) }
                }
            }
            cipher.doFinal()?.takeIf { it.isNotEmpty() }?.let { mac.update(it); out.write(it) }
            out.write(mac.doFinal())
        }
    }

    /** Throws [WrongPasswordException] when the password is wrong or the file was changed. */
    fun decrypt(input: File, output: File, password: String) {
        val total = input.length()
        require(total > HEADER + MAC_LEN) { "ไฟล์สำรองเสีย" }
        val header = input.inputStream().use { i -> ByteArray(HEADER).also { readFully(i, it) } }
        check(header.copyOfRange(0, 4).contentEquals(MAGIC)) { "ไม่ใช่ไฟล์สำรองที่เข้ารหัส" }
        check(header[4] == VERSION) { "ไฟล์สำรองมาจากแอปเวอร์ชันใหม่กว่า — อัปเดตแอปก่อน" }
        val kdf = header[5]
        val iterations = intFrom(header, 6)
        require(iterations in 1..10_000_000) { "ไฟล์สำรองเสีย" }
        val salt = header.copyOfRange(10, 26)
        val iv = header.copyOfRange(26, 42)
        val (_, keys) = deriveKeys(password, salt, iterations, kdf)
        val cipherLen = total - HEADER - MAC_LEN

        // 1) Check the MAC first (nothing is written for a wrong password).
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keys.copyOfRange(32, 64), "HmacSHA256")) }
        mac.update(header)
        val stored = ByteArray(MAC_LEN)
        input.inputStream().buffered().use { i ->
            skipFully(i, HEADER.toLong())
            copyLimited(i, cipherLen) { buf, n -> mac.update(buf, 0, n) }
            readFully(i, stored)
        }
        if (!MessageDigest.isEqual(mac.doFinal(), stored)) throw WrongPasswordException()

        // 2) Decrypt.
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.copyOfRange(0, 32), "AES"), IvParameterSpec(iv))
        }
        output.outputStream().buffered().use { out ->
            input.inputStream().buffered().use { i ->
                skipFully(i, HEADER.toLong())
                copyLimited(i, cipherLen) { buf, n -> cipher.update(buf, 0, n)?.let { out.write(it) } }
            }
            cipher.doFinal()?.takeIf { it.isNotEmpty() }?.let { out.write(it) }
        }
    }

    /** 64 bytes: AES key + MAC key. PBKDF2-SHA256 when the phone has it (Android 8+), otherwise SHA1. */
    private fun deriveKeys(password: String, salt: ByteArray, iterations: Int, kdf: Byte?): Pair<Byte, ByteArray> {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 512)
        fun run(alg: String) = SecretKeyFactory.getInstance(alg).generateSecret(spec).encoded
        return try {
            when (kdf) {
                KDF_SHA1 -> KDF_SHA1 to run("PBKDF2WithHmacSHA1")
                KDF_SHA256 -> KDF_SHA256 to run("PBKDF2WithHmacSHA256")
                else -> try {
                    KDF_SHA256 to run("PBKDF2WithHmacSHA256")
                } catch (e: java.security.NoSuchAlgorithmException) {
                    KDF_SHA1 to run("PBKDF2WithHmacSHA1")
                }
            }
        } finally {
            spec.clearPassword()
        }
    }

    private inline fun pump(input: InputStream, block: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) block(buf, n)
        }
    }

    private inline fun copyLimited(input: InputStream, length: Long, block: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IllegalStateException("ไฟล์สำรองไม่สมบูรณ์")
            block(buf, n)
            left -= n
        }
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) throw IllegalStateException("ไฟล์สำรองไม่สมบูรณ์")
            off += n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) { if (input.read() < 0) throw IllegalStateException("ไฟล์สำรองไม่สมบูรณ์") else left-- } else left -= n
        }
    }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun intFrom(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
