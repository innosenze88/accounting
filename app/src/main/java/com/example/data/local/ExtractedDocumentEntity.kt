package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.LineItemJson
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
    val rawJson: String,
    val sampleId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toAccountingDocument(): AccountingDocumentJson {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val listType = Types.newParameterizedType(List::class.java, LineItemJson::class.java)
        val adapter = moshi.adapter<List<LineItemJson>>(listType)
        val parsedItems = try {
            adapter.fromJson(lineItemsJson) ?: emptyList()
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

    companion object {
        fun fromModel(model: AccountingDocumentJson, rawJson: String, sampleId: String? = null): ExtractedDocumentEntity {
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val listType = Types.newParameterizedType(List::class.java, LineItemJson::class.java)
            val adapter = moshi.adapter<List<LineItemJson>>(listType)
            val lineItemsStr = adapter.toJson(model.lineItems ?: emptyList())

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
                lineItemsJson = lineItemsStr,
                rawJson = rawJson,
                sampleId = sampleId
            )
        }
    }
}
