package com.example.data.model

import com.example.data.wallet.WalletMath

/**
 * The rules a document must pass before it counts in the accounts. The review form, bulk approval and
 * [com.example.data.repository.DocumentRepository.verifyDocument] all use this, so no path lets through
 * something another path would refuse.
 */
object DocumentValidation {

    /** Every blocking problem, in the order shown to the person (empty = OK). */
    fun problems(
        transactionType: String?,
        date: String?,
        subtotal: Double?,
        vat: Double?,
        total: Double?,
        deposit: Double?
    ): List<String> = buildList {
        if (TransactionType.fromCode(transactionType) == null) add("ยังไม่รู้ว่ารายรับหรือรายจ่าย")
        when {
            total == null -> add("ไม่มียอดรวม")
            !total.isFinite() || total < 0 -> add("ยอดรวมไม่ถูกต้อง")
            total == 0.0 -> add("ยอดรวมเป็น 0")
        }
        listOf("มูลค่าก่อน VAT" to subtotal, "VAT" to vat, "มัดจำ" to deposit).forEach { (label, v) ->
            when {
                v == null -> Unit
                !v.isFinite() -> add("$label ไม่ใช่ตัวเลข")
                v < 0 -> add("$label ต้องไม่ติดลบ")
            }
        }
        val d = date?.trim()
        if (!d.isNullOrEmpty()) {
            when {
                (d.take(4).toIntOrNull() ?: 0) > 2400 -> add("ปีเป็น พ.ศ. — ต้องเป็น ค.ศ.")
                !WalletMath.isIsoDate(d) -> add("วันที่ไม่ถูกต้อง")
            }
        }
    }

    fun problems(doc: AccountingDocumentJson): List<String> =
        problems(doc.transactionType, doc.date, doc.subtotal, doc.vatAmount, doc.totalAmount, doc.depositAmount)
}
