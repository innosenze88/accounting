package com.example.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption backed by a non-exportable Android Keystore AES key. */
interface SettingsCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

class AndroidKeystoreSettingsCipher : SettingsCipher {
    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            FORMAT,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        ).joinToString(":")
    }

    override fun decrypt(cipherText: String): String {
        val parts = cipherText.split(":", limit = 3)
        require(parts.size == 3 && parts[0] == FORMAT) { "Unsupported encrypted settings format." }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "accounting_settings_aes_gcm_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT = "v1"
        const val TAG_BITS = 128
        val AAD = "accounting-ai-settings-v1".toByteArray(StandardCharsets.UTF_8)
        val KEY_LOCK = Any()
    }
}