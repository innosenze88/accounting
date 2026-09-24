package com.example

import com.example.data.ezee.EzeeReportParser
import com.example.data.ezee.PdfGlyph
import com.example.data.ezee.PdfLineBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the built-in eZee report reader.
 * The text below has the same layout as real eZee Absolute PDFs (columns separated by 3 spaces),
 * but names and numbers are made up.
 */
class EzeeReportParserTest {

    private fun pages(vararg page: String): List<List<String>> = page.map { it.trimIndent().lines() }

    private val managerReport = pages(
        """
        Demo Resort   Manager Report
        As on Date   13/09/2026   PTD   01/09/2026   YTD   01/01/2026   Currency   THB
        Particulars   Today ( THB )   PTD ( THB )   YTD ( THB )
        Room Charges
        Room Charges   2,000.00   50,000.00   1,000,000.00   0
        Cancellation Revenue   0.00   1,000.00   1,000.00   0
        No Show Revenue   500.00   1,500.00   1,500.00   0
        Total   2,500.00   52,500.00   1,002,500.00
        Tax
        Service Charge   50.00   200.00   200.00   2
        VAT   40.00   150.00   150.00   2
        Total   90.00   350.00   350.00
        Total Revenue without Tax   2,500.00   52,500.00   1,002,500.00
        Total Revenue with Tax   2,590.00   52,850.00   1,002,850.00
        POS to PMS Posting   0.00   0.00   0.00
        Paid Outs
        ค่าไฟฟ้า   0.00   0.00   5,000.00
        ค่าน้ำ   100.00   100.00   1,000.00
        Total   100.00   100.00   6,000.00
        Payment
        MOBILE BANKING   1,000.00   20,000.00   50,000.00
        Cash   500.00   2,000.00   100,000.00
        Total Payment   1,500.00   22,000.00   150,000.00
        City Ledger
        Closing Balance   40,000.00   40,000.00   40,000.00   Variable1   Variable1
        Advance Deposit Ledger
        Closing Balance   1,500.00
        Page 1 of 2
        """,
        """
        Demo Resort   Manager Report
        As on Date   13/09/2026   PTD   01/09/2026   YTD   01/01/2026   Currency   THB
        Guest Ledger
        Closing Balance*   12,000.00
        Room Summary
        Sold Room   2   105   1264.5
        No of Guest (Adult/Child)   5/0   230/0   2683/10
        Complimentary Room |   0 | 0   0 | 0   8 | 0
        Statistics
        Occupancy Rate(%)   13.33   53.85   38.14
        Average Daily Rate(ADR)   1,250.00   857.68   891.90
        Page 2 of 2
        """
    )

    @Test
    fun managerReport_readsTodayTotalsAndChecks() {
        val r = EzeeReportParser.parse(managerReport, "manager_report_X_20260913.pdf")
        assertNotNull(r)
        r!!
        assertEquals("manager_report", r.type)
        assertEquals("Demo Resort", r.propertyName)
        assertEquals("2026-09-13", r.reportDate)
        assertEquals("2026-09-01", r.periodFrom)
        assertEquals(2590.0, r.summary["total_revenue_with_tax"]!!, 0.001)
        assertEquals(100.0, r.summary["paid_outs_total"]!!, 0.001)
        assertEquals(1000.0, r.summary["payment_mobile_banking"]!!, 0.001)
        assertEquals(1500.0, r.summary["total_payment"]!!, 0.001)
        // "Variable1" template text next to the number must not break reading.
        assertEquals(40000.0, r.summary["city_ledger_closing"]!!, 0.001)
        assertEquals(12000.0, r.summary["guest_ledger_closing"]!!, 0.001)
        assertEquals(13.33, r.summary["occupancy_percent"]!!, 0.001)
        assertEquals(6000.0, r.summary["ytd_paid_outs_total"]!!, 0.001)
        assertEquals("INCOME", r.suggestedTransaction)
        assertEquals(2590.0, r.suggestedAmount!!, 0.001)
        assertTrue(r.countByDefault)
        assertTrue(r.checks.isNotEmpty())
        assertTrue(r.checks.joinToString { it.label }, r.checks.all { it.ok })
        // Thai paid-out categories are kept as rows.
        assertTrue(r.rows.any { it["particular"] == "ค่าน้ำ" && it["today"] == 100.0 })
    }

    @Test
    fun managerReport_wrongTotalFailsCheck() {
        val broken = managerReport.map { page -> page.map { it.replace("Total   90.00", "Total   95.00") } }
        val r = EzeeReportParser.parse(broken, null)!!
        assertTrue(r.checks.any { !it.ok })
    }

    @Test
    fun nightAudit_readsDailySalesAndRooms() {
        val r = EzeeReportParser.parse(
            pages(
                """
                Demo Resort   Night Audit
                As On Date   13/09/2026   THB
                Room Charges
                11 - Deluxe   1179   Guest A   13/09/2026 Room Only   1,680.00   1,680.00   0.00   1,680.00   0.00   admin
                Triple Room With
                4 - Superior   1166   Guest B   Expedia   13/09/2026 Room Only   1,510.00   1,019.26   0.00   1,019.26   -32.50   admin
                Total (THB)   3,190.00   2,699.26   0.00   2,699.26   -15.38
                Total   2
                Daily Sales
                Sales Type   Room Charges (THB)   Extra Charges (THB)   Room Tax (THB)   Extra Tax (THB)   Discount (THB)   Adjustment (THB)   Total Sales (THB)
                Room Sales   2,699.26   0.00   0.00   0.00   0.00   0.00   2,699.26
                No Show Sales   841.12   0.00   148.88   0.00   0.00   0.00   990.00
                Total (THB)   3,540.38   0.00   148.88   0.00   0.00   0.00   3,689.26
                Room Status
                Date   Total Rooms   Occupied   Due Out   Vacant   Departed   Reserve   Blocked
                09-13   15   2   9   4   0   0   0
                Page 1 of 1
                """
            ),
            null
        )!!
        assertEquals("night_audit", r.type)
        assertEquals("2026-09-13", r.reportDate)
        assertEquals(3689.26, r.summary["total_sales"]!!, 0.001)
        assertEquals(990.0, r.summary["no_show_sales"]!!, 0.001)
        assertEquals(2.0, r.summary["occupied_rooms"]!!, 0.001)
        val rooms = r.rows.filter { it.containsKey("folio_no") }
        assertEquals(2, rooms.size)
        assertEquals("Expedia", rooms[1]["source"])
        assertEquals(1019.26, rooms[1]["total_rent"] as Double, 0.001)
        assertTrue(r.checks.joinToString { it.label }, r.checks.all { it.ok })
        assertEquals(3689.26, r.suggestedAmount!!, 0.001)
    }

    @Test
    fun expenseVoucher_readsVouchersAndSuggestsExpense() {
        val r = EzeeReportParser.parse(
            pages(
                """
                Demo Resort   Expense Voucher
                Date From 01/09/2026 To 13/09/2026
                Date   Bill To   Charge   Comment   Amount()   Pay Method   User
                05/09/2026   12   PEA   ค่าไฟฟ้า   บิลเดือน 8   2,500.00   Cash   admin
                06/09/2026   13   Laundry   ค่าซักผ้า   ผ้าปู   500.00   Transfer   admin
                Grand Total   3,000.00
                Pay Method Wise Summary   Charge wise Summary
                Pay Method   Amount ()   Charge Type   Amount ()
                Cash   2,500.00   ค่าไฟฟ้า   2,500.00
                Transfer   500.00   ค่าซักผ้า   500.00
                Grand Total   3,000.00   Grand Total   3,000.00
                """
            ),
            null
        )!!
        assertEquals("expense_voucher", r.type)
        assertEquals("2026-09-01", r.periodFrom)
        assertEquals("2026-09-13", r.periodTo)
        assertEquals(3000.0, r.summary["total_expense"]!!, 0.001)
        assertEquals(2.0, r.summary["voucher_count"]!!, 0.001)
        assertEquals(2500.0, r.summary["paid_by_cash"]!!, 0.001)
        assertEquals("EXPENSE", r.suggestedTransaction)
        assertTrue(r.countByDefault)
        assertTrue(r.checks.joinToString { it.label }, r.checks.all { it.ok })
    }

    @Test
    fun expenseVoucher_emptyIsNotCounted() {
        val r = EzeeReportParser.parse(
            pages(
                """
                Demo Resort   Expense Voucher
                Date From 13/09/2026 To 13/09/2026
                Grand Total   0.00
                Pay Method Wise Summary   Charge wise Summary
                Grand Total   0.00   Grand Total   0.00
                """
            ),
            null
        )!!
        assertEquals(0.0, r.summary["total_expense"]!!, 0.001)
        assertFalse(r.countByDefault)
    }

    @Test
    fun cityLedger_isReceivableNotIncome() {
        val r = EzeeReportParser.parse(
            pages(
                """
                Demo Resort   City Ledger - Summary to
                Date From   13/09/2026 To   13/09/2026 Ignore zero balance account
                Booking.com-1   -   -4,988.38   0.00   0.00   -4,988.38
                Expedia-2   Mr. Expedia-2   -   45,490.59   0.00   0.00   45,490.59
                Total   0.00   0.00   40,502.21
                """
            ),
            null
        )!!
        assertEquals("city_ledger_summary", r.type)
        assertEquals(40502.21, r.summary["closing_balance"]!!, 0.001)
        assertEquals(2, r.rows.size)
        assertNull(r.suggestedTransaction)
        assertFalse(r.countByDefault)
        assertTrue(r.checks.all { it.ok })
    }

    @Test
    fun monthlyStatistics_rowsGetDatesAndAreNotCounted() {
        val r = EzeeReportParser.parse(
            pages(
                """
                Demo Resort   Monthly Statistics
                Month September,2026 Show Unposted Inclusions Charges No
                1 Tue   15   15   0   100.00   820.00   820.00   30   12,300.00   0.00   0.00   0.00   0.00
                2 Wed   15   3   0   20.00   700.00   140.00   6   2,100.00   0.00   0.00   5,000.00   0.00
                Page 1 of 2
                """,
                """
                Demo Resort   Monthly Statistics
                Total   30   18   0   60.00   800.00   480.00   36   14,400.00   0.00   0.00   5,000.00   0.00
                Page 2 of 2
                """
            ),
            null
        )!!
        assertEquals("monthly_statistics", r.type)
        assertEquals("2026-09-01", r.periodFrom)
        assertEquals("2026-09-30", r.periodTo)
        assertEquals("2026-09-02", r.rows[1]["date"])
        assertEquals(14400.0, r.summary["room_charges"]!!, 0.001)
        assertEquals(5000.0, r.summary["receipt"]!!, 0.001)
        assertFalse(r.countByDefault)
        assertTrue(r.checks.joinToString { it.label }, r.checks.all { it.ok })
    }

    @Test
    fun yearlyStatistics_readsMonths() {
        val r = EzeeReportParser.parse(
            pages(
                """
                Demo Resort   Yearly Statistics
                Year 2026
                January   0   0   0   0.00   0.00   0.00   0   0.00   0.00   0.00   0.00   1,000.00
                February   360   72   0   20.00   900.00   180.00   150   64,800.00   0.00   0.00   60,000.00   2,000.00
                Grand Total   360   72   0   20.00   900.00   180.00   150   64,800.00   0.00   0.00   60,000.00   3,000.00
                """
            ),
            null
        )!!
        assertEquals("yearly_statistics", r.type)
        assertEquals("2026-02", r.rows[1]["month"])
        assertEquals(3000.0, r.summary["expense"]!!, 0.001)
        assertTrue(r.checks.all { it.ok })
    }

    @Test
    fun frontDesk_readsRoomsAndBalances() {
        val r = EzeeReportParser.parse(
            pages(
                """
                Demo Resort
                Front Desk Activities
                Date 14/09/2026
                Room   Guest Name Mobile   Arrival   Departure   Nights   Pax (A/C)   Rate Type   Source   Res No.   Voucher
                Departing Guests
                4 - Superior   Guest B   +11   13/09/2026   14/09/2026   1   2/0   Room Only   Expedia   937   255
                Folio No.   Total   Paid   Balance
                1166   1,019.26   1,019.26   0.00
                Pending Check-outs
                2 - Deluxe   Mr. ???   1   11/09/2026   13/09/2026   2   2/0   Room Only   918-2
                Folio No.   Total   Paid   Balance
                1112   1,400.00   0.00   1,400.00
                """
            ),
            null
        )!!
        assertEquals("front_desk_activities", r.type)
        assertEquals("2026-09-14", r.reportDate)
        assertEquals(2, r.rows.size)
        assertEquals("Expedia", r.rows[0]["source"])
        assertEquals("918-2", r.rows[1]["res_no"])
        assertEquals(1400.0, r.summary["total_balance_due"]!!, 0.001)
        assertNull(r.suggestedTransaction)
    }

    @Test
    fun unknownPdf_returnsNull() {
        assertNull(EzeeReportParser.parse(pages("ใบเสร็จรับเงิน\nร้านค้า ABC\nรวม 100.00"), "receipt.pdf"))
    }

    @Test
    fun detectsByFileNameWhenTitleMissing() {
        val r = EzeeReportParser.parse(pages("Demo Resort\nAs On Date 13/09/2026\n#(3)"), "noshowreport_6AA_20260913_60029.pdf")
        assertEquals("no_show_report", r?.type)
        assertEquals(3.0, r!!.summary["no_show_count"]!!, 0.001)
    }

    @Test
    fun buddhistYearIsConverted() {
        val r = EzeeReportParser.parse(pages("Demo Resort   Night Audit\nAs On Date   13/09/2569   THB"), null)!!
        assertEquals("2026-09-13", r.reportDate)
    }

    // ------------------------------------------------------------ PdfLineBuilder

    private fun word(text: String, x: Float, y: Float, size: Float = 10f): List<PdfGlyph> =
        text.mapIndexed { i, c -> PdfGlyph(x + i * size * 0.5f, y, size * 0.5f, size, c.toString()) }

    @Test
    fun lineBuilder_joinsLinesAndSeparatesColumns() {
        val glyphs = word("Cash", 10f, 100f) + word("500.00", 200f, 100.8f) + word("VAT", 10f, 120f)
        assertEquals(listOf("Cash   500.00", "VAT"), PdfLineBuilder.build(glyphs.shuffled()))
    }

    @Test
    fun lineBuilder_keepsThaiMarksAndDropsBoldDuplicates() {
        // "ค่า": the tone mark ่ comes as its own glyph with almost the same x as ค.
        val thai = listOf(
            PdfGlyph(10f, 50f, 5f, 10f, "ค"),
            PdfGlyph(12f, 50f, 0f, 10f, "่"),
            PdfGlyph(15f, 50f, 4f, 10f, "า")
        )
        // Bold drawn by printing each letter twice at the same place.
        val bold = listOf(
            PdfGlyph(10f, 80f, 6f, 10f, "T"), PdfGlyph(10.1f, 80f, 6f, 10f, "T"),
            PdfGlyph(16f, 80f, 5f, 10f, "o"), PdfGlyph(16.1f, 80f, 5f, 10f, "o")
        )
        assertEquals(listOf("ค่า", "To"), PdfLineBuilder.build(thai + bold))
    }
}
