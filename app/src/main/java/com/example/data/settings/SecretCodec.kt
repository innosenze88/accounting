package com.example.data.settings

/**
 * Stores secrets (API keys, Sheets token, backup password) encrypted in SharedPreferences.
 * The format logic is pure Kotlin (unit-tested with a fake cipher); the real cipher is [KeystoreCipher].
 *
 *  - "ks1:<base64>"  = encrypted with the phone's Android Keystore key (cannot be read off the phone)
 *  - anything else   = an old plain value from before this version; it is read as-is and re-saved encrypted.
 */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

object SecretCodec {
    const val PREFIX = "ks1:"

    /** Value to store. Falls back to plain text only if the phone's keystore fails (the app must keep working). */
    fun encode(plain: String, cipher: SecretCipher?): String {
        if (plain.isEmpty() || cipher == null) return plain
        return runCatching { PREFIX + cipher.encrypt(plain) }.getOrDefault(plain)
    }

    /**
     * Value to use. An encrypted value that cannot be opened (e.g. the keystore key was reset) gives "" so the
     * person is asked to type the key again, instead of sending garbage to the API.
     */
    fun decode(stored: String?, cipher: SecretCipher?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        if (cipher == null) return ""
        return runCatching { cipher.decrypt(stored.removePrefix(PREFIX)) }.getOrDefault("")
    }

    /** true when [stored] is still plain text and should be re-saved encrypted. */
    fun needsUpgrade(stored: String?): Boolean = !stored.isNullOrEmpty() && !stored.startsWith(PREFIX)
}
