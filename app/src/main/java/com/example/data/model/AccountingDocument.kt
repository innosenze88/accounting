package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Standard JSON Schema for AI Accountant & OCR Extraction Expert
 */
@JsonClass(generateAdapter = true)
data class AccountingDocumentJson(
    @param:Json(name = "document_type")
    val documentType: String? = null,

    @param:Json(name = "transaction_type")
    val transactionType: String? = null,

    @param:Json(name = "document_no")
    val documentNo: String? = null,

    @param:Json(name = "date")
    val date: String? = null,

    @param:Json(name = "seller_name")
    val sellerName: String? = null,

    @param:Json(name = "seller_tax_id")
    val sellerTaxId: String? = null,

    @param:Json(name = "customer_name")
    val customerName: String? = null,

    @param:Json(name = "customer_tax_id")
    val customerTaxId: String? = null,

    @param:Json(name = "subtotal")
    val subtotal: Double? = null,

    @param:Json(name = "vat_amount")
    val vatAmount: Double? = null,

    @param:Json(name = "total_amount")
    val totalAmount: Double? = null,

    @param:Json(name = "deposit_amount")
    val depositAmount: Double? = null,

    @param:Json(name = "payment_method")
    val paymentMethod: String? = null,

    @param:Json(name = "line_items")
    val lineItems: List<LineItemJson>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class LineItemJson(
    @param:Json(name = "item_name")
    val itemName: String? = null,

    @param:Json(name = "quantity")
    val quantity: Double? = null,

    @param:Json(name = "unit_price")
    val unitPrice: Double? = null,

    @param:Json(name = "total_price")
    val totalPrice: Double? = null
)

/**
 * Document Classification Types
 */
enum class DocumentType(val code: String, val titleTh: String, val descriptionTh: String) {
    RECEIPT("RECEIPT", "ใบเสร็จรับเงิน", "ใบเสร็จรับเงิน / ใบเสร็จรับเงินออกให้ลูกค้า"),
    PAYMENT_VOUCHER("PAYMENT_VOUCHER", "ใบสำคัญจ่าย", "ใบสำคัญจ่าย / บิลจ่ายเงิน"),
    UTILITY_BILL("UTILITY_BILL", "บิลค่าน้ำ/ค่าไฟ", "บิลค่าน้ำ / ค่าไฟฟ้า / ค่าโทรศัพท์ / อินเทอร์เน็ต"),
    ADVANCE_DEPOSIT("ADVANCE_DEPOSIT", "ใบเสร็จมัดจำล่วงหน้า", "ใบเสร็จรับเงินมัดจำล่วงหน้า / เงินรับฝาก"),
    OTHER("OTHER", "เอกสารอื่นๆ", "เอกสารอื่นๆ ที่ไม่เข้าพวก");

    companion object {
        fun fromCode(code: String?): DocumentType {
            return entries.find { it.code.equals(code?.trim(), ignoreCase = true) } ?: OTHER
        }
    }
}

/**
 * Transaction Types
 */
enum class TransactionType(val code: String, val titleTh: String, val descriptionTh: String) {
    INCOME("INCOME", "รายรับ", "เมื่อเอกสารแสดงว่าเราเป็นผู้รับเงิน"),
    EXPENSE("EXPENSE", "รายจ่าย", "เมื่อเอกสารแสดงว่าเราเป็นผู้จ่ายเงิน");

    companion object {
        fun fromCode(code: String?): TransactionType? {
            return entries.find { it.code.equals(code?.trim(), ignoreCase = true) }
        }
    }
}
