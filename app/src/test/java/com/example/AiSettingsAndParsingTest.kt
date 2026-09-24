package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.network.OcrCommon
import com.example.data.settings.AiProvider
import com.example.data.settings.AiSettings
import com.example.data.settings.AiSettingsRepository
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
