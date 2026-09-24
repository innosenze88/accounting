package com.example

import com.example.data.ezee.ReportTemplate
import com.example.data.ezee.TemplateEngine
import com.example.data.ezee.TemplateField
import com.example.data.ezee.TemplateTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for user-made readers ("ตัวอ่านที่สร้างเอง"). Made-up data. */
class ReportTemplateTest {

    private fun pages(vararg page: String): List<List<String>> = page.map { it.trimIndent().lines() }

    /** A new report layout the app does not know yet. */
    private fun cashierReport(cash: String, transfer: String, total: String, date: String) = pages(
        """
        Demo Resort   Cashier Shift Report
        Shift Date   $date   Cashier   admin
        Collections
        Cash   $cash
        Bank Transfer   $transfer
        Total   $total
        Refunds
        Cash   0.00
        Total   0.00
        Items
        Room 101   1   1,200.00
        Room 102   2   2,400.00
        Total   3   3,600.00
        """
    )

    private val template = ReportTemplate(
        id = "t1",
        name = "Cashier Shift",
        matchText = "Cashier Shift Report",
        dateAnchor = "Shift Date",
        fields = listOf(
            TemplateField("cash", "เงินสด", anchor = "Cash", index = 0, heading = "Collections", occurrence = 0),
            TemplateField("bank_transfer", "โอน", anchor = "Bank Transfer", index = 0),
            TemplateField("total", "รับเงินรวม", anchor = "Total", index = 0, heading = "Collections", occurrence = 0),
            TemplateField("refund_total", "คืนเงินรวม", anchor = "Total", index = 0, heading = "Refunds", occurrence = 1)
        ),
        tables = listOf(TemplateTable("items", heading = "Items", numberCount = 2, columns = listOf("nights", "amount"))),
        countKey = "total",
        countAs = "INCOME",
        createdAt = 1L
    )

    @Test
    fun templateReadsANewFileWithDifferentNumbers() {
        val file = cashierReport("1,500.00", "2,100.00", "3,600.00", "14/09/2569")
        assertTrue(TemplateEngine.matches(template, file, "shift.pdf"))
        val r = TemplateEngine.apply(template, file)
        assertEquals("custom_cashier_shift", r.type)
        assertEquals("2026-09-14", r.reportDate)
        assertEquals(1500.0, r.summary["cash"]!!, 0.001)
        assertEquals(2100.0, r.summary["bank_transfer"]!!, 0.001)
        assertEquals(3600.0, r.summary["total"]!!, 0.001)
        assertEquals(0.0, r.summary["refund_total"]!!, 0.001)
        assertEquals("รับเงินรวม", r.labels["total"])
        assertEquals("INCOME", r.suggestedTransaction)
        assertEquals(3600.0, r.suggestedAmount!!, 0.001)
        assertTrue(r.countByDefault)
        assertEquals(2, r.rows.size)
        assertEquals(2400.0, r.rows[1]["amount"] as Double, 0.001)
        assertTrue(r.checks.all { it.ok })
    }

    @Test
    fun missingLineIsReportedNotInvented() {
        val file = pages(
            """
            Demo Resort   Cashier Shift Report
            Shift Date   15/09/2026
            Collections
            Cash   500.00
            Total   500.00
            """
        )
        val r = TemplateEngine.apply(template, file)
        assertNull(r.summary["bank_transfer"])
        assertTrue(r.notes.first().contains("โอน"))
        assertTrue(r.checks.any { !it.ok })
    }

    @Test
    fun otherReportsDoNotMatch() {
        val other = pages("Demo Resort   Night Audit\nAs On Date   13/09/2026")
        assertFalse(TemplateEngine.matches(template, other, "nightaudit.pdf"))
        assertNull(TemplateEngine.find(listOf(template), other, "nightaudit.pdf"))
    }

    @Test
    fun newestTemplateWins() {
        val file = cashierReport("1.00", "2.00", "3.00", "14/09/2026")
        val newer = template.copy(id = "t2", name = "Cashier Shift v2", createdAt = 2L)
        assertEquals("t2", TemplateEngine.find(listOf(template, newer), file, null)?.id)
    }

    @Test
    fun analyzeGivesHeadingsNumbersAndSuggestions() {
        val file = cashierReport("1,500.00", "2,100.00", "3,600.00", "14/09/2026")
        val lines = TemplateEngine.analyze(file)
        val secondTotal = lines.filter { it.label == "Total" }[1]
        assertEquals("Refunds", secondTotal.heading)
        assertEquals(1, secondTotal.occurrence)
        assertEquals(listOf("1,500.00"), lines.first { it.label == "Cash" }.numberTexts)
        assertEquals("Cashier Shift Report", TemplateEngine.suggestMatchText(file))
        assertEquals("Shift Date", TemplateEngine.suggestDateAnchor(lines))
        assertEquals("cash_1", TemplateEngine.newKey("Cash", listOf("cash")))
        assertEquals("field_1", TemplateEngine.newKey("เงินสด", emptyList()))
    }

    @Test
    fun otherDateFormats() {
        assertEquals("2026-09-14", TemplateEngine.parseDate("Date: 2026-09-14"))
        assertEquals("2026-09-14", TemplateEngine.parseDate("14-09-2026"))
        assertEquals("2026-09-14", TemplateEngine.parseDate("14.09.2569"))
        assertNull(TemplateEngine.parseDate("no date"))
    }
}
