package com.example.data.wallet

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/** One wallet's part of an amount. */
data class Share(val walletId: Long, val amount: Double)

/**
 * Money rules for the wallets ("กระเป๋า"). Pure Kotlin, unit-tested.
 * All sums are done in satang (1/100 baht) so the parts always add up to the exact total.
 */
object WalletMath {

    fun toSatang(baht: Double): Long = (baht * 100.0).roundToLong()
    fun toBaht(satang: Long): Double = satang / 100.0

    /** null when the percents are usable (0..100 each, total exactly 100). */
    fun percentProblem(percents: List<Double>): String? {
        if (percents.isEmpty()) return "ยังไม่มีกระเป๋า"
        if (percents.any { it < 0 || it > 100 || it.isNaN() }) return "% ต้องอยู่ระหว่าง 0 ถึง 100"
        val total = percents.sum()
        if (abs(total - 100.0) > 0.001) return "รวม % ต้องเท่ากับ 100 (ตอนนี้ ${String.format(Locale.US, "%.2f", total)})"
        return null
    }

    /**
     * Splits [amount] by percent. [wallets] = (walletId, percent). Rounding leftovers (a few satang)
     * go to [remainderTo] (the savings wallet), or to the first wallet if it is not in the list.
     * Wallets with 0 % get nothing and are left out.
     */
    fun allocate(amount: Double, wallets: List<Pair<Long, Double>>, remainderTo: Long?): List<Share> {
        require(amount >= 0) { "amount must not be negative" }
        val total = toSatang(amount)
        if (total == 0L || wallets.isEmpty()) return emptyList()
        val parts = LinkedHashMap<Long, Long>()
        for ((id, pct) in wallets) {
            if (pct <= 0) continue
            parts[id] = (parts[id] ?: 0L) + (total * pct / 100.0).toLong() // floor
        }
        if (parts.isEmpty()) return emptyList()
        val rest = total - parts.values.sum()
        val target = remainderTo?.takeIf { it in parts } ?: parts.keys.first()
        parts[target] = parts.getValue(target) + rest
        return parts.filterValues { it != 0L }.map { (id, s) -> Share(id, toBaht(s)) }
    }

    /**
     * Cancelled direct booking: the guest gets [refundPercent] % of the deposit back,
     * the rest is income. Returns (refund, kept) that add up exactly to [deposit].
     */
    fun cancellationSplit(deposit: Double, refundPercent: Double): Pair<Double, Double> {
        val total = toSatang(deposit)
        val refund = (total * refundPercent.coerceIn(0.0, 100.0) / 100.0).roundToLong()
        return toBaht(refund) to toBaht(total - refund)
    }

    /**
     * What still has to be moved between MAKE pockets: the net of every wallet movement that
     * was not marked "transferred" yet (expenses are paid from the pocket directly, so they are left out).
     * Positive = transfer INTO that pocket, negative = transfer OUT of it.
     */
    fun pendingTransfers(movements: List<Pair<Long, Double>>): Map<Long, Double> =
        movements.groupBy({ it.first }, { toSatang(it.second) })
            .mapValues { (_, v) -> toBaht(v.sum()) }
            .filterValues { it != 0.0 }

    // ------------------------------------------------------------------ dates (YYYY-MM-DD)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    fun isIsoDate(s: String?): Boolean = s != null && Regex("""^\d{4}-\d{2}-\d{2}$""").matches(s) &&
        runCatching { fmt().parse(s) }.isSuccess

    /** Nights between two ISO dates (check-out minus check-in), or null when a date is wrong. */
    fun nights(checkIn: String, checkOut: String): Int? {
        if (!isIsoDate(checkIn) || !isIsoDate(checkOut)) return null
        val a = fmt().parse(checkIn)!!.time
        val b = fmt().parse(checkOut)!!.time
        return ((b - a) / 86_400_000L).toInt()
    }

    fun addDays(iso: String, days: Int): String {
        val t = fmt().parse(iso)!!.time + days * 86_400_000L
        return fmt().format(java.util.Date(t))
    }

    /** "2026-09" -> "2026-10" */
    fun nextMonth(ym: String): String {
        val y = ym.take(4).toInt()
        val m = ym.substring(5, 7).toInt()
        return if (m == 12) String.format(Locale.US, "%04d-01", y + 1) else String.format(Locale.US, "%04d-%02d", y, m + 1)
    }
}
