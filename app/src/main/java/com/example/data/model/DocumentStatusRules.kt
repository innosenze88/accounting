package com.example.data.model

/**
 * Which status changes are allowed for a scanned document. Pure Kotlin (unit-tested).
 *
 *  - PENDING  -> VERIFIED (counted) or REJECTED
 *  - REJECTED -> PENDING (look at it again) or VERIFIED
 *  - VERIFIED -> only VOIDED, with a reason (voidDocument). It never goes back to PENDING/REJECTED,
 *    otherwise money would disappear from the totals without a record and Google Sheets would still show it.
 *  - VOIDED   -> nothing (kept forever as a record)
 */
object DocumentStatusRules {

    /** null = the change is allowed, otherwise the Thai reason why not. */
    fun statusChangeProblem(from: DocumentStatus, to: DocumentStatus): String? = when {
        to == DocumentStatus.VOIDED -> "ใช้ \"ยกเลิกรายการ\" พร้อมเหตุผล"
        from == DocumentStatus.VOIDED -> "เอกสารนี้ยกเลิกไปแล้ว"
        from == DocumentStatus.VERIFIED && to != DocumentStatus.VERIFIED ->
            "เอกสารนี้นับเข้ายอดแล้ว — ถ้าข้อมูลผิด ให้กด \"ยกเลิกรายการ\" (ใส่เหตุผล) แล้วสแกน/นำเข้าไฟล์ใหม่"
        else -> null
    }

    /** null = the document may be verified (counted) now. */
    fun verifyProblem(current: DocumentStatus): String? = when (current) {
        DocumentStatus.VERIFIED -> "เอกสารนี้ยืนยันไปแล้ว"
        DocumentStatus.VOIDED -> "เอกสารนี้ยกเลิกไปแล้ว"
        else -> null
    }
}
