package com.example

import com.example.data.booking.BookingEntity
import com.example.data.booking.BookingPaymentEntity
import com.example.data.booking.BookingRules
import com.example.data.booking.BookingStatus
import com.example.data.booking.PaymentKind
import com.example.data.booking.PaymentMethod
import com.example.data.booking.WalletEntity
import com.example.data.booking.WalletRole
import com.example.data.booking.WalletSetup
import com.example.data.booking.WalletTxnKind
import com.example.data.wallet.WalletMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agreed money flow: deposit -> advance wallet -> split at check-out; balance split at once; cancel = 50 % refund. */
class BookingRulesTest {

    private val wallets = BookingRules.DEFAULT_WALLETS.mapIndexed { i, (name, role, pct) ->
        WalletEntity(id = i.toLong() + 1, name = name, role = role.code, percent = pct, sortOrder = i)
    }
    private val setup = WalletSetup.from(wallets)!!
    private val advanceId = wallets.first { it.walletRole == WalletRole.ADVANCE }.id
    private val savingsId = wallets.first { it.walletRole == WalletRole.SAVINGS }.id

    private val booking = BookingEntity(
        id = 10, guestName = "คุณเอ", roomNo = "5", checkIn = "2026-09-30", checkOut = "2026-10-02",
        nightlyRate = 1500.0, totalAmount = 3000.0
    )

    private fun pay(id: Long, kind: PaymentKind, amount: Double) = BookingPaymentEntity(
        id = id, bookingId = 10, kind = kind.code, method = PaymentMethod.TRANSFER.code, amount = amount, date = "2026-09-20"
    )

    @Test
    fun defaultWalletsAddUpTo100() {
        assertNull(WalletMath.percentProblem(setup.splitTargets.map { it.second }))
        assertEquals(8, setup.splitTargets.size)
    }

    @Test
    fun depositGoesToAdvanceWalletOnly() {
        val txns = BookingRules.onPayment(setup, pay(1, PaymentKind.DEPOSIT, 1000.0), booking)
        assertEquals(1, txns.size)
        assertEquals(advanceId, txns[0].walletId)
        assertEquals(1000.0, txns[0].amount, 0.0)
        assertEquals(WalletTxnKind.ADVANCE_IN.code, txns[0].kind)
    }

    @Test
    fun checkoutMovesDepositsAndBalanceIsSplitAtOnce() {
        val deposit = pay(1, PaymentKind.DEPOSIT, 1000.0)
        val balance = pay(2, PaymentKind.BALANCE, 2000.0)
        val all = BookingRules.onPayment(setup, deposit, booking) +
            BookingRules.onPayment(setup, balance, booking) +
            BookingRules.checkOut(setup, booking, listOf(deposit, balance), "2026-10-02")
        val bal = BookingRules.balances(all)
        assertEquals(0.0, bal[advanceId] ?: 0.0, 0.0) // advance emptied
        // Whole stay (3,000) ends up split: 30 % salary wallet = 900
        assertEquals(900.0, bal[2L]!!, 0.0)
        assertEquals(3000.0, bal.filterKeys { it != advanceId }.values.sum(), 1e-9)
        assertEquals(0.0, BookingRules.money(booking, listOf(deposit, balance)).due, 0.0)
    }

    @Test
    fun cancellationRefundsHalfAndSplitsTheRest() {
        val deposit = pay(1, PaymentKind.DEPOSIT, 1500.0)
        val c = BookingRules.cancel(setup, booking, listOf(deposit), "2026-09-25", 50.0)
        assertEquals(750.0, c.refund, 0.0)
        assertEquals(750.0, c.kept, 0.0)
        val bal = BookingRules.balances(BookingRules.onPayment(setup, deposit, booking) + c.txns)
        assertEquals(0.0, bal[advanceId]!!, 0.0)
        assertEquals(750.0, bal.filterKeys { it != advanceId }.values.sum(), 1e-9)
    }

    @Test
    fun cancellationAfterBalancePaidRefundsPercentOfEverythingPaid() {
        // Paid in full (1,000 deposit + 2,000 balance), then cancels: 50 % of 3,000 goes back.
        val deposit = pay(1, PaymentKind.DEPOSIT, 1000.0)
        val balance = pay(2, PaymentKind.BALANCE, 2000.0)
        val before = BookingRules.onPayment(setup, deposit, booking) + BookingRules.onPayment(setup, balance, booking)
        val c = BookingRules.cancel(setup, booking, listOf(deposit, balance), "2026-09-25", 50.0)
        assertEquals(3000.0, c.paid, 0.0)
        assertEquals(1500.0, c.refund, 0.0)
        assertEquals(1500.0, c.kept, 0.0)
        val bal = BookingRules.balances(before + c.txns)
        assertEquals(0.0, bal[advanceId] ?: 0.0, 0.0)
        // Only what the resort keeps stays in the wallets; 2,000 was split before, 500 comes back out.
        assertEquals(1500.0, bal.filterKeys { it != advanceId }.values.sum(), 1e-9)
        assertEquals(450.0, bal[2L]!!, 1e-9) // salary wallet 30 % of 1,500
    }

    @Test
    fun cancellationWithOnlyBalanceAndFullRefundTakesItAllBack() {
        val balance = pay(2, PaymentKind.BALANCE, 2000.0)
        val before = BookingRules.onPayment(setup, balance, booking)
        val c = BookingRules.cancel(setup, booking, listOf(balance), "2026-09-25", 100.0)
        assertEquals(2000.0, c.refund, 0.0)
        val bal = BookingRules.balances(before + c.txns)
        bal.values.forEach { assertEquals(0.0, it, 1e-9) }
    }

    @Test
    fun cancellationWithSmallRefundAddsIncome() {
        // Deposit 1,000 + balance 1,000, refund 20 % = 400 -> kept 1,600 (1,000 already split, 600 more now).
        val deposit = pay(1, PaymentKind.DEPOSIT, 1000.0)
        val balance = pay(2, PaymentKind.BALANCE, 1000.0)
        val before = BookingRules.onPayment(setup, deposit, booking) + BookingRules.onPayment(setup, balance, booking)
        val c = BookingRules.cancel(setup, booking, listOf(deposit, balance), "2026-09-25", 20.0)
        assertEquals(400.0, c.refund, 0.0)
        val bal = BookingRules.balances(before + c.txns)
        assertEquals(1600.0, bal.filterKeys { it != advanceId }.values.sum(), 1e-9)
        assertEquals(0.0, bal[advanceId] ?: 0.0, 0.0)
    }

    @Test
    fun cannotSettleTwice() {
        val settled = booking.copy(settledAt = 1L, status = BookingStatus.CHECKED_OUT.code)
        val e = runCatching { BookingRules.checkOut(setup, settled, emptyList(), "2026-10-02") }.exceptionOrNull()
        assertNotNull(e)
    }

    @Test
    fun transfersAndReversal() {
        val deposit = pay(1, PaymentKind.DEPOSIT, 1000.0)
        val txns = BookingRules.onPayment(setup, deposit, booking).mapIndexed { i, t -> t.copy(id = i + 1L) }
        assertEquals(mapOf(advanceId to 1000.0), BookingRules.pendingTransfers(txns))
        // Already moved in MAKE, then the payment turns out to be a mistake -> opposite movement to transfer back.
        val moved = txns.map { it.copy(transferredAt = 5L) }
        val (voided, opposite) = BookingRules.reverse(moved, "2026-09-21", 6L)
        assertTrue(voided.isEmpty())
        assertEquals(mapOf(advanceId to -1000.0), BookingRules.pendingTransfers(moved + opposite))
        // Not moved yet -> simply voided.
        val (v2, o2) = BookingRules.reverse(txns, "2026-09-21", 6L)
        assertEquals(1, v2.size)
        assertTrue(o2.isEmpty())
    }

    @Test
    fun monthEndMovesLeftoversToSavings() {
        val income = BookingRules.split(setup, 10000.0, "2026-09-10", com.example.data.booking.TxnSource.EZEE, null, "eZee")
        val spent = BookingRules.expense(2L, 2500.0, "2026-09-28", "เงินเดือน", null)
        val before = BookingRules.balances(income + spent)
        val end = BookingRules.monthEnd(setup, before, "2026-09", "2026-09-30")
        val after = BookingRules.balances(income + spent + end)
        wallets.filter { it.walletRole == WalletRole.BUDGET }.forEach { assertEquals(0.0, after[it.id] ?: 0.0, 1e-9) }
        assertEquals(10000.0 - 2500.0, after[savingsId]!!, 1e-9)
    }

    @Test
    fun bookingChecks() {
        assertNull(BookingRules.bookingProblem("คุณเอ", "5", "2026-09-30", "2026-10-02", 3000.0))
        assertNotNull(BookingRules.bookingProblem("คุณเอ", "5", "2026-10-02", "2026-09-30", 3000.0))
        val other = booking.copy(id = 11, checkIn = "2026-10-01", checkOut = "2026-10-03")
        assertEquals(11L, BookingRules.overlapping(booking, listOf(other))?.id)
        assertNull(BookingRules.overlapping(booking, listOf(other.copy(checkIn = "2026-10-02", checkOut = "2026-10-04"))))
    }
}
