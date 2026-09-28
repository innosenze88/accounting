package com.example

import com.example.data.network.BusinessPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI is told who the resort is, so a transfer INTO the resort's account is income. */
class BusinessPromptTest {

    @Test
    fun nothingSetMeansNoExtraText() {
        assertEquals("", BusinessPrompt.context(" ", "", "", ""))
    }

    @Test
    fun identityAndRulesAreIncluded() {
        val c = BusinessPrompt.context("วิภูอนันต์ รีสอร์ท", "0-1234-56789-01-2", "081-234-5678", "กสิกร 123-4-56789-0")
        assertTrue(c.contains("วิภูอนันต์ รีสอร์ท"))
        assertTrue(c.contains("0123456789012"))
        assertTrue(c.contains("0812345678"))
        assertTrue(c.contains("กสิกร 123-4-56789-0"))
        assertTrue(c.contains("\"INCOME\""))
        assertTrue(c.contains("null"))
        assertTrue(BusinessPrompt.SLIP_RULES.contains("reference_no"))
    }
}
