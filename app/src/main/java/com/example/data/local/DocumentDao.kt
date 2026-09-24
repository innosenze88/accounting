package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM extracted_documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<ExtractedDocumentEntity>>

    @Query("SELECT * FROM extracted_documents WHERE id = :id")
    suspend fun getDocumentById(id: Long): ExtractedDocumentEntity?

    @Query("SELECT * FROM extracted_documents WHERE id = :id")
    fun observeDocumentById(id: Long): Flow<ExtractedDocumentEntity?>

    @Query("SELECT * FROM extracted_documents WHERE documentType = :type ORDER BY createdAt DESC")
    fun getDocumentsByType(type: String): Flow<List<ExtractedDocumentEntity>>

    @Query("SELECT * FROM extracted_documents WHERE transactionType = :type ORDER BY createdAt DESC")
    fun getDocumentsByTransaction(type: String): Flow<List<ExtractedDocumentEntity>>

    @Query("SELECT * FROM extracted_documents WHERE documentNo = :documentNo")
    suspend fun getByDocumentNo(documentNo: String): List<ExtractedDocumentEntity>

    @Query("SELECT imagePath FROM extracted_documents WHERE imagePath IS NOT NULL")
    suspend fun getAllImagePaths(): List<String>

    @Query("SELECT sourceFilePath FROM extracted_documents WHERE sourceFilePath IS NOT NULL")
    suspend fun getAllSourceFilePaths(): List<String>

    /** Verified real documents that have not been sent to Google Sheets yet (or were edited after sending). */
    @Query(
        "SELECT * FROM extracted_documents WHERE status = 'VERIFIED' AND sampleId IS NULL " +
            "AND (sheetSyncedAt IS NULL OR sheetSyncedAt < verifiedAt) ORDER BY createdAt"
    )
    suspend fun getVerifiedNotSynced(): List<ExtractedDocumentEntity>

    @Query("UPDATE extracted_documents SET sheetSyncedAt = :time WHERE id = :id")
    suspend fun markSheetSynced(id: Long, time: Long)

    // Default strategy is ABORT: an insert must never silently overwrite an existing row.
    @Insert
    suspend fun insertDocument(document: ExtractedDocumentEntity): Long

    @Update
    suspend fun updateDocument(document: ExtractedDocumentEntity)

    @Delete
    suspend fun deleteDocument(document: ExtractedDocumentEntity)

    @Query("DELETE FROM extracted_documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM extracted_documents")
    suspend fun deleteAll()
}
