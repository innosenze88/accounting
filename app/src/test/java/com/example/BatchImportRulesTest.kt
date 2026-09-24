package com.example

import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.looksLikeDuplicateOf
import com.example.data.local.quickVerifyProblem
import com.example.data.model.DocumentStatus
import com.example.data.network.OcrCommon
import com.example.data.model.TransactionType
import com.example.ui.viewmodel.AccountantViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatchImportRulesTest {

    private fun doc(
        id: Long = 1,
        tx: String = "EXPENSE",
        total: Double? = 1070.0,
        date: String? = "2026-09-23",
        no: String? = "INV-1",
        seller: String? = "Shop",
        status: String = DocumentStatus.PENDING.code
    ) = ExtractedDocumentEntity(
        id = id, documentType = "RECEIPT", transactionType = tx, documentNo = no, date = date,
        sellerName = seller, sellerTaxId = null, customerName = null, customerTaxId = null,
        subtotal = null, vatAmount = null, totalAmount = total, depositAmount = null,
        paymentMethod = null, lineItemsJson = "[]", rawJson = "{}", status = status
    )

    @Test
    fun `complete pending document can be approved in bulk`() {
        assertNull(doc().quickVerifyProblem())
    }

    @Test
    fun `incomplete documents must be reviewed by a person`() {
        assertNotNull(doc(tx = "UNKNOWN").quickVerifyProblem())
        assertNotNull(doc(total = null).quickVerifyProblem())
        assertNotNull(doc(total = 0.0).quickVerifyProblem())
        assertNotNull(doc(date = "23/09/2026").quickVerifyProblem())
        assertNotNull(doc(status = DocumentStatus.VERIFIED.code).quickVerifyProblem())
    }

    @Test
    fun `same number and total is flagged as duplicate`() {
        assertTrue(doc(id = 2).looksLikeDuplicateOf(doc(id = 1)))
        assertFalse(doc(id = 2, total = 50.0).looksLikeDuplicateOf(doc(id = 1)))
        assertFalse(doc(id = 1).looksLikeDuplicateOf(doc(id = 1)))
        assertFalse(doc(id = 2).looksLikeDuplicateOf(doc(id = 1, status = DocumentStatus.REJECTED.code)))
    }

    @Test
    fun `amounts written as text with commas are read`() {
        val parsed = OcrCommon.parseDocument(
            """{"document_type":"RECEIPT","transaction_type":"EXPENSE","total_amount":"1,250.00","vat_amount":"฿81.78"}"""
        )
        assertEquals(1250.0, parsed.totalAmount!!, 0.001)
        assertEquals(81.78, parsed.vatAmount!!, 0.001)
    }

    @Test
    fun `eZee report suggests income or expense and the amount to count`() {
        val revenue = AccountantViewModel.ReportPreview(
            JSONObject("""{"report_type":"night_audit","summary":{"occupancy_percent":80,"room_revenue":9000,"total_revenue":"12,500"}}"""),
            "{}"
        )
        assertEquals(TransactionType.INCOME, revenue.suggestedTransaction)
        assertEquals(12500.0, revenue.suggestedAmount(TransactionType.INCOME)!!, 0.001)
        assertFalse(revenue.numericSummary.any { it.first == "occupancy_percent" })

        val expense = AccountantViewModel.ReportPreview(
            JSONObject("""{"report_type":"expense_voucher","summary":{"total_expense":3200.5}}"""),
            "{}"
        )
        assertEquals(TransactionType.EXPENSE, expense.suggestedTransaction)
        assertEquals(3200.5, expense.suggestedAmount(TransactionType.EXPENSE)!!, 0.001)
    }
}
