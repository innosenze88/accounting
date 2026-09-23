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

/** Google Gemini (generativelanguage.googleapis.com) document reader. */
class GeminiOcrService : OcrService {

    companion object {
        private const val TAG = "GeminiOcrService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    override suspend fun extract(
        input: DocumentInput,
        apiKey: String,
        model: String
    ): Result<Pair<AccountingDocumentJson, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = generate(apiKey, model, buildBody(OcrCommon.SYSTEM_INSTRUCTION, OcrCommon.USER_PROMPT, input))
            if (text.isBlank()) throw IllegalStateException("Gemini ไม่มีข้อความในผลลัพธ์")
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
            val text = generate(apiKey, model, buildBody(OcrCommon.REPORT_INSTRUCTION, OcrCommon.REPORT_PROMPT, input))
            if (text.isBlank()) throw IllegalStateException("Gemini ไม่มีข้อความในผลลัพธ์")
            OcrCommon.sanitizeJson(text)
        }.onFailure { Log.e(TAG, "Report extraction failed", it) }
    }

    private fun buildBody(system: String, prompt: String, input: DocumentInput): JSONObject {
        val (mime, data) = when (input) {
            is DocumentInput.Image -> "image/jpeg" to OcrCommon.encodeImage(input.bitmap)
            is DocumentInput.Pdf -> "application/pdf" to OcrCommon.encodeBytes(input.bytes)
        }
        return JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().put("inline_data", JSONObject().apply {
                    put("mime_type", mime)
                    put("data", data)
                }))
            })))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.1)
            })
        }
    }

    override suspend fun testConnection(apiKey: String, model: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "ping")))))
                put("generationConfig", JSONObject().put("maxOutputTokens", 16))
            }
            generate(apiKey, model, body, allowEmpty = true)
            Unit
        }
    }

    /** Calls generateContent and returns the first text part. Key goes in a header, not the URL. */
    private fun generate(apiKey: String, model: String, body: JSONObject, allowEmpty: Boolean = false): String {
        val request = Request.Builder()
            .url("$BASE_URL/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        OcrCommon.httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                throw IllegalStateException(OcrCommon.describeHttpError("Gemini", response.code, responseBody))
            }
            val root = JSONObject(responseBody ?: throw IllegalStateException("Gemini ตอบกลับว่างเปล่า"))
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                if (allowEmpty) return ""
                val reason = root.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
                throw IllegalStateException("Gemini ไม่ส่งผลลัพธ์" + (reason?.let { " ($it)" } ?: ""))
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: if (allowEmpty) return "" else throw IllegalStateException("Gemini ไม่มีข้อความในผลลัพธ์")
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                // Skip "thought" parts from thinking models.
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) return text
            }
            // No text part: callers that need text (extract) turn this into an error.
            return ""
        }
    }
}
