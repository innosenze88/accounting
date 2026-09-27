package com.example

import com.example.data.backup.BackupCrypto
import com.example.data.backup.BackupPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** Backups that leave the phone are locked with a password; a wrong password or a changed file is refused. */
class BackupCryptoTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun plainFile(size: Int = 200_000): File =
        tmp.newFile("backup.zip").apply { writeBytes(Random(7).nextBytes(size)) }

    @Test
    fun lockedFileOpensWithTheRightPassword() {
        val plain = plainFile()
        val locked = tmp.newFile("backup.rbackup")
        BackupCrypto.lock(plain, locked, "correct horse 1")
        assertTrue(BackupCrypto.isLocked(locked))
        assertFalse(BackupCrypto.isLocked(plain))

        val out = tmp.newFile("out.zip")
        BackupCrypto.unlock(locked, out, "correct horse 1")
        assertArrayEquals(plain.readBytes(), out.readBytes())
    }

    @Test
    fun lockedFileDoesNotContainThePlainBytes() {
        val plain = tmp.newFile("p.zip").apply { writeText("PK" + "เลขบัตร 1234567890123 ".repeat(50)) }
        val locked = tmp.newFile("p.rbackup")
        BackupCrypto.lock(plain, locked, "password123")
        assertFalse(String(locked.readBytes(), Charsets.ISO_8859_1).contains("1234567890123"))
    }

    @Test
    fun wrongPasswordIsRefusedAndLeavesNoFile() {
        val locked = tmp.newFile("b.rbackup")
        BackupCrypto.lock(plainFile(), locked, "password123")
        val out = File(tmp.root, "out.zip")
        val e = runCatching { BackupCrypto.unlock(locked, out, "password124") }.exceptionOrNull()
        assertTrue(e is BackupPasswordException)
        assertFalse(out.exists())
    }

    @Test
    fun changedFileIsRefused() {
        val locked = tmp.newFile("b.rbackup")
        BackupCrypto.lock(plainFile(), locked, "password123")
        val bytes = locked.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        locked.writeBytes(bytes)
        val e = runCatching { BackupCrypto.unlock(locked, File(tmp.root, "o.zip"), "password123") }.exceptionOrNull()
        assertTrue(e is BackupPasswordException)
    }

    @Test
    fun cutOffFileIsRefused() {
        val locked = tmp.newFile("b.rbackup")
        BackupCrypto.lock(plainFile(), locked, "password123")
        locked.writeBytes(locked.readBytes().copyOf(30))
        val e = runCatching { BackupCrypto.unlock(locked, File(tmp.root, "o.zip"), "password123") }.exceptionOrNull()
        assertTrue(e is BackupPasswordException)
    }

    @Test
    fun passwordRules() {
        assertNotNull(BackupCrypto.passwordProblem("short", "short"))
        assertNotNull(BackupCrypto.passwordProblem("password123", "password124"))
        assertNull(BackupCrypto.passwordProblem("password123", "password123"))
        assertEquals(8, BackupCrypto.MIN_PASSWORD)
    }
}
