package com.example.util

import java.security.MessageDigest

/** SHA-256 of a file's bytes, used to spot the very same slip / PDF imported twice. */
object ContentHash {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
