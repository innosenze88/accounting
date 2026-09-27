package com.example.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts short secrets (API keys, the Sheets token) before they are written to SharedPreferences. */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(sealed: String): String
}

/** AES-256-GCM. The 12-byte IV is stored in front of the ciphertext, the whole thing as Base64. */
class AesGcmSecretCipher(private val key: SecretKey) : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun decrypt(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "ข้อมูลเข้ารหัสไม่ถูกต้อง" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "resort_app_settings_v1"

        /**
         * Cipher whose key lives in the Android Keystore (it never leaves the phone and is not in backups).
         * Null when the Keystore is not usable on this device; the caller then keeps the old plain storage.
         */
        fun fromAndroidKeystore(): SecretCipher? = runCatching {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val key = (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                }
                .generateKey()
            AesGcmSecretCipher(key)
        }.onFailure { Log.w("SecretCipher", "Android Keystore not available: ${it.javaClass.simpleName}") }
            .getOrNull()
    }
}
