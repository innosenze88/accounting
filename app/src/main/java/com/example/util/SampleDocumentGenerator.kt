package com.example.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.LineItemJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class SampleDocument(
    val id: String,
    val titleTh: String,
    val typeNameTh: String,
    val docType: String,
    val transactionType: String,
    val totalDisplay: String,
    val documentData: AccountingDocumentJson,
    val rawJson: String
)

object SampleDocumentGenerator {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(AccountingDocumentJson::class.java)

    val SAMPLES: List<SampleDocument> by lazy {
        listOf(
            createSampleReceipt(),
            createSampleUtilityBill(),
            createSamplePaymentVoucher(),
            createSampleAdvanceDeposit()
        )
    }

    private fun createSampleReceipt(): SampleDocument {
        val doc = AccountingDocumentJson(
            documentType = "RECEIPT",
            transactionType = "INCOME",
            documentNo = "INV-2026/089",
            date = "2026-09-15",
            sellerName = "บริษัท ทีพี ดิจิทัล โซลูชั่นส์ จำกัด",
            sellerTaxId = "0105558012345",
            customerName = "บริษัท สยาม พัฒนาการ จำกัด",
            customerTaxId = "0105562098765",
            subtotal = 15000.00,
            vatAmount = 1050.00,
            totalAmount = 16050.00,
            depositAmount = null,
            paymentMethod = "โอนเงิน",
            lineItems = listOf(
                LineItemJson("ค่าบริการพัฒนาระบบคลาวด์ (Cloud Dev)", 1.0, 10000.00, 10000.00),
                LineItemJson("ค่าบริการบำรุงรักษาประจำเดือน (Maintenance)", 1.0, 5000.00, 5000.00)
            )
        )
        return SampleDocument(
            id = "SAMPLE_RECEIPT",
            titleTh = "ใบเสร็จรับเงิน/ใบกำกับภาษี",
            typeNameTh = "RECEIPT (รายรับ)",
            docType = "RECEIPT",
            transactionType = "INCOME",
            totalDisplay = "16,050.00 ฿",
            documentData = doc,
            rawJson = adapter.indent("  ").toJson(doc)
        )
    }

    private fun createSampleUtilityBill(): SampleDocument {
        val doc = AccountingDocumentJson(
            documentType = "UTILITY_BILL",
            transactionType = "EXPENSE",
            documentNo = "MEA-98214432",
            date = "2026-09-12",
            sellerName = "การไฟฟ้านครหลวง (MEA)",
            sellerTaxId = "0994000164800",
            customerName = "นายกิตติศักดิ์ รัตนเรืองรอง",
            customerTaxId = "1100400892112",
            subtotal = 2150.40,
            vatAmount = 150.53,
            totalAmount = 2300.93,
            depositAmount = null,
            paymentMethod = "โอนเงิน",
            lineItems = listOf(
                LineItemJson("ค่าพลังงานไฟฟ้า (280 หน่วย)", 1.0, 1108.80, 1108.80),
                LineItemJson("ค่าบริการรายเดือน", 1.0, 38.22, 38.22),
                LineItemJson("ค่า Ft ประจำงวด", 1.0, 1003.38, 1003.38)
            )
        )
        return SampleDocument(
            id = "SAMPLE_UTILITY",
            titleTh = "บิลค่าไฟฟ้า กฟน.",
            typeNameTh = "UTILITY_BILL (รายจ่าย)",
            docType = "UTILITY_BILL",
            transactionType = "EXPENSE",
            totalDisplay = "2,300.93 ฿",
            documentData = doc,
            rawJson = adapter.indent("  ").toJson(doc)
        )
    }

    private fun createSamplePaymentVoucher(): SampleDocument {
        val doc = AccountingDocumentJson(
            documentType = "PAYMENT_VOUCHER",
            transactionType = "EXPENSE",
            documentNo = "PV-20260904",
            date = "2026-09-14",
            sellerName = "ห้างหุ้นส่วนจำกัด ออฟฟิศ ซัพพลาย แอนด์ สเตชั่นเนอรี่",
            sellerTaxId = "0123554001928",
            customerName = "แผนกบัญชีและการเงิน บจก. เทคโนเวิลด์",
            customerTaxId = "0105558123456",
            subtotal = 3200.00,
            vatAmount = 224.00,
            totalAmount = 3424.00,
            depositAmount = null,
            paymentMethod = "เงินสด",
            lineItems = listOf(
                LineItemJson("กระดาษถ่ายเอกสาร A4 80g (5 กล่อง)", 5.0, 480.00, 2400.00),
                LineItemJson("ตลับหมึกเลเซอร์สีดำ", 1.0, 800.00, 800.00)
            )
        )
        return SampleDocument(
            id = "SAMPLE_PAYMENT_VOUCHER",
            titleTh = "ใบสำคัญจ่าย (เงินสดย่อย)",
            typeNameTh = "PAYMENT_VOUCHER (รายจ่าย)",
            docType = "PAYMENT_VOUCHER",
            transactionType = "EXPENSE",
            totalDisplay = "3,424.00 ฿",
            documentData = doc,
            rawJson = adapter.indent("  ").toJson(doc)
        )
    }

    private fun createSampleAdvanceDeposit(): SampleDocument {
        val doc = AccountingDocumentJson(
            documentType = "ADVANCE_DEPOSIT",
            transactionType = "INCOME",
            documentNo = "DEP-2026-0042",
            date = "2026-09-10",
            sellerName = "บริษัท ทีพี ดีไซน์ สตูดิโอ จำกัด",
            sellerTaxId = "0105559099887",
            customerName = "หจก. อสังหาไทยแลนด์",
            customerTaxId = "0103557004411",
            subtotal = 50000.00,
            vatAmount = 3500.00,
            totalAmount = 53500.00,
            depositAmount = 53500.00,
            paymentMethod = "บัตรเครดิต",
            lineItems = listOf(
                LineItemJson("เงินมัดจำงวดแรก 30% สัญญาออกแบบตกแต่งภายใน", 1.0, 50000.00, 50000.00)
            )
        )
        return SampleDocument(
            id = "SAMPLE_ADVANCE_DEPOSIT",
            titleTh = "ใบเสร็จรับเงินมัดจำล่วงหน้า",
            typeNameTh = "ADVANCE_DEPOSIT (รายรับ)",
            docType = "ADVANCE_DEPOSIT",
            transactionType = "INCOME",
            totalDisplay = "53,500.00 ฿",
            documentData = doc,
            rawJson = adapter.indent("  ").toJson(doc)
        )
    }

    /**
     * Render realistic Thai accounting document visual bitmap
     */
    fun renderDocumentBitmap(sampleId: String): Bitmap {
        val sample = SAMPLES.find { it.id == sampleId } ?: SAMPLES[0]
        val doc = sample.documentData

        val width = 800
        val height = 1100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Clean white paper background with subtle paper tone
        canvas.drawColor(Color.rgb(252, 252, 253))

        val bgPaint = Paint().apply {
            color = Color.rgb(240, 243, 246)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(16f, 16f, width - 16f, height - 16f, bgPaint)

        // Header ribbon
        val headerColor = when (doc.documentType) {
            "RECEIPT" -> Color.rgb(27, 94, 32)
            "PAYMENT_VOUCHER" -> Color.rgb(183, 28, 28)
            "UTILITY_BILL" -> Color.rgb(13, 71, 161)
            "ADVANCE_DEPOSIT" -> Color.rgb(74, 20, 140)
            else -> Color.rgb(55, 71, 79)
        }

        val ribbonPaint = Paint().apply {
            color = headerColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(24f, 24f, width - 24f, 110f, ribbonPaint)

        val headerTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val headerSubPaint = Paint().apply {
            color = Color.rgb(220, 237, 245)
            textSize = 18f
            isAntiAlias = true
        }

        val typeTitle = when (doc.documentType) {
            "RECEIPT" -> "ใบเสร็จรับเงิน / ใบกำกับภาษี (RECEIPT)"
            "PAYMENT_VOUCHER" -> "ใบสำคัญจ่ายเงิน (PAYMENT VOUCHER)"
            "UTILITY_BILL" -> "ใบแจ้งหนี้ / บิลค่าสาธารณูปโภค (UTILITY BILL)"
            "ADVANCE_DEPOSIT" -> "ใบเสร็จรับเงินมัดจำล่วงหน้า (ADVANCE DEPOSIT)"
            else -> "เอกสารทางบัญชีและการเงิน"
        }
        canvas.drawText(typeTitle, 40f, 65f, headerTextPaint)
        canvas.drawText("ประเภททรานแซกชัน: ${if (doc.transactionType == "INCOME") "INCOME (รายรับ)" else "EXPENSE (รายจ่าย)"}", 40f, 96f, headerSubPaint)

        val textPaint = Paint().apply {
            color = Color.rgb(33, 33, 33)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }
        val boldPaint = Paint().apply {
            color = Color.rgb(20, 20, 20)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.rgb(200, 200, 200)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }

        var y = 150f

        // Document Meta (Doc No & Date)
        canvas.drawText("เลขที่เอกสาร (Doc No.):", 40f, y, boldPaint)
        canvas.drawText(doc.documentNo ?: "-", 260f, y, textPaint)
        y += 30f
        canvas.drawText("วันที่ออกบิล (Date):", 40f, y, boldPaint)
        canvas.drawText("${doc.date} (พ.ศ. 2569)", 260f, y, textPaint)
        y += 40f
        canvas.drawLine(40f, y, width - 40f, y, linePaint)
        y += 30f

        // Seller Info
        canvas.drawText("ข้อมูลผู้ออกบิล / ผู้ขาย (Seller):", 40f, y, boldPaint)
        y += 26f
        canvas.drawText("ชื่อ: ${doc.sellerName ?: "-"}", 60f, y, textPaint)
        y += 26f
        canvas.drawText("เลขประจำตัวผู้เสียภาษี (Tax ID): ${doc.sellerTaxId ?: "-"}", 60f, y, textPaint)
        y += 35f

        // Customer Info
        canvas.drawText("ข้อมูลลูกค้า / ผู้รับบริการ (Customer):", 40f, y, boldPaint)
        y += 26f
        canvas.drawText("ชื่อ / ได้รับเงินจาก: ${doc.customerName ?: "-"}", 60f, y, textPaint)
        y += 26f
        canvas.drawText("เลขประจำตัวผู้เสียภาษี (Tax ID): ${doc.customerTaxId ?: "-"}", 60f, y, textPaint)
        y += 35f
        canvas.drawLine(40f, y, width - 40f, y, linePaint)
        y += 35f

        // Line Items Table Header
        val tableHeaderPaint = Paint().apply {
            color = Color.rgb(235, 240, 245)
            style = Paint.Style.FILL
        }
        canvas.drawRect(40f, y, width - 40f, y + 36f, tableHeaderPaint)
        canvas.drawText("ลำดับ / รายการสินค้า-บริการ", 50f, y + 24f, boldPaint)
        canvas.drawText("จำนวน", 480f, y + 24f, boldPaint)
        canvas.drawText("หน่วยละ", 570f, y + 24f, boldPaint)
        canvas.drawText("รวมเงิน (บาท)", 670f, y + 24f, boldPaint)
        y += 50f

        // Line Items Rows
        doc.lineItems?.forEachIndexed { index, item ->
            canvas.drawText("${index + 1}. ${item.itemName ?: "-"}", 50f, y, textPaint)
            canvas.drawText(String.format("%.0f", item.quantity ?: 1.0), 490f, y, textPaint)
            canvas.drawText(String.format("%,.2f", item.unitPrice ?: 0.0), 570f, y, textPaint)
            canvas.drawText(String.format("%,.2f", item.totalPrice ?: 0.0), 670f, y, boldPaint)
            y += 32f
        }

        y = maxOf(y + 20f, 750f)
        canvas.drawLine(40f, y, width - 40f, y, linePaint)
        y += 35f

        // Totals Box
        val boxPaint = Paint().apply {
            color = Color.rgb(248, 249, 250)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(400f, y, width - 40f, y + 200f), 12f, 12f, boxPaint)
        val boxBorder = Paint().apply {
            color = Color.rgb(215, 220, 225)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRoundRect(RectF(400f, y, width - 40f, y + 200f), 12f, 12f, boxBorder)

        var totalY = y + 35f
        canvas.drawText("มูลค่าก่อนภาษี (Subtotal):", 420f, totalY, textPaint)
        canvas.drawText(String.format("%,.2f ฿", doc.subtotal ?: 0.0), 660f, totalY, boldPaint)
        totalY += 32f

        canvas.drawText("ภาษีมูลค่าเพิ่ม 7% (VAT):", 420f, totalY, textPaint)
        canvas.drawText(String.format("%,.2f ฿", doc.vatAmount ?: 0.0), 660f, totalY, boldPaint)
        totalY += 32f

        if (doc.depositAmount != null) {
            canvas.drawText("เงินมัดจำ (Deposit):", 420f, totalY, textPaint)
            canvas.drawText(String.format("%,.2f ฿", doc.depositAmount), 660f, totalY, boldPaint)
            totalY += 32f
        }

        canvas.drawLine(415f, totalY - 10f, width - 55f, totalY - 10f, linePaint)
        val grandTotalPaint = Paint().apply {
            color = headerColor
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText("รวมทั้งสิ้น (Total):", 420f, totalY + 20f, grandTotalPaint)
        canvas.drawText(String.format("%,.2f ฿", doc.totalAmount ?: 0.0), 640f, totalY + 20f, grandTotalPaint)

        // Payment Method & Stamp on bottom left
        val stampPaint = Paint().apply {
            color = Color.rgb(46, 125, 50)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val stampTextPaint = Paint().apply {
            color = Color.rgb(46, 125, 50)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawRoundRect(RectF(60f, y + 20f, 320f, y + 90f), 8f, 8f, stampPaint)
        canvas.drawText("✓ ชำระแล้ว (${doc.paymentMethod ?: "โอนเงิน"})", 80f, y + 62f, stampTextPaint)

        canvas.drawText("สแกนเอกสารตัวอย่าง AI Accountant OCR", 60f, y + 150f, Paint().apply {
            color = Color.GRAY
            textSize = 14f
            isAntiAlias = true
        })

        return bitmap
    }
}
