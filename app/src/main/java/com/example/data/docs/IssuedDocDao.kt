package com.example.data.docs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IssuedDocDao {
    @Query("SELECT * FROM issued_documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IssuedDocumentEntity>>

    @Query("SELECT * FROM issued_documents WHERE id = :id")
    suspend fun get(id: Long): IssuedDocumentEntity?

    @Query("SELECT * FROM issued_documents WHERE bookingId = :bookingId ORDER BY createdAt")
    suspend fun forBooking(bookingId: Long): List<IssuedDocumentEntity>

    @Insert
    suspend fun insert(d: IssuedDocumentEntity): Long

    @Update
    suspend fun update(d: IssuedDocumentEntity)

    @Query("SELECT * FROM doc_sequences WHERE `key` = :key")
    suspend fun getSequence(key: String): DocSequenceEntity?

    @Insert
    suspend fun insertSequence(s: DocSequenceEntity)

    @Update
    suspend fun updateSequence(s: DocSequenceEntity)
}
