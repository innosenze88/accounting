package com.example.data.sheets

import com.example.data.local.ExtractedDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the Google Apps Script Web App (see res/raw/apps_script_code.txt).
 * Every request is a JSON POST with {action, token, ...}; the script answers {ok, error?}.
 */
class SheetsClient {

    companion object {
        /** Apps Script accepts requests up to about 50 MB; base64 adds a third. */
        const val MAX_BACKUP_UPLOAD = 30L * 1024 * 1024
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Apps Script answers POST with a 302 to googleusercontent.com; OkHttp follows it as GET.
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun ping(url: String, token: String): Result<String> = post(url, token, JSONObject().put("action", "ping"))
        .map { it.optString("spreadsheet", "") }

    suspend fun upsertDocument(url: String, token: String, doc: ExtractedDocumentEntity): Result<Unit> =
        post(url, token, JSONObject().put("action", "upsertDocument").put("document", documentJson(doc))).map { }

    /** Moves the document's row from "Accounting" to "Voided" (needs the Apps Script from app version 1.2+). */
    suspend fun voidDocument(url: String, token: String, doc: ExtractedDocumentEntity): Result<Unit> =
        post(url, token, JSONObject().put("action", "voidDocument").put("document", documentJson(doc))).map { }

    private fun documentJson(doc: ExtractedDocumentEntity): JSONObject {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return JSONObject().apply {
            put("app_id", "DOC-${doc.id}")
            put("status", doc.status)
            put("date", doc.date ?: JSONObject.NULL)
            put("transaction_type", doc.transactionType)
            put("document_type", doc.documentType)
            put("document_no", doc.documentNo ?: JSONObject.NULL)
            put("seller_name", doc.sellerName ?: JSONObject.NULL)
            put("seller_tax_id", doc.sellerTaxId ?: JSONObject.NULL)
            put("customer_name", doc.customerName ?: JSONObject.NULL)
            put("customer_tax_id", doc.customerTaxId ?: JSONObject.NULL)
            put("subtotal", doc.subtotal ?: JSONObject.NULL)
            put("vat_amount", doc.vatAmount ?: JSONObject.NULL)
            put("total_amount", doc.totalAmount ?: JSONObject.NULL)
            put("deposit_amount", doc.depositAmount ?: JSONObject.NULL)
            put("payment_method", doc.paymentMethod ?: JSONObject.NULL)
            put("verified_at", doc.verifiedAt?.let { stamp.format(java.util.Date(it)) } ?: JSONObject.NULL)
            put("source", if (doc.sourceFilePath != null) "file" else "camera")
            if (doc.voidedAt != null) {
                put("void_reason", doc.voidReason ?: "")
                put("voided_at", stamp.format(java.util.Date(doc.voidedAt)))
            }
        }
    }

    /** Uploads a backup zip to the "Resort Accounting Backups" folder in Google Drive (keeps the newest [keep]). */
    suspend fun saveBackup(url: String, token: String, file: java.io.File, keep: Int = 7): Result<String> {
        if (file.length() > MAX_BACKUP_UPLOAD) {
            return Result.failure(
                IllegalStateException("ไฟล์สำรองใหญ่เกินจะส่งขึ้น Drive ผ่าน Apps Script (${file.length() / 1_048_576} MB) — ใช้ \"บันทึกเป็นไฟล์\" แล้วเลือก Google Drive แทน")
            )
        }
        val body = JSONObject()
            .put("action", "saveBackup")
            .put("name", file.name)
            .put("keep", keep)
            .put("data", android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP))
        return post(url, token, body).map { it.optString("url") }
    }

    /** A slip / receipt sent to the LINE group, waiting in the "LineInbox" sheet. */
    data class LineItem(val id: String, val receivedAt: String, val sender: String, val fileId: String, val fileName: String, val mime: String)

    suspend fun listLineInbox(url: String, token: String, limit: Int = 30): Result<List<LineItem>> =
        post(url, token, JSONObject().put("action", "listLineInbox").put("limit", limit)).map { json ->
            val arr = json.optJSONArray("items") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                LineItem(
                    id = o.optString("id"), receivedAt = o.optString("received_at"), sender = o.optString("sender_name"),
                    fileId = o.optString("file_id"), fileName = o.optString("file_name"), mime = o.optString("mime")
                )
            }.filter { it.id.isNotBlank() && it.fileId.isNotBlank() }
        }

    /** Downloads one LINE slip file (bytes) from Google Drive through the Apps Script. */
    suspend fun getLineFile(url: String, token: String, fileId: String): Result<ByteArray> =
        post(url, token, JSONObject().put("action", "getLineFile").put("fileId", fileId)).map {
            android.util.Base64.decode(it.optString("data"), android.util.Base64.DEFAULT)
        }

    /** status = IMPORTED / DUPLICATE / FAILED */
    suspend fun markLineImported(url: String, token: String, id: String, status: String, docId: Long?, note: String?): Result<Unit> =
        post(
            url, token,
            JSONObject().put("action", "markLineImported").put("id", id).put("status", status)
                .put("docId", docId ?: JSONObject.NULL).put("note", note ?: "")
        ).map { }

    /** [reportJson] is the AI JSON of an eZee report. */
    suspend fun upsertReport(url: String, token: String, appId: String, fileName: String, reportJson: JSONObject): Result<Int> {
        val r = JSONObject(reportJson.toString()).apply {
            put("app_id", appId)
            put("file_name", fileName)
        }
        return post(url, token, JSONObject().put("action", "upsertReport").put("report", r))
            .map { it.optInt("rows", 0) }
    }

    /** Sends a table in chunks of [chunkSize] rows. Re-sending the same [importId] replaces the old rows. */
    suspend fun appendTable(
        url: String,
        token: String,
        importId: String,
        fileName: String,
        sheetName: String,
        headers: List<String>,
        rows: List<List<String>>,
        chunkSize: Int = 500
    ): Result<Int> {
        var sent = 0
        val chunks = if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(chunkSize)
        chunks.forEachIndexed { index, chunk ->
            val body = JSONObject().apply {
                put("action", "appendTable")
                put("importId", importId)
                put("fileName", fileName)
                put("sheetName", sheetName)
                put("chunkIndex", index)
                put("headers", JSONArray(headers))
                put("rows", JSONArray().apply { chunk.forEach { put(JSONArray(it)) } })
            }
            val result = post(url, token, body)
            if (result.isFailure) {
                return Result.failure(
                    IllegalStateException("ส่งได้ $sent แถว แล้วหยุด: ${result.exceptionOrNull()?.localizedMessage}")
                )
            }
            sent += chunk.size
        }
        return Result.success(sent)
    }

    private suspend fun post(url: String, token: String, body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "ยังไม่ได้ใส่ URL ของ Apps Script ในหน้าตั้งค่า" }
            body.put("token", token)
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Google Sheets [${response.code}] ตรวจ URL และการ Deploy ของ Apps Script")
                }
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    // HTML instead of JSON usually means the web app is not shared with "Anyone".
                    throw IllegalStateException(
                        "Apps Script ไม่ได้ตอบเป็น JSON — ตอน Deploy ต้องเลือก Execute as: Me และ Who has access: Anyone"
                    )
                }
                if (!json.optBoolean("ok", false)) {
                    throw IllegalStateException("Google Sheets: ${json.optString("error", "ไม่ทราบสาเหตุ")}")
                }
                json
            }
        }
    }
}
