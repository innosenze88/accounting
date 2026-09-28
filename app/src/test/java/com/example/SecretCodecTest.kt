package com.example

import com.example.data.settings.SecretCipher
import com.example.data.settings.SecretCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** API keys are stored encrypted; old plain values keep working and get upgraded. */
class SecretCodecTest {

    private val fake = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(encoded: String) = encoded.reversed()
    }
    private val broken = object : SecretCipher {
        override fun encrypt(plain: String): String = throw IllegalStateException("keystore")
        override fun decrypt(encoded: String): String = throw IllegalStateException("keystore")
    }

    @Test
    fun roundTrip() {
        val stored = SecretCodec.encode("sk-ant-api03-secret", fake)
        assertTrue(stored.startsWith(SecretCodec.PREFIX))
        assertFalse(stored.contains("secret"))
        assertEquals("sk-ant-api03-secret", SecretCodec.decode(stored, fake))
    }

    @Test
    fun oldPlainValuesStillWorkAndAreUpgraded() {
        assertEquals("AIza-old", SecretCodec.decode("AIza-old", fake))
        assertTrue(SecretCodec.needsUpgrade("AIza-old"))
        assertFalse(SecretCodec.needsUpgrade(SecretCodec.encode("x", fake)))
        assertFalse(SecretCodec.needsUpgrade(""))
    }

    @Test
    fun brokenKeystoreNeverBreaksTheApp() {
        assertEquals("plain", SecretCodec.encode("plain", broken)) // falls back to plain
        assertEquals("", SecretCodec.decode(SecretCodec.PREFIX + "zzz", broken)) // asks for the key again
        assertEquals("", SecretCodec.encode("", fake))
    }
}
