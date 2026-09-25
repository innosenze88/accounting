package com.example

import com.example.data.docs.DocNumbering
import com.example.data.docs.IssuedDocType
import com.example.data.payment.PromptPay
import com.example.data.wallet.WalletMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wallet split, PromptPay QR and document numbering rules. */
class MoneyRulesTest {

    private val wallets = listOf(1L to 30.0, 2L to 10.0, 3L to 10.0, 4L to 10.0, 5L to 10.0, 6L to 5.0, 7L to 15.0, 8L to 10.0)

    @Test
    fun allocationAlwaysAddsUpExactly() {
        for (amount in listOf(1500.0, 1019.26, 0.07, 3689.26, 99999.99)) {
            val shares = WalletMath.allocate(amount, wallets, remainderTo = 8L)
            assertEquals(WalletMath.toSatang(amount), shares.sumOf { WalletMath.toSatang(it.amount) })
        }
        val s = WalletMath.allocate(1000.0, wallets, 8L).associate { it.walletId to it.amount }
        assertEquals(300.0, s[1]!!, 0.0)
        assertEquals(50.0, s[6]!!, 0.0)
        assertEquals(100.0, s[8]!!, 0.0)
    }

    @Test
    fun remainderGoesToSavings() {
        // 0.07 baht = 7 satang: 30 % = 2.1 -> 2, 15 % = 1.05 -> 1, the rest -> 0; leftover 4 satang to savings (id 8)
        val s = WalletMath.allocate(0.07, wallets, 8L).associate { it.walletId to it.amount }
        assertEquals(0.02, s[1]!!, 0.0)
        assertEquals(0.01, s[7]!!, 0.0)
        assertEquals(0.04, s[8]!!, 1e-9)
    }

    @Test
    fun percentsMustTotal100() {
        assertNull(WalletMath.percentProblem(wallets.map { it.second }))
        assertTrue(WalletMath.percentProblem(listOf(50.0, 40.0))!!.contains("100"))
        assertTrue(WalletMath.percentProblem(listOf(120.0, -20.0)) != null)
    }

    @Test
    fun cancellationRefundsHalf() {
        assertEquals(750.0 to 750.0, WalletMath.cancellationSplit(1500.0, 50.0))
        val (r, k) = WalletMath.cancellationSplit(1000.01, 50.0)
        assertEquals(100001L, WalletMath.toSatang(r) + WalletMath.toSatang(k))
    }

    @Test
    fun pendingTransfersNetPerWallet() {
        val m = WalletMath.pendingTransfers(listOf(0L to 1500.0, 0L to -1500.0, 1L to 450.0, 1L to 30.0, 8L to 150.0))
        assertEquals(mapOf(1L to 480.0, 8L to 150.0), m)
    }

    @Test
    fun nightsAndDates() {
        assertEquals(2, WalletMath.nights("2026-09-30", "2026-10-02"))
        assertEquals(null, WalletMath.nights("2026-02-30", "2026-03-01"))
        assertEquals("2026-10-01", WalletMath.addDays("2026-09-30", 1))
        assertEquals("2027-01", WalletMath.nextMonth("2026-12"))
    }

    @Test
    fun promptPayPayload() {
        assertEquals("29B1", PromptPay.crc16("123456789")) // CRC-16/CCITT-FALSE check value
        val p = PromptPay.payload("081-234-5678")
        assertTrue(p.startsWith("000201010211" + "2937" + "0016A000000677010111" + "01130066812345678"))
        assertTrue(p.contains("5802TH5303764"))
        assertEquals(PromptPay.crc16(p.dropLast(4)), p.takeLast(4))
        val withAmount = PromptPay.payload("0812345678", 1500.0)
        assertTrue(withAmount.startsWith("000201010212"))
        assertTrue(withAmount.contains("54071500.00"))
        val taxId = PromptPay.payload("1234567890123")
        assertTrue(taxId.contains("02131234567890123"))
    }

    @Test
    fun documentNumbersAndBahtText() {
        assertEquals("RC2609-0001", DocNumbering.format(IssuedDocType.RECEIPT, "2026-09-25", 1))
        assertEquals("TX2701-0123", DocNumbering.format(IssuedDocType.TAX_INVOICE, "2027-01-02", 123))
        assertEquals("หนึ่งพันห้าร้อยบาทถ้วน", DocNumbering.bahtText(1500.0))
        assertEquals("สิบเอ็ดบาทห้าสิบสตางค์", DocNumbering.bahtText(11.5))
        assertEquals("สองหมื่นหนึ่งร้อยยี่สิบเอ็ดบาทถ้วน", DocNumbering.bahtText(20121.0))
        assertEquals("หนึ่งล้านเอ็ดบาทถ้วน", DocNumbering.bahtText(1_000_001.0))
        assertEquals("ศูนย์บาทถ้วน", DocNumbering.bahtText(0.0))
    }
}

class VatMathTest {
    @Test
    fun vatInsideAndOnTop() {
        val inc = com.example.data.docs.VatMath.split(1070.0, 7.0, inclusive = true)
        assertEquals(1000.0, inc.subtotal, 0.0)
        assertEquals(70.0, inc.vat, 0.0)
        assertEquals(1070.0, inc.total, 0.0)
        val top = com.example.data.docs.VatMath.split(1000.0, 7.0, inclusive = false)
        assertEquals(1070.0, top.total, 0.0)
        val none = com.example.data.docs.VatMath.split(1500.0, 0.0, inclusive = true)
        assertEquals(0.0, none.vat, 0.0)
        // Parts always add up to the total
        val odd = com.example.data.docs.VatMath.split(1019.26, 7.0, inclusive = true)
        assertEquals(Math.round(odd.total * 100), Math.round(odd.subtotal * 100) + Math.round(odd.vat * 100))
    }
}
