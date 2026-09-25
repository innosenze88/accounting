package com.example

import com.example.data.booking.BookingRules
import com.example.data.booking.TxnSource
import com.example.data.booking.WalletEntity
import com.example.data.booking.WalletPercentRules
import com.example.data.booking.WalletRole
import com.example.data.booking.WalletTxnEntity
import com.example.data.booking.WalletTxnKind
import com.example.data.booking.WalletSetup
import com.example.data.wallet.WalletMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raising a wallet's % in a month with high bills, with a reason and a history. Made-up data. */
class WalletPercentRulesTest {

    private val wallets = BookingRules.DEFAULT_WALLETS.mapIndexed { i, (name, role, pct) ->
        WalletEntity(id = i.toLong() + 1, name = name, role = role.code, percent = pct, sortOrder = i)
    }
    private val advanceId = wallets.first { it.walletRole == WalletRole.ADVANCE }.id
    private val electricity = wallets.first { it.name == "ค่าน้ำ-ค่าไฟ" }
    private val savings = wallets.first { it.walletRole == WalletRole.SAVINGS }

    private fun splitting(ws: List<WalletEntity>) = ws.filter { it.active && it.walletRole != WalletRole.ADVANCE }

    @Test
    fun raiseOneWalletAndLetSavingsTakeTheDifference() {
        // Electricity 10 % -> 15 %: savings must go 10 % -> 5 % to stay at 100 %.
        val raised = wallets.map { if (it.id == electricity.id) it.copy(percent = 15.0) else it }
        val newSavings = WalletPercentRules.balancePercent(splitting(raised).associate { it.id to it.percent }, savings.id)
        assertEquals(5.0, newSavings!!, 0.0)
        val fixed = raised.map { if (it.id == savings.id) it.copy(percent = newSavings) else it }
        assertNull(WalletMath.percentProblem(splitting(fixed).map { it.percent }))

        val diffs = WalletPercentRules.diff(wallets, fixed)
        assertEquals(2, diffs.size)
        assertEquals("ค่าน้ำ-ค่าไฟ 10→15%, เงินเก็บ 10→5%", WalletPercentRules.describe(diffs))
        // A % change needs a reason.
        assertNotNull(WalletPercentRules.changeProblem(diffs, " "))
        assertNull(WalletPercentRules.changeProblem(diffs, "ค่าไฟหน้าร้อน"))
        // Renaming only is not a % change.
        assertTrue(WalletPercentRules.diff(wallets, wallets.map { it.copy(name = it.name + "!") }).isEmpty())

        // New income is split with the new %: electricity gets 15 % of 10,000.
        val setup = WalletSetup.from(fixed)!!
        val shares = BookingRules.split(setup, 10000.0, "2026-09-26", TxnSource.EZEE, null, "eZee")
        assertEquals(1500.0, shares.first { it.walletId == electricity.id }.amount, 0.0)
        assertEquals(500.0, shares.first { it.walletId == savings.id }.amount, 0.0)
    }

    @Test
    fun savingsCannotGoBelowZero() {
        val raised = wallets.map { if (it.id == electricity.id) it.copy(percent = 25.0) else it } // +15 %, savings only has 10
        assertNull(WalletPercentRules.balancePercent(splitting(raised).associate { it.id to it.percent }, savings.id))
    }

    @Test
    fun suggestedPercentCoversTheMonthlyTarget() {
        // Electricity bill ~ 9,000 a month, average income 60,000 -> 15 %.
        assertEquals(15.0, WalletPercentRules.suggestedPercent(9000.0, 60000.0)!!, 0.0)
        // Rounded up to half a percent: 22,400 / 80,000 = 28 % exactly; 22,500 / 80,000 = 28.125 -> 28.5
        assertEquals(28.0, WalletPercentRules.suggestedPercent(22400.0, 80000.0)!!, 0.0)
        assertEquals(28.5, WalletPercentRules.suggestedPercent(22500.0, 80000.0)!!, 0.0)
        assertNull(WalletPercentRules.suggestedPercent(null, 80000.0))
        assertNull(WalletPercentRules.suggestedPercent(9000.0, null))
    }

    @Test
    fun averageIncomeUsesFinishedMonthsOnly() {
        fun alloc(amount: Double, date: String) = WalletTxnEntity(
            walletId = 2, amount = amount, kind = WalletTxnKind.ALLOCATION.code, date = date, sourceType = TxnSource.EZEE.code
        )
        val txns = listOf(
            alloc(50000.0, "2026-06-10"), alloc(60000.0, "2026-07-10"), alloc(70000.0, "2026-08-10"),
            alloc(10000.0, "2026-05-10"), // older than the last 3 months
            alloc(99999.0, "2026-09-10"), // this month: not finished
            WalletTxnEntity(walletId = advanceId, amount = 5000.0, kind = WalletTxnKind.ADVANCE_IN.code, date = "2026-08-01", sourceType = TxnSource.PAYMENT.code),
            WalletTxnEntity(walletId = 2, amount = -3000.0, kind = WalletTxnKind.EXPENSE.code, date = "2026-08-02", sourceType = TxnSource.MANUAL.code)
        )
        assertEquals(60000.0, WalletPercentRules.avgMonthlyIncome(txns, advanceId, "2026-09-25")!!, 0.001)
        assertNull(WalletPercentRules.avgMonthlyIncome(emptyList(), advanceId, "2026-09-25"))
    }

    @Test
    fun goBackToAnEarlierSetting() {
        val before = WalletPercentRules.encode(wallets)
        val raised = wallets.map {
            when (it.id) {
                electricity.id -> it.copy(percent = 15.0)
                savings.id -> it.copy(percent = 5.0)
                else -> it
            }
        }
        val back = WalletPercentRules.restore(raised, before)!!
        assertEquals(10.0, back.first { it.id == electricity.id }.percent, 0.0)
        assertEquals(10.0, back.first { it.id == savings.id }.percent, 0.0)
        // A wallet added after that setting gets 0 % so the total still works.
        val withNew = raised + WalletEntity(id = 99, name = "ใหม่", role = WalletRole.BUDGET.code, percent = 0.0)
        assertEquals(0.0, WalletPercentRules.restore(withNew, before)!!.first { it.id == 99L }.percent, 0.0)
        assertNull(WalletPercentRules.restore(raised, "garbage"))
    }
}
