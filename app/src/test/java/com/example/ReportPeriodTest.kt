package com.example

import com.example.util.ReportPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPeriodTest {
    private val today = "2026-09-25"

    @Test
    fun filtersByDocumentDate() {
        assertTrue(ReportPeriod.TODAY.contains("2026-09-25", 0L, today))
        assertFalse(ReportPeriod.TODAY.contains("2026-09-24", 0L, today))
        assertTrue(ReportPeriod.THIS_MONTH.contains("2026-09-01", 0L, today))
        assertFalse(ReportPeriod.THIS_MONTH.contains("2026-08-31", 0L, today))
        assertTrue(ReportPeriod.LAST_MONTH.contains("2026-08-31", 0L, today))
        assertTrue(ReportPeriod.ALL.contains(null, 0L, today))
    }

    @Test
    fun missingOrBadDateUsesSavedDay() {
        val saved = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse("2026-09-25")!!.time + 3_600_000
        assertTrue(ReportPeriod.TODAY.contains(null, saved, today))
        assertTrue(ReportPeriod.TODAY.contains("25/09/2026", saved, today))
    }

    @Test
    fun previousMonthCrossesYear() {
        assertEquals("2025-12", ReportPeriod.previousMonth("2026-01-15"))
        assertTrue(ReportPeriod.LAST_MONTH.contains("2025-12-31", 0L, "2026-01-02"))
        assertEquals("เดือนนี้ (ก.ย. 2026)", ReportPeriod.label(ReportPeriod.THIS_MONTH, today))
    }
}
