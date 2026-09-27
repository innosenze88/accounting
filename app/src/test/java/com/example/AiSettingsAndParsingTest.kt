package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.network.OcrCommon
import com.example.data.settings.AiProvider
import com.example.data.settings.AiSettings
import com.example.data.settings.AiSettingsRepository
import com.example.data.settings.AesGcmSecretCipher
import javax.crypto.KeyGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiSettingsAndParsingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `settings are saved and reloaded`() {
        val repo = AiSettingsRepository(context)
        repo.save(AiSettings(provider = AiProvider.CLAUDE, claudeApiKey = "  sk-ant-test  ", claudeModel = "claude-sonnet-5"))

        val reloaded = AiSettingsRepository(context).settings.value
        assertEquals(AiProvider.CLAUDE, reloaded.provider)
        assertEquals("sk-ant-test", reloaded.claudeApiKey) // trimmed
        assertEquals("sk-ant-test", reloaded.activeApiKey)
        assertEquals("claude-sonnet-5", reloaded.activeModel)
    }

    private fun softwareCipher() = AesGcmSecretCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    private fun rawPrefs() = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    @Test
    fun `api keys and token are stored encrypted`() {
        rawPrefs().edit().clear().commit()
        val cipher = softwareCipher()
        val repo = AiSettingsRepository(context, cipher)
        repo.save(repo.settings.value.copy(geminiApiKey = "AIza-secret", claudeApiKey = "sk-ant-secret"))

        val onDisk = rawPrefs().all.values.joinToString(" ")
        assertFalse(onDisk.contains("AIza-secret"))
        assertFalse(onDisk.contains("sk-ant-secret"))
        assertFalse(onDisk.contains(repo.settings.value.sheetsToken))

        val reloaded = AiSettingsRepository(context, cipher).settings.value
        assertEquals("AIza-secret", reloaded.geminiApiKey)
        assertEquals("sk-ant-secret", reloaded.claudeApiKey)
        assertEquals(repo.settings.value.sheetsToken, reloaded.sheetsToken)
    }

    @Test
    fun `plain keys from an older version are read and then encrypted`() {
        rawPrefs().edit().clear().putString("gemini_api_key", "AIza-old").putString("sheets_token", "OLDTOKEN").commit()
        val cipher = softwareCipher()
        val loaded = AiSettingsRepository(context, cipher).settings.value
        assertEquals("AIza-old", loaded.geminiApiKey)
        assertEquals("OLDTOKEN", loaded.sheetsToken)
        assertTrue(rawPrefs().getString("gemini_api_key", "")!!.startsWith("enc1:"))
        assertTrue(rawPrefs().getString("sheets_token", "")!!.startsWith("enc1:"))
        assertEquals("AIza-old", AiSettingsRepository(context, cipher).settings.value.geminiApiKey)
    }

    @Test
    fun `a key that cannot be decrypted becomes empty instead of crashing`() {
        rawPrefs().edit().clear().commit()
        AiSettingsRepository(context, softwareCipher()).let { it.save(it.settings.value.copy(geminiApiKey = "AIza-x")) }
        val other = AiSettingsRepository(context, softwareCipher()).settings.value
        assertEquals("", other.geminiApiKey)
        assertTrue(other.sheetsToken.isNotBlank())
    }

    @Test
    fun `model reply in code fence is parsed and Buddhist year fixed`() {
        val reply = """
            ```json
            {"document_type":"RECEIPT","transaction_type":"INCOME","date":"2569-09-23",
             "total_amount":1070.0,"vat_amount":70.0,"line_items":[{"item_name":"Room","quantity":1,"total_price":1000}]}
            ```
        """.trimIndent()

        val doc = OcrCommon.parseDocument(OcrCommon.sanitizeJson(reply))
        assertEquals("RECEIPT", doc.documentType)
        assertEquals("2026-09-23", doc.date)
        assertEquals(1070.0, doc.totalAmount!!, 0.0)
        assertEquals(1, doc.lineItems?.size)
        assertNull(doc.documentNo)
    }
}
