package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.network.OcrCommon
import com.example.data.settings.AiProvider
import com.example.data.settings.AiSettings
import com.example.data.settings.AiSettingsRepository
import com.example.data.settings.SettingsCipher
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        clearSettings()
        val cipher = TestSettingsCipher()
        val repo = AiSettingsRepository(context, cipher)
        repo.save(AiSettings(provider = AiProvider.CLAUDE, claudeApiKey = "  sk-ant-test  ", claudeModel = "claude-sonnet-5"))

        val reloaded = AiSettingsRepository(context, cipher).settings.value
        assertEquals(AiProvider.CLAUDE, reloaded.provider)
        assertEquals("sk-ant-test", reloaded.claudeApiKey) // trimmed
        assertEquals("sk-ant-test", reloaded.activeApiKey)
        assertEquals("claude-sonnet-5", reloaded.activeModel)
    }

    @Test
    fun `legacy plaintext settings migrate to encrypted storage and remain usable`() {
        clearSettings()
        val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("provider", "CLAUDE")
            .putString("claude_api_key", "sk-ant-legacy-secret")
            .putString("claude_model", "claude-sonnet-5")
            .putString("sheets_token", "legacy-sheets-secret")
            .putBoolean("sheets_auto_sync", false)
            .commit()

        val cipher = TestSettingsCipher()
        val migrated = AiSettingsRepository(context, cipher).settings.value
        assertEquals("sk-ant-legacy-secret", migrated.claudeApiKey)
        assertEquals("legacy-sheets-secret", migrated.sheetsToken)
        assertFalse(migrated.sheetsAutoSync)
        assertFalse(prefs.contains("claude_api_key"))
        assertFalse(prefs.contains("sheets_token"))
        val encrypted = prefs.getString("encrypted_settings_v1", null)
        assertNotNull(encrypted)
        assertFalse(encrypted!!.contains("sk-ant-legacy-secret"))

        val reloaded = AiSettingsRepository(context, cipher).settings.value
        assertEquals(migrated, reloaded)
    }

    private fun clearSettings() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class TestSettingsCipher : SettingsCipher {
        override fun encrypt(plainText: String): String =
            Base64.getEncoder().encodeToString(plainText.toByteArray(StandardCharsets.UTF_8))

        override fun decrypt(cipherText: String): String =
            String(Base64.getDecoder().decode(cipherText), StandardCharsets.UTF_8)
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
