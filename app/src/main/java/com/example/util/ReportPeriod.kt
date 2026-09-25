package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Period filter for the dashboard. Uses the date printed on the document (not the scan date). */
enum class ReportPeriod(val titleTh: String) {
    TODAY("วันนี้"),
    THIS_MONTH("เดือนนี้"),
    LAST_MONTH("เดือนก่อน"),
    ALL("ทั้งหมด");

    /**
     * true when a document dated [docDate] (YYYY-MM-DD, may be null) falls in this period.
     * Documents without a date use [createdAtMillis] (the day they were saved).
     * [todayIso] = today as YYYY-MM-DD (passed in so the rule can be tested).
     */
    fun contains(docDate: String?, createdAtMillis: Long, todayIso: String): Boolean {
        if (this == ALL) return true
        val date = docDate?.trim()?.takeIf { ISO.matches(it) } ?: isoDay(createdAtMillis)
        return when (this) {
            TODAY -> date == todayIso
            THIS_MONTH -> date.take(7) == todayIso.take(7)
            LAST_MONTH -> date.take(7) == previousMonth(todayIso)
            ALL -> true
        }
    }

    companion object {
        private val ISO = Regex("""^\d{4}-\d{2}-\d{2}$""")

        fun isoDay(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

        fun today(): String = isoDay(System.currentTimeMillis())

        /** "2026-01-15" -> "2025-12" */
        fun previousMonth(todayIso: String): String {
            val y = todayIso.take(4).toInt()
            val m = todayIso.substring(5, 7).toInt()
            return if (m == 1) String.format(Locale.US, "%04d-12", y - 1)
            else String.format(Locale.US, "%04d-%02d", y, m - 1)
        }

        /** Thai label for the period, e.g. "เดือนนี้ (ก.ย. 2026)". */
        fun label(period: ReportPeriod, todayIso: String): String {
            val months = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")
            fun monthName(ym: String) = "${months[ym.substring(5, 7).toInt() - 1]} ${ym.take(4)}"
            return when (period) {
                TODAY -> "วันนี้ (${todayIso.substring(8).toInt()} ${monthName(todayIso.take(7))})"
                THIS_MONTH -> "เดือนนี้ (${monthName(todayIso.take(7))})"
                LAST_MONTH -> "เดือนก่อน (${monthName(previousMonth(todayIso))})"
                ALL -> "ทั้งหมด"
            }
        }
    }
}
