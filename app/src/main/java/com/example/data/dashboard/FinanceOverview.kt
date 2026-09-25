package com.example.data.dashboard

import com.example.data.booking.BookingEntity
import com.example.data.booking.BookingMoney
import com.example.data.booking.BookingStatus
import com.example.data.booking.TxnSource
import com.example.data.booking.WalletEntity
import com.example.data.booking.WalletRole
import com.example.data.booking.WalletTxnEntity
import com.example.data.booking.WalletTxnKind
import com.example.data.wallet.WalletMath
import com.example.util.ReportPeriod

/** One wallet on the dashboard. [isProfit] = counts as profit (savings, owner) instead of an expense budget. */
data class WalletLine(val wallet: WalletEntity, val balance: Double, val isProfit: Boolean)

/** One wallet in the yearly plan. [targetYear] = monthly target × 12 (when a target is set). */
data class PlanLine(val wallet: WalletEntity, val isProfit: Boolean, val year: Double, val month: Double, val targetYear: Double?) {
    /** Money missing for the year compared with the target (0 = enough or no target). */
    val shortBy: Double get() = targetYear?.let { (it - year).coerceAtLeast(0.0) } ?: 0.0
}

data class YearPlan(
    val year: String,
    /** How the yearly income was estimated (shown to the person). */
    val basis: String,
    val avgMonthlyIncome: Double,
    val income: Double,
    val expenseBudget: Double,
    val profit: Double,
    val expensePercent: Double,
    val profitPercent: Double,
    val lines: List<PlanLine>,
    /** Real figures of this year so far. */
    val incomeSoFar: Double,
    val expenseSoFar: Double
) {
    val profitSoFar: Double get() = incomeSoFar - expenseSoFar
}

data class FinanceOverview(
    // (1) income in the period
    val incomeBookings: Double,
    val incomeEzee: Double,
    val incomeDocuments: Double,
    // (2) expenses in the period
    val expenseWallets: Double,
    val expenseDocuments: Double,
    // (3) deposits held (now)
    val advance: Double,
    // (4) still to collect from guests at check-out (now)
    val toCollect: Double,
    val toCollectBookings: Int,
    // (5) wallets (now)
    val wallets: List<WalletLine>,
    val percentTotal: Double,
    val plan: YearPlan?
) {
    val income: Double get() = incomeBookings + incomeEzee + incomeDocuments
    val expense: Double get() = expenseWallets + expenseDocuments
    val profit: Double get() = income - expense
    val percentOk: Boolean get() = kotlin.math.abs(percentTotal - 100.0) < 0.001
}

/** A scanned document reduced to what the overview needs (counted = verified, not a sample, not voided). */
data class CountedDoc(val id: Long, val isIncome: Boolean, val isExpense: Boolean, val amount: Double, val date: String?, val createdAt: Long, val isEzeeReport: Boolean)

/**
 * Builds the dashboard figures from every source without counting the same money twice:
 *  - income: money split into the wallets (direct bookings + eZee day close) + verified income documents,
 *    except eZee report documents of a day that was already closed (that money is in the day close).
 *  - expenses: money paid from the wallets + verified expense documents, except wallet payments made
 *    for a document that is already counted.
 */
object FinanceOverviewCalc {

    /** Default when the person has not chosen: the savings wallet and wallets named "เจ้าของ"/"กำไร" are profit. */
    fun defaultIsProfit(w: WalletEntity): Boolean =
        w.walletRole == WalletRole.SAVINGS || w.name.contains("เจ้าของ") || w.name.contains("กำไร")

    private val INCOME_SOURCES = setOf(TxnSource.PAYMENT.code, TxnSource.BOOKING.code, TxnSource.EZEE.code)

    /** Income movements (+ reversals) in the budget/savings wallets. */
    private fun incomeTxns(txns: List<WalletTxnEntity>, advanceId: Long?): List<WalletTxnEntity> = txns.filter {
        it.isActive && it.walletId != advanceId && it.sourceType in INCOME_SOURCES &&
            (it.txnKind == WalletTxnKind.ALLOCATION || it.txnKind == WalletTxnKind.ADJUST)
    }

    private fun expenseTxns(txns: List<WalletTxnEntity>, countedExpenseDocIds: Set<Long>): List<WalletTxnEntity> = txns.filter {
        it.isActive && it.txnKind == WalletTxnKind.EXPENSE &&
            !(it.sourceType == TxnSource.DOCUMENT.code && it.sourceId != null && it.sourceId in countedExpenseDocIds)
    }

    private fun docIncomeCounts(d: CountedDoc, closedDays: Set<String>): Boolean =
        d.isIncome && !(d.isEzeeReport && d.date != null && d.date in closedDays)

    fun build(
        docs: List<CountedDoc>,
        txns: List<WalletTxnEntity>,
        wallets: List<WalletEntity>,
        bookings: List<Pair<BookingEntity, BookingMoney>>,
        closedDays: Set<String>,
        period: ReportPeriod,
        today: String,
        profitWalletIds: Set<Long>?
    ): FinanceOverview {
        val active = wallets.filter { it.active }.sortedBy { it.sortOrder }
        val advance = active.firstOrNull { it.walletRole == WalletRole.ADVANCE }
        val isProfit: (WalletEntity) -> Boolean = { w -> profitWalletIds?.contains(w.id) ?: defaultIsProfit(w) }
        val countedExpenseIds = docs.filter { it.isExpense }.map { it.id }.toSet()

        fun inPeriod(date: String?, created: Long) = period.contains(date, created, today)

        val inc = incomeTxns(txns, advance?.id).filter { inPeriod(it.date, it.createdAt) }
        val incomeEzee = inc.filter { it.sourceType == TxnSource.EZEE.code }.sumOf { it.amount }
        val incomeBookings = inc.filter { it.sourceType != TxnSource.EZEE.code }.sumOf { it.amount }
        val incomeDocs = docs.filter { docIncomeCounts(it, closedDays) && inPeriod(it.date, it.createdAt) }.sumOf { it.amount }
        val expWallets = expenseTxns(txns, countedExpenseIds).filter { inPeriod(it.date, it.createdAt) }.sumOf { -it.amount }
        val expDocs = docs.filter { it.isExpense && inPeriod(it.date, it.createdAt) }.sumOf { it.amount }

        // Balances need every movement (not only the period).
        val balances = HashMap<Long, Long>()
        txns.filter { it.isActive }.forEach { balances[it.walletId] = (balances[it.walletId] ?: 0L) + WalletMath.toSatang(it.amount) }
        fun bal(id: Long) = WalletMath.toBaht(balances[id] ?: 0L)

        val open = bookings.filter { (b, _) -> b.bookingStatus == BookingStatus.BOOKED || b.bookingStatus == BookingStatus.CHECKED_IN }
        val due = open.map { it.second.due }.filter { it > 0 }

        val splitting = active.filter { it.walletRole != WalletRole.ADVANCE }
        val lines = splitting.map { WalletLine(it, bal(it.id), isProfit(it)) }

        return FinanceOverview(
            incomeBookings = round(incomeBookings), incomeEzee = round(incomeEzee), incomeDocuments = round(incomeDocs),
            expenseWallets = round(expWallets), expenseDocuments = round(expDocs),
            advance = advance?.let { bal(it.id) } ?: 0.0,
            toCollect = round(due.sum()), toCollectBookings = due.size,
            wallets = lines,
            percentTotal = splitting.sumOf { it.percent },
            plan = yearPlan(docs, txns, advance?.id, closedDays, countedExpenseIds, splitting, isProfit, today)
        )
    }

    /**
     * Estimates the whole year from this year's income:
     *  - average of the finished months this year that have income, or
     *  - (no finished month yet) this month so far, scaled to the full month.
     * Each wallet's share of that estimate = what the year should look like with the current %.
     */
    fun yearPlan(
        docs: List<CountedDoc>,
        txns: List<WalletTxnEntity>,
        advanceId: Long?,
        closedDays: Set<String>,
        countedExpenseIds: Set<Long>,
        splitting: List<WalletEntity>,
        isProfit: (WalletEntity) -> Boolean,
        today: String
    ): YearPlan? {
        val year = today.take(4)
        val month = today.take(7)
        val byMonth = HashMap<String, Double>()
        fun add(ym: String, v: Double) { if (ym.take(4) == year) byMonth[ym] = (byMonth[ym] ?: 0.0) + v }
        incomeTxns(txns, advanceId).forEach { add(dateOf(it.date, it.createdAt).take(7), it.amount) }
        docs.filter { docIncomeCounts(it, closedDays) }.forEach { add(dateOf(it.date, it.createdAt).take(7), it.amount) }

        val incomeSoFar = byMonth.filterKeys { it <= month }.values.sum()
        val expenseSoFar =
            expenseTxns(txns, countedExpenseIds).filter { dateOf(it.date, it.createdAt).take(4) == year }.sumOf { -it.amount } +
                docs.filter { it.isExpense && dateOf(it.date, it.createdAt).take(4) == year }.sumOf { it.amount }

        val finished = byMonth.filter { (ym, v) -> ym < month && v > 0 }
        val (avg, basis) = when {
            finished.isNotEmpty() ->
                finished.values.average() to "เฉลี่ยจาก ${finished.size} เดือนที่จบแล้วของปี $year"
            (byMonth[month] ?: 0.0) > 0 -> {
                val day = today.substring(8, 10).toInt()
                val days = WalletMath.addDays(WalletMath.nextMonth(month) + "-01", -1).substring(8, 10).toInt()
                (byMonth[month]!! / day * days) to "ประมาณจากเดือนนี้ $day วันแรก (ข้อมูลยังน้อย)"
            }
            else -> return null
        }
        val income = avg * 12
        val lines = splitting.map { w ->
            val y = income * w.percent / 100.0
            PlanLine(w, isProfit(w), round(y), round(y / 12), w.monthlyTarget?.takeIf { it > 0 }?.let { it * 12 })
        }
        val profitPct = splitting.filter(isProfit).sumOf { it.percent }
        val expensePct = splitting.filterNot(isProfit).sumOf { it.percent }
        return YearPlan(
            year = year, basis = basis, avgMonthlyIncome = round(avg), income = round(income),
            expenseBudget = round(income * expensePct / 100.0), profit = round(income * profitPct / 100.0),
            expensePercent = expensePct, profitPercent = profitPct, lines = lines,
            incomeSoFar = round(incomeSoFar), expenseSoFar = round(expenseSoFar)
        )
    }

    private fun dateOf(date: String?, created: Long): String =
        date?.takeIf { WalletMath.isIsoDate(it) } ?: ReportPeriod.isoDay(created)

    private fun round(v: Double): Double = WalletMath.toBaht(WalletMath.toSatang(v))
}
