package com.example.data.network

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.LineItemJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiOcrService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(AccountingDocumentJson::class.java)

    companion object {
        private const val TAG = "GeminiOcrService"
        private const val MODEL = "gemini-2.5-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        const val SYSTEM_INSTRUCTION = """คุณคือ "AI Accountant & OCR Extraction Expert" มีหน้าที่อ่าน สกัดข้อมูล และจำแนกประเภทเอกสารบัญชีการเงิน (เช่น ใบเสร็จรับเงิน, ใบสำคัญจ่าย, บิลค่าน้ำ/ค่าไฟ, ใบเสร็จมัดจำล่วงหน้า) จากรูปภาพหรือเอกสาร PDF ที่ได้รับ แล้วแปลงข้อมูลให้อยู่ในรูปแบบ JSON ตาม Schema ที่กำหนดเท่านั้น

### ขั้นตอนการทำงาน (Workflow Constraints):

1. การจำแนกประเภทเอกสาร (Document Type Classification):
   - "RECEIPT" : ใบเสร็จรับเงิน / ใบเสร็จรับเงินออกให้ลูกค้า
   - "PAYMENT_VOUCHER" : ใบสำคัญจ่าย / บิลจ่ายเงิน
   - "UTILITY_BILL" : บิลค่าน้ำ / ค่าไฟฟ้า / ค่าโทรศัพท์ / อินเทอร์เน็ต
   - "ADVANCE_DEPOSIT" : ใบเสร็จรับเงินมัดจำล่วงหน้า / เงินรับฝาก
   - "OTHER" : เอกสารอื่นๆ ที่ไม่เข้าพวก

2. การกำหนดประเภททรานแซกชัน (Transaction Type):
   - "INCOME" (รายรับ) : เมื่อเอกสารแสดงว่าเราเป็นผู้รับเงิน (เช่น ใบเสร็จที่ออกให้ลูกค้า)
   - "EXPENSE" (รายจ่าย) : เมื่อเอกสารแสดงว่าเราเป็นผู้จ่ายเงิน (เช่น บิลค่าน้ำไฟ, ใบสำคัญจ่าย, ซื้อของ)

3. การ Mapping คำและสกัดข้อมูลเข้าคอลัมน์มาตรฐาน (Field Mapping Rules):
   - document_no : ค้นหาคำว่า "เลขที่", "No.", "Receipt No.", "Invoice No.", "Doc No.", "เลขที่ใบเสร็จ"
   - date : ค้นหาคำว่า "วันที่", "Date", "Issue Date" -> แปลงเป็นฟอร์แมตมาตรฐาน YYYY-MM-DD (กรณีเป็น พ.ศ. ให้แปลงเป็น ค.ศ. เช่น 2569 -> 2026)
   - seller_name : ชื่อผู้ออกบิล / ผู้ขาย / ชื่อบริษัทบนหัวเอกสาร
   - seller_tax_id : "เลขประจำตัวผู้เสียภาษี", "Tax ID" ของผู้ขาย
   - customer_name : "นาม", "ลูกค้า", "Customer", "Sold To", "Bill To", "ได้รับเงินจาก"
   - customer_tax_id : "เลขประจำตัวผู้เสียภาษี", "Tax ID" ของลูกค้า
   - subtotal : "มูลค่าสินค้า/บริการ", "จำนวนเงิน", "Subtotal", "Amount Before VAT"
   - vat_amount : "ภาษีมูลค่าเพิ่ม 7%", "VAT", "VAT Amount"
   - total_amount : "จำนวนเงินรวมทั้งสิ้น", "รวมทั้งสิ้น", "Total Amount", "Grand Total", "ยอดชำระ"
   - deposit_amount : "เงินมัดจำ", "หักมัดจำ", "Deposit", "Advance Payment" (ถ้ามี)
   - payment_method : "เงินสด", "โอนเงิน", "บัตรเครดิต", "Cash", "Transfer", "Credit Card" (หากระบุ)
   - line_items : รายการสินค้า/บริการย่อย โดยสกัดเป็น Array ของ Object [{ item_name, quantity, unit_price, total_price }]

4. กฎการทำความสะอาดและจัดฟอร์แมตข้อมูล (Data Formatting Rules):
   - ตัวเลขการเงิน (Numeric Fields): ต้องเป็นประเภท number (Float/Integer) เท่านั้น ห้ามใส่เครื่องหมายจุลภาค (Comma) เช่น "1,250.00" -> 1250.00
   - วันที่ (Date Fields): ต้องอยู่ในรูปแบบ "YYYY-MM-DD" เท่านั้น
   - หากไม่พบข้อมูลใน Field ใด ให้กำหนดค่าเป็น null ห้ามเดาข้อมูลขึ้นมาเอง

5. การส่งผลลัพธ์ (Output Format):
   - ให้ตอบกลับเฉพาะ JSON Object ตาม Schema ที่ระบุไว้เท่านั้น
   - ห้ามใส่คำเกริ่น คำอธิบาย หรือ Markdown อื่นๆ นอกเหนือจากตัวโครงสร้าง JSON"""
    }

    suspend fun extractDocumentFromBitmap(bitmap: Bitmap): Result<Pair<AccountingDocumentJson, String>> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNullOrBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(
                IllegalStateException("GEMINI_API_KEY is not configured in Secrets. Please add your key in AI Studio Secrets panel.")
            )
        }

        try {
            // Resize bitmap if very large to prevent memory overhead while keeping crisp text
            val scaledBitmap = scaleBitmapIfNeeded(bitmap, maxDimension = 1600)
            val base64Image = bitmapToBase64(scaledBitmap)

            // Construct JSON request body for Gemini API
            val requestJson = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", SYSTEM_INSTRUCTION) })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "กรุณาสแกนและสกัดข้อมูลเอกสารทางการเงินนี้ตามกฎทั้งหมด แปลงเป็น JSON ตาม Schema เท่านั้น")
                            })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.1)
                })
            }

            val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "API Error: ${response.code} - $errorBody")
                return@withContext Result.failure(Exception("Gemini API error [${response.code}]: $errorBody"))
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response from Gemini API"))

            // Parse response candidates
            val rootJson = JSONObject(responseBody)
            val candidates = rootJson.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(Exception("No candidate returned by Gemini API"))
            }

            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")
                ?: return@withContext Result.failure(Exception("No text in candidate content"))

            val cleanJsonString = sanitizeJson(text)
            val parsedDoc = parseAndValidateJson(cleanJsonString)

            Result.success(Pair(parsedDoc, cleanJsonString))
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            Result.failure(e)
        }
    }

    private fun sanitizeJson(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json").trim()
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```").trim()
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```").trim()
        }
        val firstBrace = clean.indexOf('{')
        val lastBrace = clean.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            clean = clean.substring(firstBrace, lastBrace + 1)
        }
        return clean
    }

    private fun parseAndValidateJson(jsonString: String): AccountingDocumentJson {
        return try {
            val parsed = adapter.fromJson(jsonString)
            if (parsed != null) {
                // Ensure date format and types
                sanitizeParsedDocument(parsed)
            } else {
                fallbackJsonParser(jsonString)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Moshi parse failed, trying manual fallback", e)
            fallbackJsonParser(jsonString)
        }
    }

    private fun sanitizeParsedDocument(doc: AccountingDocumentJson): AccountingDocumentJson {
        // Fix Buddhist year if model returned e.g. 2569-xx-xx
        var formattedDate = doc.date
        if (formattedDate != null && formattedDate.length >= 4) {
            val yearPrefix = formattedDate.substring(0, 4).toIntOrNull()
            if (yearPrefix != null && yearPrefix > 2400) {
                val adYear = yearPrefix - 543
                formattedDate = "$adYear${formattedDate.substring(4)}"
            }
        }

        return doc.copy(date = formattedDate)
    }

    private fun fallbackJsonParser(jsonString: String): AccountingDocumentJson {
        val obj = JSONObject(jsonString)
        val lineItemsList = mutableListOf<LineItemJson>()
        val itemsArray = obj.optJSONArray("line_items")
        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                val itemObj = itemsArray.optJSONObject(i) ?: continue
                lineItemsList.add(
                    LineItemJson(
                        itemName = itemObj.optString("item_name", "").ifEmpty { null },
                        quantity = if (itemObj.has("quantity") && !itemObj.isNull("quantity")) itemObj.optDouble("quantity") else null,
                        unitPrice = if (itemObj.has("unit_price") && !itemObj.isNull("unit_price")) itemObj.optDouble("unit_price") else null,
                        totalPrice = if (itemObj.has("total_price") && !itemObj.isNull("total_price")) itemObj.optDouble("total_price") else null
                    )
                )
            }
        }

        var dateVal = obj.optString("date", "").ifEmpty { null }
        if (dateVal != null && dateVal.length >= 4) {
            val yearPrefix = dateVal.substring(0, 4).toIntOrNull()
            if (yearPrefix != null && yearPrefix > 2400) {
                dateVal = "${yearPrefix - 543}${dateVal.substring(4)}"
            }
        }

        return AccountingDocumentJson(
            documentType = obj.optString("document_type", "OTHER").ifEmpty { "OTHER" },
            transactionType = obj.optString("transaction_type", "EXPENSE").ifEmpty { "EXPENSE" },
            documentNo = obj.optString("document_no", "").ifEmpty { null },
            date = dateVal,
            sellerName = obj.optString("seller_name", "").ifEmpty { null },
            sellerTaxId = obj.optString("seller_tax_id", "").ifEmpty { null },
            customerName = obj.optString("customer_name", "").ifEmpty { null },
            customerTaxId = obj.optString("customer_tax_id", "").ifEmpty { null },
            subtotal = if (obj.has("subtotal") && !obj.isNull("subtotal")) obj.optDouble("subtotal") else null,
            vatAmount = if (obj.has("vat_amount") && !obj.isNull("vat_amount")) obj.optDouble("vat_amount") else null,
            totalAmount = if (obj.has("total_amount") && !obj.isNull("total_amount")) obj.optDouble("total_amount") else null,
            depositAmount = if (obj.has("deposit_amount") && !obj.isNull("deposit_amount")) obj.optDouble("deposit_amount") else null,
            paymentMethod = obj.optString("payment_method", "").ifEmpty { null },
            lineItems = lineItemsList
        )
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }
        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (width > height) {
            targetWidth = maxDimension
            targetHeight = (maxDimension / ratio).toInt()
        } else {
            targetHeight = maxDimension
            targetWidth = (maxDimension * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
