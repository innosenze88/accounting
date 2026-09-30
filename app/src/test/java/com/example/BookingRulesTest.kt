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
    fun dayCloseCanBeUndoneAndClosedAgainWithoutDoubleReversal() {
        val src = com.example.data.booking.TxnSource.EZEE
        val day = BookingRules.daySourceId("2026-09-25")
        // 1) Closed with a typo (21,000 instead of 12,000); already moved in MAKE.
        var id = 0L
        fun ids(l: List<com.example.data.booking.WalletTxnEntity>) = l.map { it.copy(id = ++id) }
        val wrong = ids(BookingRules.split(setup, 21000.0, "2026-09-25", src, day, "eZee")).map { it.copy(transferredAt = 1L) }
        val kinds = setOf(WalletTxnKind.ALLOCATION)
        assertEquals(wrong.size, BookingRules.outstanding(wrong, kinds).size)
        // 2) Undo -> opposite movements (money has to go back in MAKE).
        val (v1, back1) = BookingRules.reverse(BookingRules.outstanding(wrong, kinds), "2026-09-26", 2L)
        assertTrue(v1.isEmpty())
        val afterUndo = wrong + ids(back1)
        assertTrue(BookingRules.outstanding(afterUndo, kinds).isEmpty())
        assertEquals(0.0, BookingRules.balances(afterUndo).values.sum(), 1e-9)
        // 3) Closed again with the right amount, then undone again (not moved in MAKE yet): only the new split is undone.
        val right = ids(BookingRules.split(setup, 12000.0, "2026-09-25", src, day, "eZee"))
        val all = afterUndo + right
        assertEquals(12000.0, BookingRules.balances(all).values.sum(), 1e-9)
        val open = BookingRules.outstanding(all, kinds)
        assertEquals(right.map { it.id }, open.map { it.id })
        val (v2, back2) = BookingRules.reverse(open, "2026-09-26", 3L)
        assertTrue(back2.isEmpty())
        val final = all.map { t -> v2.firstOrNull { it.id == t.id } ?: t }
        assertEquals(0.0, BookingRules.balances(final).values.sum(), 1e-9)
    }

    @Test
    fun manualAdjustmentNeedsReason() {
        assertNotNull(runCatching { BookingRules.adjustment(2L, 100.0, "2026-09-25", " ", true, 1L) }.exceptionOrNull())
        assertNotNull(runCatching { BookingRules.adjustment(2L, 0.0, "2026-09-25", "x", true, 1L) }.exceptionOrNull())
        val t = BookingRules.adjustment(2L, -150.0, "2026-09-25", "นับเงินผิด", alreadyInMake = true, now = 5L)
        assertEquals(-150.0, t.amount, 0.0)
        assertEquals(5L, t.transferredAt)
        // Already right in MAKE -> nothing to transfer.
        assertTrue(BookingRules.pendingTransfers(listOf(t)).isEmpty())
        assertEquals(mapOf(2L to -150.0), BookingRules.pendingTransfers(listOf(BookingRules.adjustment(2L, -150.0, "2026-09-25", "x", false, 5L))))
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
    fun monthEndLeavesNextMonthIncomeInItsWallets() {
        val sep = BookingRules.split(setup, 10000.0, "2026-09-10", com.example.data.booking.TxnSource.EZEE, null, "eZee")
        val oct = BookingRules.split(setup, 5000.0, "2026-10-02", com.example.data.booking.TxnSource.EZEE, null, "eZee")
        val all = sep + oct
        // Closing September on 3 October moves only September's leftovers.
        val end = BookingRules.monthEnd(setup, BookingRules.monthEndBalances(all, "2026-09"), "2026-09", "2026-09-30")
        val moved = end.filter { it.amount > 0 }.sumOf { it.amount }
        assertEquals(10000.0 - BookingRules.balances(sep)[savingsId]!!, moved, 1e-9)
        val after = BookingRules.balances(all + end)
        val octOnly = BookingRules.balances(oct)
        wallets.filter { it.walletRole == WalletRole.BUDGET }.forEach {
            assertEquals(octOnly[it.id] ?: 0.0, after[it.id] ?: 0.0, 1e-9)
        }
        // Closing it again later (after October was closed) cannot move the same money twice.
        val octEnd = BookingRules.monthEnd(setup, BookingRules.monthEndBalances(all + end, "2026-10"), "2026-10", "2026-10-31")
        val again = BookingRules.monthEnd(setup, BookingRules.monthEndBalances(all + end + octEnd, "2026-09"), "2026-09", "2026-09-30")
        assertTrue(again.isEmpty())
    }

    @Test
    fun monthEndDoesNotMoveNewIncomeThatRefilledASpentWallet() {
        val salaries = 2L
        // End of September: 300 left in the salaries wallet.
        val sep = listOf(
            com.example.data.booking.WalletTxnEntity(
                walletId = salaries, amount = 300.0, kind = WalletTxnKind.ALLOCATION.code, date = "2026-09-20",
                sourceType = com.example.data.booking.TxnSource.EZEE.code
            )
        )
        // October: the 300 is paid out, then October income puts 300 back.
        val oct = listOf(
            BookingRules.expense(salaries, 300.0, "2026-10-02", "เงินเดือน", null),
            com.example.data.booking.WalletTxnEntity(
                walletId = salaries, amount = 300.0, kind = WalletTxnKind.ALLOCATION.code, date = "2026-10-03",
                sourceType = com.example.data.booking.TxnSource.EZEE.code
            )
        )
        val left = BookingRules.monthEndBalances(sep + oct, "2026-09")
        assertEquals(0.0, left[salaries] ?: 0.0, 1e-9)
        assertTrue(BookingRules.monthEnd(setup, left, "2026-09", "2026-09-30").isEmpty())
        // Only part of it spent: the rest of September's money still moves.
        val partly = BookingRules.monthEndBalances(sep + BookingRules.expense(salaries, 100.0, "2026-10-02", "x", null), "2026-09")
        assertEquals(200.0, partly[salaries] ?: 0.0, 1e-9)
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
