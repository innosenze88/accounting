package com.example

import com.example.data.backup.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Backups with guests' ID numbers are unreadable without the password; a wrong password never half-restores. */
class BackupCryptoTest {

    private fun tmp(name: String) = File.createTempFile(name, ".bin").apply { deleteOnExit() }

    @Test
    fun roundTripLargeFile() {
        val plain = tmp("plain").apply { writeBytes(Random(1).nextBytes(300_000)) }
        val enc = tmp("enc")
        val back = tmp("back")
        BackupCrypto.encrypt(plain, enc, "resort1234", iterations = 1000)
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertFalse(BackupCrypto.isEncrypted(plain))
        BackupCrypto.decrypt(enc, back, "resort1234")
        assertArrayEquals(plain.readBytes(), back.readBytes())
    }

    @Test
    fun wrongPasswordOrChangedFileIsRefused() {
        val plain = tmp("plain").apply { writeBytes("เลขบัตร 1234567890123".toByteArray()) }
        val enc = tmp("enc")
        BackupCrypto.encrypt(plain, enc, "resort1234", iterations = 1000)
        val out = tmp("out").apply { writeBytes(ByteArray(0)) }
        assertNotNull(runCatching { BackupCrypto.decrypt(enc, out, "wrongpass") }.exceptionOrNull() as? BackupCrypto.WrongPasswordException)
        assertTrue("nothing written for a wrong password", out.length() == 0L)
        val bytes = enc.readBytes().also { it[50] = (it[50].toInt() xor 1).toByte() }
        val changed = tmp("changed").apply { writeBytes(bytes) }
        assertNotNull(runCatching { BackupCrypto.decrypt(changed, out, "resort1234") }.exceptionOrNull())
    }

    @Test
    fun shortPasswordRejected() {
        assertNotNull(BackupCrypto.passwordProblem("123"))
    }
}
