package com.example.data.network

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.LineItemJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** What we send to the AI: a photo/image or an original PDF file. */
sealed class DocumentInput {
    class Image(val bitmap: Bitmap) : DocumentInput()
    class Pdf(val bytes: ByteArray) : DocumentInput()
}

/** Common contract for every AI provider that can read accounting documents. */
interface OcrService {
    /** Reads a receipt/slip/bill. Returns the parsed document and the cleaned raw JSON text. */
    suspend fun extract(input: DocumentInput, apiKey: String, model: String): Result<Pair<AccountingDocumentJson, String>>

    /** Reads a hotel PMS report (e.g. eZee PDF). Returns the cleaned JSON text (see [OcrCommon.REPORT_INSTRUCTION]). */
    suspend fun extractReport(input: DocumentInput, apiKey: String, model: String): Result<String>

    /** Sends a tiny request to check that the key and model work. */
    suspend fun testConnection(apiKey: String, model: String): Result<Unit>
}

internal object OcrCommon {
    private const val TAG = "OcrCommon"

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val adapter by lazy {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(AccountingDocumentJson::class.java)
    }

    const val USER_PROMPT = "กรุณาสแกนและสกัดข้อมูลเอกสารทางการเงินนี้ตามกฎทั้งหมด แปลงเป็น JSON ตาม Schema เท่านั้น"

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
   - document_type : หนึ่งในค่าข้อ 1
   - transaction_type : หนึ่งในค่าข้อ 2
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

    const val REPORT_PROMPT = "อ่านรายงานนี้และสรุปเป็น JSON ตาม Schema ที่กำหนดเท่านั้น"

    /** Prompt for hotel PMS reports (eZee Absolute etc.): summary numbers + the main table. */
    const val REPORT_INSTRUCTION = """You read hotel PMS reports (mostly eZee Absolute/eZee FrontDesk PDF exports) for a small Thai resort.
Return ONLY one JSON object, no markdown, with this schema:
{
  "report_type": short snake_case id of the report, e.g. "night_audit", "manager_report", "expense_voucher", "city_ledger_summary", "occupancy_monthly", "no_show_report", "front_desk_activities", "yearly_statistics", "daily_revenue", or "other",
  "report_title": title exactly as printed,
  "property_name": hotel name if printed, else null,
  "report_date": main date of the report as YYYY-MM-DD (convert Buddhist year to AD, e.g. 2569 -> 2026), else null,
  "period_from": YYYY-MM-DD or null,
  "period_to": YYYY-MM-DD or null,
  "currency": e.g. "THB" or null,
  "summary": object of the key totals printed in the report, snake_case keys and plain numbers (no commas),
             e.g. total_revenue, room_revenue, other_revenue, total_payments, cash, bank_transfer, credit_card,
             occupancy_percent, rooms_sold, rooms_available, adr, revpar, total_expense, outstanding_balance.
             Only include values that are actually printed.
  "rows": array of objects for the main table of the report (max 300 rows), using the report's own column names
          as snake_case keys; numbers as plain numbers, dates as YYYY-MM-DD,
  "notes": any important remark, else null
}
Never invent values. Use null when something is not in the report."""

    /** Scales down large images (keeps text sharp) and returns base64 JPEG. */
    fun encodeBytes(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun encodeImage(bitmap: Bitmap, maxDimension: Int = 1600): String {
        val w = bitmap.width
        val h = bitmap.height
        val scaled = if (w <= maxDimension && h <= maxDimension) {
            bitmap
        } else {
            val ratio = maxDimension.toFloat() / maxOf(w, h)
            Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Strips ```json fences / extra text and keeps only the outer JSON object. */
    fun sanitizeJson(raw: String): String {
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

    fun parseDocument(jsonString: String): AccountingDocumentJson {
        return try {
            val parsed = adapter.fromJson(jsonString)
            if (parsed != null) parsed.copy(date = fixBuddhistYear(parsed.date)) else fallbackParse(jsonString)
        } catch (e: Exception) {
            Log.w(TAG, "Moshi parse failed, trying manual fallback", e)
            fallbackParse(jsonString)
        }
    }

    private fun fixBuddhistYear(date: String?): String? {
        if (date == null || date.length < 4) return date
        val year = date.substring(0, 4).toIntOrNull() ?: return date
        return if (year > 2400) "${year - 543}${date.substring(4)}" else date
    }

    private fun JSONObject.optNumber(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

    private fun JSONObject.optText(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, "").ifEmpty { null } else null

    private fun fallbackParse(jsonString: String): AccountingDocumentJson {
        val obj = JSONObject(jsonString)
        val items = mutableListOf<LineItemJson>()
        val arr = obj.optJSONArray("line_items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                items.add(
                    LineItemJson(
                        itemName = item.optText("item_name"),
                        quantity = item.optNumber("quantity"),
                        unitPrice = item.optNumber("unit_price"),
                        totalPrice = item.optNumber("total_price")
                    )
                )
            }
        }
        return AccountingDocumentJson(
            documentType = obj.optText("document_type") ?: "OTHER",
            // Unknown direction stays null so the reviewer must choose income/expense.
            transactionType = obj.optText("transaction_type"),
            documentNo = obj.optText("document_no"),
            date = fixBuddhistYear(obj.optText("date")),
            sellerName = obj.optText("seller_name"),
            sellerTaxId = obj.optText("seller_tax_id"),
            customerName = obj.optText("customer_name"),
            customerTaxId = obj.optText("customer_tax_id"),
            subtotal = obj.optNumber("subtotal"),
            vatAmount = obj.optNumber("vat_amount"),
            totalAmount = obj.optNumber("total_amount"),
            depositAmount = obj.optNumber("deposit_amount"),
            paymentMethod = obj.optText("payment_method"),
            lineItems = items
        )
    }

    /** Short, readable error text from an HTTP error body (never includes the API key). */
    fun describeHttpError(provider: String, code: Int, body: String?): String {
        val message = try {
            val root = JSONObject(body ?: "")
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        val lower = message?.lowercase() ?: ""
        val hint = when {
            code == 400 && "anthropic-workspace-id" in lower ->
                "key นี้ใช้ได้หลาย workspace ต้องใส่ Workspace ID (wrkspc_...) ในหน้าตั้งค่า"
            code == 400 && "credit balance" in lower ->
                "เครดิตในบัญชี Console หมด ต้องเติมเงินที่ Plans & Billing"
            else -> null
        } ?: when (code) {
            400 -> "คำขอไม่ถูกต้อง หรือชื่อโมเดลผิด"
            401, 403 -> "API key ไม่ถูกต้องหรือไม่มีสิทธิ์"
            404 -> "ไม่พบโมเดลนี้ ตรวจชื่อโมเดลในหน้าตั้งค่า"
            429 -> "เรียกใช้ถี่เกินไปหรือโควต้าหมด"
            in 500..599 -> "ฝั่งผู้ให้บริการขัดข้อง ลองใหม่ภายหลัง"
            else -> "เกิดข้อผิดพลาด"
        }
        return "$provider [$code] $hint" + (message?.let { " — $it" } ?: "")
    }
}
