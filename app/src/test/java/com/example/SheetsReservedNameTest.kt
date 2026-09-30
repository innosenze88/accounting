package com.example

import com.example.data.sheets.SheetsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetsReservedNameTest {
    @Test
    fun appSheetsCannotBeImportTargets() {
        listOf("Accounting", " accounting ", "Voided", "LINEINBOX", "eZee_Reports", "ezee_revenue").forEach {
            assertTrue(it, SheetsClient.isReservedSheet(it))
        }
    }

    @Test
    fun ordinarySheetNamesAreAllowed() {
        listOf("Import", "Bank statement", "Accounting 2026", "eZee").forEach {
            assertFalse(it, SheetsClient.isReservedSheet(it))
        }
    }

    @Test
    fun formulaLikeColumnNamesAreNotRunBySheets() {
        assertEquals("_=HYPERLINK(\"http://x\")", SheetsClient.safeHeader("=HYPERLINK(\"http://x\")"))
        assertEquals("_+1", SheetsClient.safeHeader("+1"))
        assertEquals("_-x", SheetsClient.safeHeader("-x"))
        assertEquals("_@a", SheetsClient.safeHeader("@a"))
        assertEquals("ยอดเงิน", SheetsClient.safeHeader("ยอดเงิน"))
        assertEquals("", SheetsClient.safeHeader(""))
    }
}
