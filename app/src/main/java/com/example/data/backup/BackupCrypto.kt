package com.example.data.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Wrong password, or the locked file was changed / damaged. */
class BackupPasswordException : Exception("รหัสผ่านไม่ถูกต้อง หรือไฟล์สำรองเสีย")

/**
 * Password-locked backup files.
 *
 * Layout: "RABK" | version (1 byte) | PBKDF2 iterations (int) | salt (16) | IV (12) | AES-256-GCM(zip bytes).
 * The key comes from the password with PBKDF2-HMAC-SHA1 (available on every Android version the app supports).
 * GCM checks the whole file, so a wrong password or a changed file is always detected, never half-restored.
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 150_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    const val MIN_PASSWORD = 8

    /** Problem with a new password (null = OK). */
    fun passwordProblem(password: String, confirm: String): String? = when {
        password.length < MIN_PASSWORD -> "รหัสผ่านต้องยาวอย่างน้อย $MIN_PASSWORD ตัวอักษร"
        password != confirm -> "รหัสผ่านทั้งสองช่องไม่ตรงกัน"
        else -> null
    }

    fun isLocked(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(MAGIC.size)
        input.read(head) == MAGIC.size && head.contentEquals(MAGIC)
    }

    fun lock(plain: File, out: File, password: String) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        out.outputStream().buffered().use { raw ->
            DataOutputStream(raw).apply {
                write(MAGIC); writeByte(VERSION.toInt()); writeInt(ITERATIONS); write(salt); write(iv); flush()
            }
            plain.inputStream().use { pump(it, raw, cipher) }
        }
    }

    /** Writes the zip inside [locked] to [out]. Throws [BackupPasswordException] on a wrong password. */
    fun unlock(locked: File, out: File, password: String) {
        try {
            locked.inputStream().buffered().use { raw ->
                val data = DataInputStream(raw)
                val head = ByteArray(MAGIC.size).also(data::readFully)
                require(head.contentEquals(MAGIC)) { "ไม่ใช่ไฟล์สำรองที่ตั้งรหัสไว้" }
                require(data.readByte() == VERSION) { "ไฟล์สำรองมาจากแอปเวอร์ชันใหม่กว่า — อัปเดตแอปก่อน" }
                val iterations = data.readInt()
                require(iterations in 10_000..10_000_000) { "ไฟล์สำรองเสีย" }
                val salt = ByteArray(SALT_SIZE).also(data::readFully)
                val iv = ByteArray(IV_SIZE).also(data::readFully)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
                out.outputStream().buffered().use { pump(raw, it, cipher) }
            }
        } catch (e: GeneralSecurityException) {
            out.delete()
            throw BackupPasswordException()
        } catch (e: java.io.EOFException) {
            out.delete()
            throw BackupPasswordException()
        }
    }

    private fun pump(input: InputStream, output: OutputStream, cipher: Cipher) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            cipher.update(buf, 0, n)?.let(output::write)
        }
        output.write(cipher.doFinal())
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
