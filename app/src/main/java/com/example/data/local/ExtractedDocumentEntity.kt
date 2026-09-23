package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.DocumentStatus
import com.example.data.model.LineItemJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

@Entity(tableName = "extracted_documents")
data class ExtractedDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentType: String,
    val transactionType: String,
    val documentNo: String?,
    val date: String?,
    val sellerName: String?,
    val sellerTaxId: String?,
    val customerName: String?,
    val customerTaxId: String?,
    val subtotal: Double?,
    val vatAmount: Double?,
    val totalAmount: Double?,
    val depositAmount: Double?,
    val paymentMethod: String?,
    val lineItemsJson: String,
    /** Original AI output. Never overwritten, even after a person edits the fields. */
    val rawJson: String,
    val sampleId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // ---- Added in DB version 2 ----
    /** See [DocumentStatus]. Only VERIFIED rows are counted in accounting totals. */
    val status: String = DocumentStatus.PENDING.code,
    /** Absolute path of the original document image stored in app-private storage. */
    val imagePath: String? = null,
    val verifiedAt: Long? = null,
    val updatedAt: Long? = null
) {
    fun toAccountingDocument(): AccountingDocumentJson {
        val parsedItems = try {
            lineItemsAdapter.fromJson(lineItemsJson) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        return AccountingDocumentJson(
            documentType = documentType,
            transactionType = transactionType,
            documentNo = documentNo,
            date = date,
            sellerName = sellerName,
            sellerTaxId = sellerTaxId,
            customerName = customerName,
            customerTaxId = customerTaxId,
            subtotal = subtotal,
            vatAmount = vatAmount,
            totalAmount = totalAmount,
            depositAmount = depositAmount,
            paymentMethod = paymentMethod,
            lineItems = parsedItems
        )
    }

    /** Returns a copy whose accounting fields are replaced by [model] (rawJson is kept as-is). */
    fun withFieldsFrom(model: AccountingDocumentJson): ExtractedDocumentEntity = copy(
        documentType = model.documentType ?: "OTHER",
        transactionType = model.transactionType ?: "EXPENSE",
        documentNo = model.documentNo,
        date = model.date,
        sellerName = model.sellerName,
        sellerTaxId = model.sellerTaxId,
        customerName = model.customerName,
        customerTaxId = model.customerTaxId,
        subtotal = model.subtotal,
        vatAmount = model.vatAmount,
        totalAmount = model.totalAmount,
        depositAmount = model.depositAmount,
        paymentMethod = model.paymentMethod,
        lineItemsJson = lineItemsAdapter.toJson(model.lineItems ?: emptyList())
    )

    companion object {
        private val lineItemsAdapter: JsonAdapter<List<LineItemJson>> by lazy {
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val listType = Types.newParameterizedType(List::class.java, LineItemJson::class.java)
            moshi.adapter(listType)
        }

        fun fromModel(
            model: AccountingDocumentJson,
            rawJson: String,
            sampleId: String? = null,
            imagePath: String? = null
        ): ExtractedDocumentEntity {
            return ExtractedDocumentEntity(
                documentType = model.documentType ?: "OTHER",
                transactionType = model.transactionType ?: "EXPENSE",
                documentNo = model.documentNo,
                date = model.date,
                sellerName = model.sellerName,
                sellerTaxId = model.sellerTaxId,
                customerName = model.customerName,
                customerTaxId = model.customerTaxId,
                subtotal = model.subtotal,
                vatAmount = model.vatAmount,
                totalAmount = model.totalAmount,
                depositAmount = model.depositAmount,
                paymentMethod = model.paymentMethod,
                lineItemsJson = lineItemsAdapter.toJson(model.lineItems ?: emptyList()),
                rawJson = rawJson,
                sampleId = sampleId,
                status = DocumentStatus.PENDING.code,
                imagePath = imagePath
            )
        }
    }
}

/** Status parsed from the stored code. */
fun ExtractedDocumentEntity.documentStatus(): DocumentStatus = DocumentStatus.fromCode(status)

/**
 * True only for real (non-sample) documents that a person has verified.
 * Every accounting total in the app must be built from rows that pass this check.
 */
fun ExtractedDocumentEntity.countsInAccounting(): Boolean =
    sampleId == null && documentStatus() == DocumentStatus.VERIFIED
