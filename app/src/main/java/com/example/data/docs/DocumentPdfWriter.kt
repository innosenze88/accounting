package com.example.data.docs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.booking.BookingEntity
import com.example.data.payment.QrImage
import com.example.data.settings.BusinessSettings
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** One line of a document. */
data class DocLine(val description: String, val qty: Double, val unitPrice: Double, val amount: Double)

/** Everything printed on a document. */
data class DocContent(
    val type: IssuedDocType,
    val number: String,
    val issueDate: String,
    val business: BusinessSettings,
    val customerName: String,
    val customerAddress: String?,
    val customerTaxId: String?,
    val lines: List<DocLine>,
    val amounts: VatSplit,
    val showVat: Boolean,
    val vatRate: Double,
    /** e.g. "ชำระโดย: โอน วันที่ 25 ก.ย. 2569" */
    val paymentText: String? = null,
    val note: String? = null,
    /** PromptPay payload to print as a QR code (invoices). */
    val qrPayload: String? = null,
    /** Extra label/value rows (booking details, guest register fields). */
    val extraRows: List<Pair<String, String>> = emptyList(),
    val copy: Boolean = false,
    val voided: Boolean = false,
    val voidReason: String? = null
)

/**
 * Draws documents as A4 PDF files on the phone (Android PdfDocument, Thai text via the system font).
 * No internet and no paid service needed.
 */
class DocumentPdfWriter {

    private val pageW = 595 // A4 portrait, points
    private val pageH = 842
    private val margin = 40f

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        this.color = color
    }

    /** Draws [text] wrapped to [width] at (x, y); returns the height used. */
    private fun Canvas.text(
        text: String, x: Float, y: Float, width: Float, size: Float,
        bold: Boolean = false, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, color: Int = Color.BLACK
    ): Float {
        if (text.isEmpty()) return 0f
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint(size, bold, color), width.toInt().coerceAtLeast(10))
            .setAlignment(align)
            .setIncludePad(false)
            .build()
        save()
        translate(x, y)
        layout.draw(this)
        restore()
        return layout.height.toFloat()
    }

    private fun money(v: Double) = String.format(Locale.US, "%,.2f", v)

    private fun qtyText(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    /** Writes a receipt / invoice / quotation / tax invoice / voucher / guest card to [out]. */
    fun write(c: DocContent, out: File) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        val cv = page.canvas
        val contentW = pageW - margin * 2
        var y = margin

        // ---- header: resort (left) and document title (right)
        val leftW = contentW * 0.58f
        val rightX = margin + leftW + 10
        val rightW = contentW - leftW - 10
        var ly = y
        val b = c.business
        ly += cv.text(b.name.ifBlank { "(ตั้งชื่อรีสอร์ทในหน้า จอง → ตั้งค่ารีสอร์ท)" }, margin, ly, leftW, 15f, bold = true) + 2
        if (c.type == IssuedDocType.TAX_INVOICE && b.branch.isNotBlank()) ly += cv.text(b.branch, margin, ly, leftW, 9.5f) + 1
        if (b.address.isNotBlank()) ly += cv.text(b.address, margin, ly, leftW, 9.5f) + 1
        if (b.taxId.isNotBlank()) ly += cv.text("เลขประจำตัวผู้เสียภาษี ${b.taxId}", margin, ly, leftW, 9.5f) + 1
        if (b.phone.isNotBlank()) ly += cv.text("โทร ${b.phone}", margin, ly, leftW, 9.5f) + 1

        var ry = y
        ry += cv.text(c.type.titleTh, rightX, ry, rightW, 16f, bold = true, align = Layout.Alignment.ALIGN_OPPOSITE) + 1
        ry += cv.text(c.type.titleEn, rightX, ry, rightW, 9f, align = Layout.Alignment.ALIGN_OPPOSITE, color = Color.DKGRAY) + 6
        if (c.type != IssuedDocType.GUEST_CARD || c.number.isNotBlank()) {
            ry += cv.text("เลขที่  ${c.number}", rightX, ry, rightW, 10.5f, bold = true, align = Layout.Alignment.ALIGN_OPPOSITE) + 2
        }
        ry += cv.text("วันที่  ${ThaiDate.long(c.issueDate)}", rightX, ry, rightW, 10.5f, align = Layout.Alignment.ALIGN_OPPOSITE) + 2
        if (c.copy) {
            ry += 4
            val stamp = RectF(pageW - margin - 70, ry, pageW - margin, ry + 22)
            cv.drawRect(stamp, Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(198, 40, 40); strokeWidth = 1.5f })
            cv.text("สำเนา", stamp.left, stamp.top + 3, stamp.width(), 12f, bold = true, align = Layout.Alignment.ALIGN_CENTER, color = Color.rgb(198, 40, 40))
            ry += 26
        } else if (c.type == IssuedDocType.RECEIPT || c.type == IssuedDocType.TAX_INVOICE) {
            ry += cv.text("ต้นฉบับ", rightX, ry, rightW, 9f, align = Layout.Alignment.ALIGN_OPPOSITE, color = Color.DKGRAY) + 2
        }
        y = maxOf(ly, ry) + 10
        cv.drawLine(margin, y, pageW - margin, y, Paint().apply { color = Color.GRAY; strokeWidth = 0.8f })
        y += 10

        // ---- customer
        if (c.type == IssuedDocType.GUEST_CARD) {
            y += drawRows(cv, c.extraRows, y, contentW) + 10
        } else {
            val who = if (c.type == IssuedDocType.PAYMENT_VOUCHER) "จ่ายให้" else "ลูกค้า"
            y += cv.text("$who:  ${c.customerName}", margin, y, contentW, 11f, bold = true) + 2
            c.customerAddress?.takeIf { it.isNotBlank() }?.let { y += cv.text("ที่อยู่:  $it", margin, y, contentW, 10f) + 2 }
            c.customerTaxId?.takeIf { it.isNotBlank() }?.let { y += cv.text("เลขประจำตัวผู้เสียภาษี:  $it", margin, y, contentW, 10f) + 2 }
            if (c.extraRows.isNotEmpty()) {
                y += 4
                y += drawRows(cv, c.extraRows, y, contentW)
            }
            y += 12

            // ---- items table
            val cols = floatArrayOf(28f, 0f, 48f, 80f, 90f) // no, description (rest), qty, unit price, amount
            cols[1] = contentW - cols[0] - cols[2] - cols[3] - cols[4]
            val headers = listOf("ลำดับ", "รายการ", "จำนวน", "ราคา/หน่วย", "จำนวนเงิน")
            val rowPaint = Paint().apply { color = Color.rgb(236, 239, 241) }
            cv.drawRect(margin, y, pageW - margin, y + 20, rowPaint)
            var x = margin
            headers.forEachIndexed { i, h ->
                val align = if (i >= 2) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                cv.text(h, x + 4, y + 4, cols[i] - 8, 9.5f, bold = true, align = align)
                x += cols[i]
            }
            y += 22
            c.lines.forEachIndexed { idx, l ->
                var cx = margin
                val cells = listOf((idx + 1).toString(), l.description, qtyText(l.qty), money(l.unitPrice), money(l.amount))
                var h = 0f
                cells.forEachIndexed { i, t ->
                    val align = if (i >= 2) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                    h = maxOf(h, cv.text(t, cx + 4, y + 3, cols[i] - 8, 10f, align = align))
                    cx += cols[i]
                }
                y += h + 8
                cv.drawLine(margin, y, pageW - margin, y, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f })
            }
            y += 8

            // ---- totals
            val labelX = pageW - margin - 250f
            fun totalRow(label: String, value: String, bold: Boolean = false) {
                cv.text(label, labelX, y, 150f, 10.5f, bold = bold)
                cv.text(value, labelX + 150, y, 100f, 10.5f, bold = bold, align = Layout.Alignment.ALIGN_OPPOSITE)
                y += 17
            }
            if (c.showVat) {
                totalRow("มูลค่าก่อนภาษีมูลค่าเพิ่ม", money(c.amounts.subtotal))
                totalRow("ภาษีมูลค่าเพิ่ม ${qtyText(c.vatRate)}%", money(c.amounts.vat))
            }
            totalRow("รวมทั้งสิ้น (บาท)", money(c.amounts.total), bold = true)
            y += cv.text("(${DocNumbering.bahtText(c.amounts.total)})", margin, y, contentW, 10f, align = Layout.Alignment.ALIGN_OPPOSITE) + 8
        }

        c.paymentText?.takeIf { it.isNotBlank() }?.let { y += cv.text(it, margin, y, contentW, 10.5f) + 4 }
        c.note?.takeIf { it.isNotBlank() }?.let { y += cv.text("หมายเหตุ: $it", margin, y, contentW, 10f, color = Color.DKGRAY) + 4 }

        // ---- PromptPay QR (invoices / quotations)
        c.qrPayload?.let { payload ->
            val size = 130
            val qr = QrImage.bitmap(payload, size * 3)
            val dst = RectF(margin, y + 4, margin + size, y + 4 + size)
            cv.drawBitmap(qr, null, dst, null)
            cv.text(
                "สแกนจ่ายด้วยแอปธนาคาร (พร้อมเพย์)\nยอด ${money(c.amounts.total)} บาท\nพร้อมเพย์: ${b.promptPayId}",
                margin + size + 12, y + 30, contentW - size - 12, 10.5f
            )
            y += size + 14
        }

        // ---- signatures
        val signY = maxOf(y + 40, pageH - margin - 110)
        val (leftSign, rightSign) = when (c.type) {
            IssuedDocType.PAYMENT_VOUCHER -> "ผู้รับเงิน" to "ผู้จ่ายเงิน"
            IssuedDocType.INVOICE, IssuedDocType.QUOTATION -> "ผู้รับเอกสาร" to "ผู้ออกเอกสาร"
            IssuedDocType.GUEST_CARD -> "ลายมือชื่อผู้พัก" to "เจ้าหน้าที่"
            else -> "ผู้จ่ายเงิน" to "ผู้รับเงิน"
        }
        val half = contentW / 2
        listOf(leftSign to margin, rightSign to margin + half).forEach { (label, sx) ->
            cv.drawLine(sx + 30, signY + 30, sx + half - 30, signY + 30, Paint().apply { color = Color.GRAY; strokeWidth = 0.8f })
            cv.text(label, sx, signY + 36, half, 10f, align = Layout.Alignment.ALIGN_CENTER)
            cv.text("วันที่ ........ / ........ / ........", sx, signY + 52, half, 9f, align = Layout.Alignment.ALIGN_CENTER, color = Color.DKGRAY)
        }
        if (b.footer.isNotBlank()) {
            cv.text(b.footer, margin, pageH - margin - 14, contentW, 9f, align = Layout.Alignment.ALIGN_CENTER, color = Color.DKGRAY)
        }

        // ---- VOID stamp
        if (c.voided) {
            val red = Color.argb(150, 198, 40, 40)
            cv.save()
            cv.rotate(-30f, pageW / 2f, pageH / 2f)
            cv.text("ยกเลิก", 0f, pageH / 2f - 60, pageW.toFloat(), 90f, bold = true, align = Layout.Alignment.ALIGN_CENTER, color = red)
            cv.restore()
            c.voidReason?.let {
                cv.text("ยกเลิกเอกสาร: $it", margin, pageH - margin - 30, contentW, 10f, bold = true, color = Color.rgb(198, 40, 40))
            }
        }

        doc.finishPage(page)
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }

    private fun drawRows(cv: Canvas, rows: List<Pair<String, String>>, top: Float, width: Float): Float {
        var y = top
        val labelW = 150f
        rows.forEach { (k, v) ->
            val h1 = cv.text(k, margin, y, labelW, 10.5f, bold = true)
            val h2 = cv.text(v.ifBlank { "-" }, margin + labelW, y, width - labelW, 10.5f)
            y += maxOf(h1, h2) + 5
        }
        return y - top
    }

    /**
     * Monthly guest register: one row per direct booking that stayed in [yearMonth] (landscape A4, several pages).
     */
    fun writeRegister(business: BusinessSettings, yearMonth: String, bookings: List<BookingEntity>, out: File) {
        val w = 842
        val h = 595
        val doc = PdfDocument()
        val headers = listOf("ที่", "ชื่อ-สกุล", "สัญชาติ", "เลขบัตร/พาสปอร์ต", "ที่อยู่", "มาจาก", "จะไป", "ห้อง", "วันเข้า", "วันออก", "คน")
        val widths = floatArrayOf(24f, 120f, 60f, 95f, 150f, 65f, 65f, 36f, 62f, 62f, 23f)
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            page = p
            val cv = p.canvas
            y = margin
            y += cv.text("ทะเบียนผู้พัก — ${business.name}", margin, y, w - margin * 2, 14f, bold = true) + 2
            y += cv.text("ประจำเดือน ${ThaiDate.month(yearMonth)}   (หน้า $pageNo)", margin, y, w - margin * 2, 10f) + 8
            cv.drawRect(margin, y, w - margin, y + 20, Paint().apply { color = Color.rgb(236, 239, 241) })
            var x = margin
            headers.forEachIndexed { i, t -> cv.text(t, x + 2, y + 4, widths[i] - 4, 8.5f, bold = true); x += widths[i] }
            y += 22
        }
        newPage()
        bookings.forEachIndexed { i, bk ->
            val cells = listOf(
                (i + 1).toString(), bk.guestName, bk.nationality.orEmpty(), bk.idNumber.orEmpty(), bk.address.orEmpty(),
                bk.comeFrom.orEmpty(), bk.goTo.orEmpty(), bk.roomNo, ThaiDate.short(bk.checkIn), ThaiDate.short(bk.checkOut), bk.guests.toString()
            )
            // Estimate the row height first so a row never splits across pages.
            val rowH = cells.mapIndexed { j, t ->
                StaticLayout.Builder.obtain(t, 0, t.length, paint(8.5f), (widths[j] - 4).toInt().coerceAtLeast(10)).build().height
            }.maxOrNull()?.toFloat() ?: 12f
            if (y + rowH + 8 > h - margin) newPage()
            val cv = page!!.canvas
            var x = margin
            cells.forEachIndexed { j, t -> cv.text(t, x + 2, y + 2, widths[j] - 4, 8.5f); x += widths[j] }
            y += rowH + 6
            cv.drawLine(margin, y, w - margin, y, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f })
        }
        if (bookings.isEmpty()) page!!.canvas.text("ไม่มีผู้พักจากการจองตรงในเดือนนี้", margin, y + 6, w - margin * 2, 10f)
        page?.let { doc.finishPage(it) }
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}

/** Thai dates for documents (Buddhist year). */
object ThaiDate {
    private val MONTHS = listOf("มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม")
    private val SHORT = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

    /** "2026-09-25" -> "25 กันยายน 2569" */
    fun long(iso: String): String = runCatching {
        "${iso.substring(8, 10).toInt()} ${MONTHS[iso.substring(5, 7).toInt() - 1]} ${iso.take(4).toInt() + 543}"
    }.getOrDefault(iso)

    /** "2026-09-25" -> "25 ก.ย. 69" */
    fun short(iso: String): String = runCatching {
        "${iso.substring(8, 10).toInt()} ${SHORT[iso.substring(5, 7).toInt() - 1]} ${(iso.take(4).toInt() + 543) % 100}"
    }.getOrDefault(iso)

    /** "2026-09" -> "กันยายน 2569" */
    fun month(ym: String): String = runCatching {
        "${MONTHS[ym.substring(5, 7).toInt() - 1]} ${ym.take(4).toInt() + 543}"
    }.getOrDefault(ym)
}
