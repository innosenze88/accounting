package com.example.data.network

/**
 * Tells the AI who "we" are, so it can decide if a slip is money coming IN (income) or going OUT (expense).
 * Without this the AI only guesses the direction of a bank transfer slip. Pure Kotlin (unit-tested).
 */
object BusinessPrompt {

    /**
     * Extra system text with the resort's identity, or "" when nothing is set.
     * [bankAccounts] = free text, e.g. "กสิกร 123-4-56789-0 (MAKE), กรุงไทย 987-6-54321-0".
     */
    fun context(name: String, taxId: String, promptPayId: String, bankAccounts: String): String {
        val lines = buildList {
            name.trim().takeIf { it.isNotEmpty() }?.let { add("- ชื่อกิจการ: $it") }
            taxId.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { add("- เลขประจำตัวผู้เสียภาษี: $it") }
            promptPayId.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { add("- พร้อมเพย์: $it") }
            bankAccounts.trim().takeIf { it.isNotEmpty() }?.let { add("- บัญชีธนาคารของเรา: $it") }
        }
        if (lines.isEmpty()) return ""
        return """

### ข้อมูลกิจการของเรา ("เรา" ในกฎข้อ 2)
${lines.joinToString("\n")}

กฎตัดสินรายรับ/รายจ่ายจากข้อมูลนี้:
   - ถ้าผู้รับเงิน / บัญชีปลายทาง / พร้อมเพย์ปลายทาง ตรงกับข้อมูลของเรา (ชื่อหรือเลขบัญชีบางส่วนที่แสดง เช่น xxx-x-x6789-x) -> "INCOME"
   - ถ้าเราเป็นผู้โอน / ผู้ซื้อ / ผู้ถูกเรียกเก็บเงิน -> "EXPENSE"
   - ถ้าเทียบแล้วไม่แน่ใจ ให้ transaction_type เป็น null (คนจะเลือกเอง) ห้ามเดา"""
    }

    /** Rules for bank transfer slips, appended to the main instruction (fields reference_no and TRANSFER_SLIP). */
    const val SLIP_RULES = """

### สลิปโอนเงิน (TRANSFER_SLIP)
   - document_type = "TRANSFER_SLIP" สำหรับสลิปโอนเงิน / สลิปจากแอปธนาคาร / หลักฐานการจ่าย QR พร้อมเพย์
   - reference_no : "เลขที่รายการ", "รหัสอ้างอิง", "Ref", "Reference No.", "Transaction ID" (คัดลอกตามที่พิมพ์ ห้ามเดา) — เอกสารอื่นที่ไม่มีให้เป็น null
   - seller_name = ชื่อผู้รับเงิน (บัญชีปลายทาง), customer_name = ชื่อผู้โอน (บัญชีต้นทาง)
   - total_amount = จำนวนเงินที่โอน, payment_method = "โอนเงิน" หรือ "พร้อมเพย์"
   - date = วันที่ทำรายการบนสลิป"""
}
