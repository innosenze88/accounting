package com.example.data.repository

import com.example.data.local.DocumentDao
import com.example.data.local.DocumentImageStore
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.canDelete
import com.example.data.local.canVoid
import com.example.data.local.documentStatus
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.DocumentStatus
import kotlinx.coroutines.flow.Flow

class DocumentRepository(
    private val documentDao: DocumentDao,
    private val imageStore: DocumentImageStore
) {

    val allDocuments: Flow<List<ExtractedDocumentEntity>> = documentDao.getAllDocuments()

    fun observeDocument(id: Long): Flow<ExtractedDocumentEntity?> = documentDao.observeDocumentById(id)

    suspend fun getDocumentById(id: Long): ExtractedDocumentEntity? {
        return documentDao.getDocumentById(id)
    }

    /**
     * Saves an AI extraction as PENDING, together with its original image.
     * PENDING rows are never counted in totals until a person verifies them.
     */
    suspend fun savePendingDocument(
        document: AccountingDocumentJson,
        rawJson: String,
        imagePath: String?,
        sourceFilePath: String? = null,
        contentHash: String? = null
    ): Long {
        val entity = ExtractedDocumentEntity.fromModel(
            document, rawJson, sampleId = null, imagePath = imagePath, sourceFilePath = sourceFilePath,
            contentHash = contentHash
        )
        return documentDao.insertDocument(entity)
    }

    /**
     * Saves a document that a person has already checked on screen (e.g. the totals of an eZee report)
     * directly as VERIFIED, so it counts in the totals right away.
     */
    suspend fun saveVerifiedDocument(document: AccountingDocumentJson, rawJson: String, sourceFilePath: String?): Long {
        val now = System.currentTimeMillis()
        val entity = ExtractedDocumentEntity.fromModel(
            document, rawJson, sampleId = null, imagePath = null, sourceFilePath = sourceFilePath
        ).copy(status = DocumentStatus.VERIFIED.code, verifiedAt = now, updatedAt = now)
        return documentDao.insertDocument(entity)
    }

    suspend fun getByDocumentNo(documentNo: String): List<ExtractedDocumentEntity> = documentDao.getByDocumentNo(documentNo)

    /** Deletes only the database row (the stored file may be shared with an imported report). */
    suspend fun deleteRowOnly(id: Long) = documentDao.deleteById(id)

    /** Stores the person-checked values and marks the document VERIFIED. rawJson (AI output) is kept. */
    suspend fun verifyDocument(id: Long, reviewed: AccountingDocumentJson) {
        val current = documentDao.getDocumentById(id) ?: return
        val now = System.currentTimeMillis()
        documentDao.updateDocument(
            current.withFieldsFrom(reviewed).copy(
                status = DocumentStatus.VERIFIED.code,
                verifiedAt = now,
                updatedAt = now
            )
        )
    }

    /**
     * Moves a document back to PENDING / REJECTED. A voided document stays voided.
     * [ExtractedDocumentEntity.verifiedAt] is kept on purpose: it records that the document was counted once,
     * so it can only be voided later, never deleted.
     */
    suspend fun setStatus(id: Long, status: DocumentStatus) {
        require(status != DocumentStatus.VOIDED) { "use voidDocument()" }
        val current = documentDao.getDocumentById(id) ?: return
        if (current.documentStatus() == DocumentStatus.VOIDED) return
        documentDao.updateDocument(current.copy(status = status.code, updatedAt = System.currentTimeMillis()))
    }

    /** Cancels a counted document with a reason. The row, image and original file are kept as a record. */
    suspend fun voidDocument(id: Long, reason: String): ExtractedDocumentEntity? {
        val current = documentDao.getDocumentById(id) ?: return null
        require(current.canVoid()) { "เอกสารนี้ยกเลิกไม่ได้" }
        require(reason.isNotBlank()) { "ต้องใส่เหตุผล" }
        val now = System.currentTimeMillis()
        val voided = current.copy(
            status = DocumentStatus.VOIDED.code,
            voidReason = reason.trim(),
            voidedAt = now,
            updatedAt = now
        )
        documentDao.updateDocument(voided)
        return voided
    }

    suspend fun getAllOnce(): List<ExtractedDocumentEntity> = documentDao.getAllOnce()

    suspend fun getByContentHash(hash: String): List<ExtractedDocumentEntity> = documentDao.getByContentHash(hash)

    suspend fun getVoidedNotSynced(): List<ExtractedDocumentEntity> = documentDao.getVoidedNotSynced()

    suspend fun saveImage(bitmap: android.graphics.Bitmap): String = imageStore.save(bitmap)

    suspend fun loadImage(path: String?): android.graphics.Bitmap? = imageStore.load(path)

    suspend fun saveFile(bytes: ByteArray, extension: String): String = imageStore.saveBytes(bytes, extension)

    suspend fun readFile(path: String?): ByteArray? = imageStore.readBytes(path)

    suspend fun getVerifiedNotSynced(): List<ExtractedDocumentEntity> = documentDao.getVerifiedNotSynced()

    suspend fun markSheetSynced(id: Long) = documentDao.markSheetSynced(id, System.currentTimeMillis())

    /** Deletes a document that was never counted. Counted documents must be voided instead. */
    suspend fun deleteDocument(id: Long) {
        val entity = documentDao.getDocumentById(id)
        check(entity == null || entity.canDelete()) { "เอกสารที่ยืนยันแล้วลบไม่ได้ ให้ใช้ \"ยกเลิกรายการ\"" }
        documentDao.deleteById(id)
        imageStore.delete(entity?.imagePath)
        imageStore.delete(entity?.sourceFilePath)
    }

    /** Deletes every document that was never counted (counted ones are kept). */
    suspend fun deleteAllDocuments() {
        documentDao.getAllOnce().filter { it.canDelete() }.forEach { deleteDocument(it.id) }
    }
}
