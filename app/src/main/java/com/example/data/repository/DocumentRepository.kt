package com.example.data.repository

import com.example.data.local.DocumentDao
import com.example.data.local.DocumentImageStore
import com.example.data.local.ExtractedDocumentEntity
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
        imagePath: String?
    ): Long {
        val entity = ExtractedDocumentEntity.fromModel(document, rawJson, sampleId = null, imagePath = imagePath)
        return documentDao.insertDocument(entity)
    }

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

    suspend fun setStatus(id: Long, status: DocumentStatus) {
        val current = documentDao.getDocumentById(id) ?: return
        documentDao.updateDocument(
            current.copy(
                status = status.code,
                verifiedAt = if (status == DocumentStatus.VERIFIED) current.verifiedAt else null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveImage(bitmap: android.graphics.Bitmap): String = imageStore.save(bitmap)

    suspend fun loadImage(path: String?): android.graphics.Bitmap? = imageStore.load(path)

    suspend fun deleteDocument(id: Long) {
        val entity = documentDao.getDocumentById(id)
        documentDao.deleteById(id)
        imageStore.delete(entity?.imagePath)
    }

    suspend fun deleteAllDocuments() {
        val paths = documentDao.getAllImagePaths()
        documentDao.deleteAll()
        paths.forEach { imageStore.delete(it) }
    }
}
