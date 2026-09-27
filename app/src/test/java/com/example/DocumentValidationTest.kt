package com.example

import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.quickVerifyProblem
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
}
