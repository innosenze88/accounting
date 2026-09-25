package com.example

import com.example.data.booking.BookingEntity
import com.example.data.booking.BookingMoney
import com.example.data.booking.BookingRules
import com.example.data.booking.BookingStatus
import com.example.data.booking.TxnSource
import com.example.data.booking.WalletEntity
import com.example.data.booking.WalletRole
import com.example.data.booking.WalletTxnEntity
import com.example.data.booking.WalletTxnKind
import com.example.data.dashboard.CountedDoc
import com.example.data.dashboard.FinanceOverviewCalc
import com.example.util.ReportPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dashboard figures: every source counted once, deposits and dues shown apart, yearly plan from the %. Made-up data. */
class FinanceOverviewTest {

    private val wallets = BookingRules.DEFAULT_WALLETS.mapIndexed { i, (name, role, pct) ->
        WalletEntity(id = i.toLong() + 1, name = name, role = role.code, percent = pct, sortOrder = i)
    }
    private val advanceId = wallets.first { it.walletRole == WalletRole.ADVANCE }.id
    private val salaryId = wallets.first { it.percent == 30.0 }.id
    private val today = "2026-09-25"

    private fun alloc(amount: Double, date: String, source: TxnSource, walletId: Long = salaryId) =
        WalletTxnEntity(walletId = walletId, amount = amount, kind = WalletTxnKind.ALLOCATION.code, date = date, sourceType = source.code, sourceId = 1)

    private val txns = listOf(
        WalletTxnEntity(walletId = advanceId, amount = 2000.0, kind = WalletTxnKind.ADVANCE_IN.code, date = "2026-09-20", sourceType = TxnSource.PAYMENT.code, sourceId = 9),
        alloc(3000.0, "2026-09-10", TxnSource.BOOKING),
        alloc(1000.0, "2026-09-11", TxnSource.EZEE),
        alloc(10000.0, "2026-08-15", TxnSource.EZEE),
        alloc(20000.0, "2026-07-15", TxnSource.EZEE),
        // voided allocation never counts
        alloc(999.0, "2026-09-12", TxnSource.PAYMENT).copy(voidedAt = 1L),
        // expense paid for document 7 (already counted as a document) + a plain expense
        WalletTxnEntity(walletId = salaryId, amount = -500.0, kind = WalletTxnKind.EXPENSE.code, date = "2026-09-13", sourceType = TxnSource.DOCUMENT.code, sourceId = 7),
        WalletTxnEntity(walletId = salaryId, amount = -300.0, kind = WalletTxnKind.EXPENSE.code, date = "2026-09-14", sourceType = TxnSource.MANUAL.code)
    )

    private val docs = listOf(
        CountedDoc(7, isIncome = false, isExpense = true, amount = 500.0, date = "2026-09-13", createdAt = 0, isEzeeReport = false),
        // eZee report of a closed day: the money is already in the day close
        CountedDoc(8, isIncome = true, isExpense = false, amount = 1000.0, date = "2026-09-11", createdAt = 0, isEzeeReport = true),
        CountedDoc(9, isIncome = true, isExpense = false, amount = 400.0, date = "2026-09-05", createdAt = 0, isEzeeReport = false)
    )

    private val openBooking = BookingEntity(id = 1, guestName = "ลูกค้า ก", roomNo = "3", checkIn = "2026-09-24", checkOut = "2026-09-26", nightlyRate = 1000.0, totalAmount = 2000.0, status = BookingStatus.CHECKED_IN.code)
    private val bookings = listOf(openBooking to BookingMoney(total = 2000.0, deposits = 500.0, balancePaid = 0.0, refunded = 0.0))

    private fun build(period: ReportPeriod) = FinanceOverviewCalc.build(docs, txns, wallets, bookings, setOf("2026-09-11"), period, today, null)

    @Test
    fun `income and expenses of the month are counted once`() {
        val o = build(ReportPeriod.THIS_MONTH)
        assertEquals(3000.0, o.incomeBookings, 0.001)
        assertEquals(1000.0, o.incomeEzee, 0.001)
        assertEquals(400.0, o.incomeDocuments, 0.001) // the eZee report of 11 Sep is not added again
        assertEquals(4400.0, o.income, 0.001)
        assertEquals(300.0, o.expenseWallets, 0.001) // the 500 paid for document 7 is counted by the document
        assertEquals(500.0, o.expenseDocuments, 0.001)
        assertEquals(3600.0, o.profit, 0.001)
    }

    @Test
    fun `a slip attached to a booking payment is not counted again`() {
        // Document 9 (400) is the transfer slip of a direct-booking payment: the booking counts that money.
        val o = FinanceOverviewCalc.build(
            docs, txns, wallets, bookings, setOf("2026-09-11"), ReportPeriod.THIS_MONTH, today, null, linkedSlipIds = setOf(9L)
        )
        assertEquals(0.0, o.incomeDocuments, 0.001)
        assertEquals(4000.0, o.income, 0.001)
        assertEquals(34000.0, o.plan!!.incomeSoFar, 0.001)
    }

    @Test
    fun `slip choices put the matching slip first and hide attached ones`() {
        val options = listOf(
            BookingRules.SlipOption(1, 1500.0, "2026-09-01", "a"),
            BookingRules.SlipOption(2, 2000.0, "2026-09-24", "b"),
            BookingRules.SlipOption(3, 2000.0, "2026-09-10", "c"),
            BookingRules.SlipOption(4, 2000.0, "2026-09-25", "d")
        )
        val choices = BookingRules.slipChoices(options, linked = setOf(4L), amount = 2000.0, date = "2026-09-25")
        assertEquals(listOf(2L, 3L, 1L), choices.map { it.documentId })
        assertTrue(BookingRules.isLikelySlip(options[1], 2000.0, "2026-09-25"))
        assertTrue(!BookingRules.isLikelySlip(options[2], 2000.0, "2026-09-25")) // 15 days away
        val paid = com.example.data.booking.BookingPaymentEntity(
            id = 1, bookingId = 1, kind = "DEPOSIT", method = "TRANSFER", amount = 1.0, date = today, slipDocumentId = 5
        )
        assertEquals(setOf(5L), BookingRules.linkedSlipIds(listOf(paid, paid.copy(id = 2, slipDocumentId = 6, voidedAt = 1L))))
    }

    @Test
    fun `deposits held and money to collect are shown apart`() {
        val o = build(ReportPeriod.THIS_MONTH)
        assertEquals(2000.0, o.advance, 0.001)
        assertEquals(1500.0, o.toCollect, 0.001)
        assertEquals(1, o.toCollectBookings)
    }

    @Test
    fun `wallets add up to 100 percent`() {
        val o = build(ReportPeriod.ALL)
        assertEquals(8, o.wallets.size)
        assertTrue(o.percentOk)
        assertEquals(100.0, o.percentTotal, 0.001)
        // savings + owner count as profit by default
        assertEquals(2, o.wallets.count { it.isProfit })
    }

    @Test
    fun `yearly plan uses the average of finished months`() {
        val plan = build(ReportPeriod.THIS_MONTH).plan
        assertNotNull(plan)
        plan!!
        assertEquals(15000.0, plan.avgMonthlyIncome, 0.001) // (Jul 20,000 + Aug 10,000) / 2
        assertEquals(180000.0, plan.income, 0.001)
        assertEquals(25.0, plan.profitPercent, 0.001)
        assertEquals(45000.0, plan.profit, 0.001)
        assertEquals(135000.0, plan.expenseBudget, 0.001)
        assertEquals(54000.0, plan.lines.first { it.wallet.id == salaryId }.year, 0.001)
        assertEquals(34400.0, plan.incomeSoFar, 0.001)
        assertEquals(800.0, plan.expenseSoFar, 0.001)
    }

    @Test
    fun `chosen profit wallets and monthly targets`() {
        val withTarget = wallets.map { if (it.id == salaryId) it.copy(monthlyTarget = 22400.0) else it }
        val plan = FinanceOverviewCalc.build(docs, txns, withTarget, bookings, emptySet(), ReportPeriod.ALL, today, setOf(salaryId)).plan!!
        assertEquals(30.0, plan.profitPercent, 0.001)
        // needs 22,400 x 12 = 268,800 a year; 30 % of 180,000 = 54,000 is far less
        assertTrue(plan.lines.first { it.wallet.id == salaryId }.shortBy > 0)
    }

    @Test
    fun `no income this year means no plan`() {
        assertNull(FinanceOverviewCalc.build(emptyList(), emptyList(), wallets, emptyList(), emptySet(), ReportPeriod.ALL, today, null).plan)
    }
}
