package com.example.data.docs

import java.util.Locale

/** Kinds of documents the app issues, with their running-number prefix. */
enum class IssuedDocType(val code: String, val prefix: String, val titleTh: String, val titleEn: String) {
    RECEIPT("RECEIPT", "RC", "ใบเสร็จรับเงิน", "RECEIPT"),
    DEPOSIT_RECEIPT("DEPOSIT_RECEIPT", "DP", "ใบรับเงินมัดจำ", "DEPOSIT RECEIPT"),
    INVOICE("INVOICE", "IV", "ใบแจ้งหนี้", "INVOICE"),
    QUOTATION("QUOTATION", "QT", "ใบเสนอราคา", "QUOTATION"),
    TAX_INVOICE("TAX_INVOICE", "TX", "ใบกำกับภาษี / ใบเสร็จรับเงิน", "TAX INVOICE / RECEIPT"),
    PAYMENT_VOUCHER("PAYMENT_VOUCHER", "PV", "ใบสำคัญจ่าย", "PAYMENT VOUCHER"),
    GUEST_CARD("GUEST_CARD", "GR", "บัตรทะเบียนผู้พัก", "GUEST REGISTRATION");

    companion object {
        fun fromCode(code: String?): IssuedDocType? = entries.find { it.code == code }
    }
}

/** Running numbers: RC2609-0001 = receipt no. 1 of September 2026. They restart every month, never repeat. */
object DocNumbering {

    /** "2026-09-25" -> "2609" */
    fun period(isoDate: String): String = isoDate.substring(2, 4) + isoDate.substring(5, 7)

    /** Key of the counter for one type in one month. */
    fun counterKey(type: IssuedDocType, isoDate: String): String = "${type.prefix}${period(isoDate)}"

    fun format(type: IssuedDocType, isoDate: String, seq: Int): String =
        "${counterKey(type, isoDate)}-${String.format(Locale.US, "%04d", seq)}"

    /** Thai baht text for amounts on receipts: 1,500.50 -> "หนึ่งพันห้าร้อยบาทห้าสิบสตางค์". */
    fun bahtText(amount: Double): String {
        val satang = Math.round(amount * 100)
        val baht = satang / 100
        val st = (satang % 100).toInt()
        val bahtWords = if (baht == 0L) "ศูนย์" else thaiNumber(baht)
        return if (st == 0) "${bahtWords}บาทถ้วน" else "${bahtWords}บาท${thaiNumber(st.toLong())}สตางค์"
    }

    private val DIGITS = listOf("", "หนึ่ง", "สอง", "สาม", "สี่", "ห้า", "หก", "เจ็ด", "แปด", "เก้า")
    private val PLACES = listOf("", "สิบ", "ร้อย", "พัน", "หมื่น", "แสน")

    private fun thaiNumber(n: Long, afterHigher: Boolean = false): String {
        if (n == 0L) return ""
        val millions = n / 1_000_000
        val rest = n % 1_000_000
        val head = if (millions > 0) thaiNumber(millions) + "ล้าน" else ""
        if (rest == 0L) return head
        val s = rest.toString()
        val higher = afterHigher || millions > 0
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            val d = ch - '0'
            val place = s.length - i - 1
            if (d == 0) continue
            val word = when {
                place == 0 && d == 1 && (s.length > 1 || higher) -> "เอ็ด"
                place == 1 && d == 1 -> ""
                place == 1 && d == 2 -> "ยี่"
                else -> DIGITS[d]
            }
            sb.append(word).append(PLACES[place])
        }
        return head + sb
    }
}

/** Amounts printed on a document. */
data class VatSplit(val subtotal: Double, val vat: Double, val total: Double)

/** VAT for documents. Hotels usually quote prices that already include VAT. */
object VatMath {
    /**
     * [amount] is the price the guest pays when [inclusive] (VAT inside), otherwise the price before VAT.
     * No VAT at all when [rate] is 0 (not VAT registered).
     */
    fun split(amount: Double, rate: Double, inclusive: Boolean): VatSplit {
        val a = Math.round(amount * 100)
        if (rate <= 0.0) return VatSplit(a / 100.0, 0.0, a / 100.0)
        return if (inclusive) {
            val vat = Math.round(a * rate / (100.0 + rate))
            VatSplit((a - vat) / 100.0, vat / 100.0, a / 100.0)
        } else {
            val vat = Math.round(a * rate / 100.0)
            VatSplit(a / 100.0, vat / 100.0, (a + vat) / 100.0)
        }
    }
}
