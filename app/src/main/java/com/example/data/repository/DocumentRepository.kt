package com.example.data.repository

import com.example.data.local.DocumentDao
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.model.AccountingDocumentJson
import kotlinx.coroutines.flow.Flow

class DocumentRepository(private val documentDao: DocumentDao) {

    val allDocuments: Flow<List<ExtractedDocumentEntity>> = documentDao.getAllDocuments()

    suspend fun getDocumentById(id: Long): ExtractedDocumentEntity? {
        return documentDao.getDocumentById(id)
    }

    suspend fun saveDocument(document: AccountingDocumentJson, rawJson: String, sampleId: String? = null): Long {
        val entity = ExtractedDocumentEntity.fromModel(document, rawJson, sampleId)
        return documentDao.insertDocument(entity)
    }

    suspend fun deleteDocument(id: Long) {
        documentDao.deleteById(id)
    }

    suspend fun deleteAllDocuments() {
        documentDao.deleteAll()
    }
}
