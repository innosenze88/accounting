package com.example.data.booking

import com.example.data.wallet.WalletMath
import java.util.Locale
import kotlin.math.ceil

/**
 * Changing the % of the wallets, e.g. raising "ค่าน้ำ-ค่าไฟ" in a month with high bills.
 * Pure Kotlin (unit-tested). The new % only applies to income split AFTER the change;
 * money that was already split stays where it is.
 */
object WalletPercentRules {

    /** One wallet's % before and after a change. */
    data class PercentDiff(val walletId: Long, val name: String, val before: Double, val after: Double)

    /** Wallets whose % (or active state) changed. Wallets that are new or were switched off count as 0 %. */
    fun diff(before: List<WalletEntity>, after: List<WalletEntity>): List<PercentDiff> {
        fun pct(w: WalletEntity?) = if (w == null || !w.active || w.walletRole == WalletRole.ADVANCE) 0.0 else w.percent
        val old = before.associateBy { it.id }
        return after.filter { it.walletRole != WalletRole.ADVANCE }.mapNotNull { w ->
            val b = pct(old[w.id].takeIf { w.id != 0L })
            val a = pct(w)
            if (kotlin.math.abs(a - b) < 0.0001) null else PercentDiff(w.id, w.name, b, a)
        }
    }

    /** "ค่าน้ำ-ค่าไฟ 10→15%, เงินเก็บ 10→5%" */
    fun describe(diffs: List<PercentDiff>): String =
        diffs.joinToString(", ") { "${it.name} ${fmt(it.before)}→${fmt(it.after)}%" }

    /**
     * Average money split into the wallets per month over the last [months] FINISHED months that had income
     * (the current month is left out because it is not complete). null when there is no history yet.
     */
    fun avgMonthlyIncome(txns: List<WalletTxnEntity>, advanceId: Long?, today: String, months: Int = 3): Double? {
        val thisMonth = today.take(7)
        val incomeSources = setOf(TxnSource.PAYMENT.code, TxnSource.BOOKING.code, TxnSource.EZEE.code)
        val byMonth = txns.filter {
            it.isActive && it.walletId != advanceId && it.sourceType in incomeSources &&
                (it.txnKind == WalletTxnKind.ALLOCATION || it.txnKind == WalletTxnKind.ADJUST) &&
                it.date.length >= 7 && it.date.take(7) < thisMonth
        }.groupBy { it.date.take(7) }
            .mapValues { (_, l) -> WalletMath.toBaht(l.sumOf { WalletMath.toSatang(it.amount) }) }
            .filterValues { it > 0 }
        val last = byMonth.keys.sortedDescending().take(months)
        if (last.isEmpty()) return null
        return last.sumOf { byMonth.getValue(it) } / last.size
    }

    /**
     * % a wallet needs so that its share of an average month covers [monthlyTarget]
     * (rounded UP to half a percent). null when it cannot be worked out.
     */
    fun suggestedPercent(monthlyTarget: Double?, avgMonthlyIncome: Double?): Double? {
        if (monthlyTarget == null || monthlyTarget <= 0 || avgMonthlyIncome == null || avgMonthlyIncome <= 0) return null
        val raw = monthlyTarget / avgMonthlyIncome * 100.0
        return (ceil(raw * 2.0 - 1e-9) / 2.0).coerceAtMost(100.0)
    }

    /**
     * Makes the % add up to 100 by changing only [balanceWalletId] (normally the savings wallet):
     * when one wallet was raised, the difference is taken from it; when lowered, it gets the difference.
     * Returns the new % for [balanceWalletId], or null when that is impossible (would go below 0 or above 100).
     */
    fun balancePercent(percents: Map<Long, Double>, balanceWalletId: Long): Double? {
        val current = percents[balanceWalletId] ?: return null
        val others = percents.filterKeys { it != balanceWalletId }.values.sum()
        val needed = Math.round((100.0 - others) * 100.0) / 100.0
        if (needed < 0.0 || needed > 100.0) return null
        return if (kotlin.math.abs(needed - current) < 0.0001) current else needed
    }

    /** null when a % change can be saved, otherwise what is missing. */
    fun changeProblem(diffs: List<PercentDiff>, reason: String): String? =
        if (diffs.isNotEmpty() && reason.isBlank()) "ใส่เหตุผลที่ปรับ % (เช่น ค่าไฟเดือนนี้สูง)" else null

    /** "3=10.0;9=5.0" — % of every active non-advance wallet, for [WalletPercentChangeEntity]. */
    fun encode(wallets: List<WalletEntity>): String =
        wallets.filter { it.active && it.walletRole != WalletRole.ADVANCE }.joinToString(";") { "${it.id}=${it.percent}" }

    fun decode(s: String): Map<Long, Double> = s.split(';').mapNotNull { part ->
        val (id, pct) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val i = id.trim().toLongOrNull() ?: return@mapNotNull null
        val p = pct.trim().toDoubleOrNull() ?: return@mapNotNull null
        i to p
    }.toMap()

    /**
     * An earlier setting applied to today's wallets (to go back after a month with high bills).
     * Wallets that did not exist then get 0 %. null when the result would not add up to 100.
     */
    fun restore(current: List<WalletEntity>, saved: String): List<WalletEntity>? {
        val pct = decode(saved)
        if (pct.isEmpty()) return null
        val out = current.map { w ->
            if (w.walletRole == WalletRole.ADVANCE || !w.active) w else w.copy(percent = pct[w.id] ?: 0.0)
        }
        val splitting = out.filter { it.active && it.walletRole != WalletRole.ADVANCE }.map { it.percent }
        return if (WalletMath.percentProblem(splitting) == null) out else null
    }

    fun fmt(p: Double): String = if (p % 1.0 == 0.0) p.toLong().toString() else String.format(Locale.US, "%.2f", p).trimEnd('0').trimEnd('.')
}
