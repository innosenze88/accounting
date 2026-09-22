package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM extracted_documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<ExtractedDocumentEntity>>

    @Query("SELECT * FROM extracted_documents WHERE id = :id")
    suspend fun getDocumentById(id: Long): ExtractedDocumentEntity?

    @Query("SELECT * FROM extracted_documents WHERE documentType = :type ORDER BY createdAt DESC")
    fun getDocumentsByType(type: String): Flow<List<ExtractedDocumentEntity>>

    @Query("SELECT * FROM extracted_documents WHERE transactionType = :type ORDER BY createdAt DESC")
    fun getDocumentsByTransaction(type: String): Flow<List<ExtractedDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: ExtractedDocumentEntity): Long

    @Delete
    suspend fun deleteDocument(document: ExtractedDocumentEntity)

    @Query("DELETE FROM extracted_documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM extracted_documents")
    suspend fun deleteAll()
}
