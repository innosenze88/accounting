package com.example.data.docs

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A document the resort issued (receipt, invoice, tax invoice, guest card...). Never deleted, only voided. */
@Entity(tableName = "issued_documents")
data class IssuedDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val number: String,
    val issueDate: String,
    val bookingId: Long? = null,
    val paymentId: Long? = null,
    val customerName: String,
    val customerAddress: String? = null,
    val customerTaxId: String? = null,
    /** JSON array of {description, qty, unitPrice, amount}. */
    val itemsJson: String,
    val subtotal: Double,
    val vatAmount: Double,
    val total: Double,
    val note: String? = null,
    val pdfPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** How many times it was shared/printed again (copies are marked "สำเนา"). */
    val copies: Int = 0,
    val voidedAt: Long? = null,
    val voidReason: String? = null
) {
    val docType: IssuedDocType? get() = IssuedDocType.fromCode(type)
    val isActive: Boolean get() = voidedAt == null
}

/** Last used running number per type and month (key = e.g. "RC2609"). */
@Entity(tableName = "doc_sequences")
data class DocSequenceEntity(
    @PrimaryKey val key: String,
    val lastNo: Int
)
