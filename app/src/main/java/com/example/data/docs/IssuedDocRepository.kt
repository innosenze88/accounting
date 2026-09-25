package com.example.data.docs

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.settings.BusinessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What to put on a new document (numbers and VAT are worked out here). */
data class DocRequest(
    val type: IssuedDocType,
    val issueDate: String,
    val customerName: String,
    val customerAddress: String? = null,
    val customerTaxId: String? = null,
    val lines: List<DocLine>,
    val bookingId: Long? = null,
    val paymentId: Long? = null,
    val paymentText: String? = null,
    val note: String? = null,
    val qrPayload: String? = null,
    val extraRows: List<Pair<String, String>> = emptyList()
)

/**
 * Issues documents with running numbers that never repeat, keeps their PDF,
 * voids them with a reason (never deletes) and makes copies marked "สำเนา".
 */
class IssuedDocRepository(context: Context, private val db: AppDatabase) {

    private val dao = db.issuedDocDao()
    private val writer = DocumentPdfWriter()
    private val dir = File(context.applicationContext.filesDir, "issued_documents")
    private val shareDir = File(context.applicationContext.cacheDir, "share")

    val all: Flow<List<IssuedDocumentEntity>> = dao.observeAll()

    suspend fun forBooking(bookingId: Long) = dao.forBooking(bookingId)
    suspend fun get(id: Long) = dao.get(id)

    /** VAT shows on sales documents only when the resort is VAT registered. */
    private fun vatFor(type: IssuedDocType, business: BusinessSettings): Double = when (type) {
        IssuedDocType.RECEIPT, IssuedDocType.TAX_INVOICE, IssuedDocType.INVOICE, IssuedDocType.QUOTATION ->
            if (business.vatRegistered) business.vatRate else 0.0
        else -> 0.0
    }

    suspend fun issue(req: DocRequest, business: BusinessSettings): IssuedDocumentEntity {
        if (req.type == IssuedDocType.TAX_INVOICE) {
            check(business.canIssueTaxInvoice) {
                "ออกใบกำกับภาษีไม่ได้: ต้องจดทะเบียน VAT และใส่ชื่อ + เลขผู้เสียภาษี 13 หลักในตั้งค่ารีสอร์ทก่อน"
            }
            check(req.customerName.isNotBlank()) { "ใบกำกับภาษีต้องมีชื่อผู้ซื้อ" }
        }
        require(req.lines.isNotEmpty() || req.type == IssuedDocType.GUEST_CARD) { "ไม่มีรายการ" }
        val rate = vatFor(req.type, business)
        val amounts = VatMath.split(req.lines.sumOf { it.amount }, rate, business.pricesIncludeVat)

        val saved = db.withTransaction {
            val key = DocNumbering.counterKey(req.type, req.issueDate)
            val seq = (dao.getSequence(key)?.lastNo ?: 0) + 1
            if (seq == 1) dao.insertSequence(DocSequenceEntity(key, 1)) else dao.updateSequence(DocSequenceEntity(key, seq))
            val entity = IssuedDocumentEntity(
                type = req.type.code,
                number = DocNumbering.format(req.type, req.issueDate, seq),
                issueDate = req.issueDate,
                bookingId = req.bookingId,
                paymentId = req.paymentId,
                customerName = req.customerName.trim(),
                customerAddress = req.customerAddress?.trim()?.ifBlank { null },
                customerTaxId = req.customerTaxId?.trim()?.ifBlank { null },
                itemsJson = printJson(req, business, rate).toString(),
                subtotal = amounts.subtotal,
                vatAmount = amounts.vat,
                total = amounts.total,
                note = req.note
            )
            entity.copy(id = dao.insert(entity))
        }
        val pdf = File(dir, "${saved.number}.pdf")
        withContext(Dispatchers.IO) { writer.write(content(saved, copy = false), pdf) }
        val withPdf = saved.copy(pdfPath = pdf.absolutePath)
        dao.update(withPdf)
        return withPdf
    }

    /** Cancels a document: the number stays used, the PDF gets a "ยกเลิก" stamp. */
    suspend fun void(id: Long, reason: String): IssuedDocumentEntity {
        require(reason.isNotBlank()) { "ต้องใส่เหตุผล" }
        val d = requireNotNull(dao.get(id)) { "ไม่พบเอกสาร" }
        check(d.isActive) { "เอกสารนี้ยกเลิกไปแล้ว" }
        val voided = d.copy(voidedAt = System.currentTimeMillis(), voidReason = reason.trim())
        dao.update(voided)
        d.pdfPath?.let { path -> withContext(Dispatchers.IO) { writer.write(content(voided, copy = false), File(path)) } }
        return voided
    }

    /**
     * File to share/print. The first time: the original. After that: a copy marked "สำเนา".
     */
    suspend fun fileForSharing(id: Long): File {
        val d = requireNotNull(dao.get(id)) { "ไม่พบเอกสาร" }
        val original = d.pdfPath?.let { File(it) }?.takeIf { it.exists() }
        if (d.copies == 0 && original != null) {
            dao.update(d.copy(copies = 1))
            return original
        }
        val out = File(shareDir, "${d.number}-copy.pdf")
        withContext(Dispatchers.IO) { writer.write(content(d, copy = true), out) }
        dao.update(d.copy(copies = d.copies + 1))
        return out
    }

    /** Monthly guest register (PDF in the share folder). */
    suspend fun registerPdf(business: BusinessSettings, yearMonth: String, bookings: List<com.example.data.booking.BookingEntity>): File {
        val out = File(shareDir, "guest-register-$yearMonth.pdf")
        withContext(Dispatchers.IO) { writer.writeRegister(business, yearMonth, bookings, out) }
        return out
    }

    // ------------------------------------------------------------------ stored print data

    private fun printJson(req: DocRequest, business: BusinessSettings, rate: Double): JSONObject = JSONObject().apply {
        put("lines", JSONArray().apply {
            req.lines.forEach { l ->
                put(JSONObject().put("description", l.description).put("qty", l.qty).put("unitPrice", l.unitPrice).put("amount", l.amount))
            }
        })
        put("paymentText", req.paymentText ?: "")
        put("qr", req.qrPayload ?: "")
        put("extraRows", JSONArray().apply { req.extraRows.forEach { (k, v) -> put(JSONArray().put(k).put(v)) } })
        // Snapshot of the resort details at the time of issue (a later address change does not alter old documents).
        put("business", business.toJson())
        put("vatRate", rate)
    }

    private fun content(d: IssuedDocumentEntity, copy: Boolean): DocContent {
        val o = JSONObject(d.itemsJson)
        val lines = o.optJSONArray("lines") ?: JSONArray()
        val rows = o.optJSONArray("extraRows") ?: JSONArray()
        val rate = o.optDouble("vatRate", 0.0)
        return DocContent(
            type = d.docType ?: IssuedDocType.RECEIPT,
            number = d.number,
            issueDate = d.issueDate,
            business = BusinessSettings.fromJson(o.optJSONObject("business") ?: JSONObject()),
            customerName = d.customerName,
            customerAddress = d.customerAddress,
            customerTaxId = d.customerTaxId,
            lines = (0 until lines.length()).map { i ->
                val l = lines.getJSONObject(i)
                DocLine(l.optString("description"), l.optDouble("qty", 1.0), l.optDouble("unitPrice"), l.optDouble("amount"))
            },
            amounts = VatSplit(d.subtotal, d.vatAmount, d.total),
            showVat = rate > 0,
            vatRate = rate,
            paymentText = o.optString("paymentText").ifBlank { null },
            note = d.note,
            qrPayload = o.optString("qr").ifBlank { null },
            extraRows = (0 until rows.length()).map { i -> rows.getJSONArray(i).let { it.optString(0) to it.optString(1) } },
            copy = copy,
            voided = !d.isActive,
            voidReason = d.voidReason
        )
    }
}
