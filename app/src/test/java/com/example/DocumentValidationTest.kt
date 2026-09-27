package com.example

import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.quickVerifyProblem
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.DocumentValidation
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentValidationTest {
    private fun pending(
        transactionType: String = "EXPENSE",
        date: String? = "2026-09-28",
        total: Double? = 100.0
    ) = ExtractedDocumentEntity(
        documentType = "RECEIPT",
        transactionType = transactionType,
        documentNo = null,
        date = date,
        sellerName = null,
        sellerTaxId = null,
        customerName = null,
        customerTaxId = null,
        subtotal = null,
        vatAmount = null,
        totalAmount = total,
        depositAmount = null,
        paymentMethod = null,
        lineItemsJson = "[]",
        rawJson = "{}"
    )

    @Test
    fun unknownTransactionTypeCannotBeBulkVerified() {
        assertEquals("ยังไม่รู้ว่ารายรับหรือรายจ่าย", pending(transactionType = "").quickVerifyProblem())
    }

    @Test
    fun impossibleCalendarDateCannotBeBulkVerified() {
        assertEquals("วันที่ไม่ถูกต้อง", pending(date = "2026-02-31").quickVerifyProblem())
    }

    @Test
    fun nonFiniteAmountCannotBeBulkVerified() {
        assertEquals("ยอดรวมไม่ถูกต้อง", pending(total = Double.POSITIVE_INFINITY).quickVerifyProblem())
    }

    private fun problems(
        tx: String? = "INCOME", date: String? = "2026-09-28", subtotal: Double? = null,
        vat: Double? = null, total: Double? = 100.0, deposit: Double? = null
    ) = DocumentValidation.problems(tx, date, subtotal, vat, total, deposit)

    @Test
    fun validDocumentHasNoProblems() {
        assertTrue(problems(subtotal = 93.46, vat = 6.54, deposit = 0.0).isEmpty())
        assertTrue(problems(date = null).isEmpty())
        assertTrue(problems(date = "2024-02-29").isEmpty()) // leap day
    }

    @Test
    fun everyRuleIsChecked() {
        assertEquals(listOf("ยอดรวมเป็น 0"), problems(total = 0.0))
        assertEquals(listOf("ยอดรวมไม่ถูกต้อง"), problems(total = -5.0))
        assertEquals(listOf("ยอดรวมไม่ถูกต้อง"), problems(total = Double.NaN))
        assertEquals(listOf("VAT ไม่ใช่ตัวเลข"), problems(vat = Double.NEGATIVE_INFINITY))
        assertEquals(listOf("มูลค่าก่อน VAT ต้องไม่ติดลบ"), problems(subtotal = -1.0))
        assertEquals(listOf("มัดจำ ต้องไม่ติดลบ"), problems(deposit = -1.0))
        assertEquals(listOf("วันที่ไม่ถูกต้อง"), problems(date = "2025-02-29"))
        assertEquals(listOf("วันที่ไม่ถูกต้อง"), problems(date = "28/09/2026"))
        assertEquals(listOf("ปีเป็น พ.ศ. — ต้องเป็น ค.ศ."), problems(date = "2569-09-28"))
    }

    @Test
    fun formAndBulkApprovalUseTheSameRules() {
        val doc = AccountingDocumentJson(transactionType = "EXPENSE", date = "2026-09-28", totalAmount = 50.0, vatAmount = -3.0)
        assertEquals("VAT ต้องไม่ติดลบ", DocumentValidation.problems(doc).first())
        assertEquals("VAT ต้องไม่ติดลบ", pending().copy(vatAmount = -3.0).quickVerifyProblem())
    }
}
