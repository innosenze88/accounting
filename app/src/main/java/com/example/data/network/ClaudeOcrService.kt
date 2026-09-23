package com.example.data.network

import android.util.Log
import com.example.data.model.AccountingDocumentJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Claude (Messages API) document reader. */
class ClaudeOcrService : OcrService {

    companion object {
        private const val TAG = "ClaudeOcrService"
        private const val URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 4096
        private const val REPORT_MAX_TOKENS = 16000
    }

    override suspend fun extract(
        input: DocumentInput,
        apiKey: String,
        model: String
    ): Result<Pair<AccountingDocumentJson, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = send(apiKey, buildBody(model, OcrCommon.SYSTEM_INSTRUCTION, OcrCommon.USER_PROMPT, input))
            if (text.isBlank()) throw IllegalStateException("Claude ไม่มีข้อความในผลลัพธ์")
            val clean = OcrCommon.sanitizeJson(text)
            OcrCommon.parseDocument(clean) to clean
        }.onFailure { Log.e(TAG, "Extraction failed", it) }
    }

    override suspend fun extractReport(
        input: DocumentInput,
        apiKey: String,
        model: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val text = send(apiKey, buildBody(model, OcrCommon.REPORT_INSTRUCTION, OcrCommon.REPORT_PROMPT, input, REPORT_MAX_TOKENS))
            if (text.isBlank()) throw IllegalStateException("Claude ไม่มีข้อความในผลลัพธ์")
            OcrCommon.sanitizeJson(text)
        }.onFailure { Log.e(TAG, "Report extraction failed", it) }
    }

    private fun buildBody(
        model: String,
        system: String,
        prompt: String,
        input: DocumentInput,
        maxTokens: Int = MAX_TOKENS
    ): JSONObject {
        val fileBlock = when (input) {
            is DocumentInput.Image -> JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", OcrCommon.encodeImage(input.bitmap))
                })
            }
            is DocumentInput.Pdf -> JSONObject().apply {
                put("type", "document")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "application/pdf")
                    put("data", OcrCommon.encodeBytes(input.bytes))
                })
            }
        }
        val content = JSONArray().put(fileBlock).put(JSONObject().put("type", "text").put("text", prompt))
        return JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        }
    }

    override suspend fun testConnection(apiKey: String, model: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 16)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            }
            send(apiKey, body)
            Unit
        }
    }

    /** Sends a Messages API request and returns the joined text blocks (thinking blocks are skipped). */
    private fun send(apiKey: String, body: JSONObject): String {
        val request = Request.Builder()
            .url(URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        OcrCommon.httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                throw IllegalStateException(OcrCommon.describeHttpError("Claude", response.code, responseBody))
            }
            val root = JSONObject(responseBody ?: throw IllegalStateException("Claude ตอบกลับว่างเปล่า"))
            val blocks = root.optJSONArray("content") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") sb.append(block.optString("text", ""))
            }
            return sb.toString()
        }
    }
}
