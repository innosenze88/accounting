package com.example.data.booking

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookingDao {
    @Query("SELECT * FROM bookings ORDER BY checkIn DESC, id DESC")
    fun observeBookings(): Flow<List<BookingEntity>>

    @Query("SELECT * FROM bookings WHERE id = :id")
    suspend fun getBooking(id: Long): BookingEntity?

    @Query("SELECT * FROM bookings")
    suspend fun getAllBookings(): List<BookingEntity>

    @Insert
    suspend fun insertBooking(b: BookingEntity): Long

    @Update
    suspend fun updateBooking(b: BookingEntity)

    @Query("SELECT * FROM booking_payments ORDER BY date DESC, id DESC")
    fun observePayments(): Flow<List<BookingPaymentEntity>>

    @Query("SELECT * FROM booking_payments WHERE bookingId = :bookingId ORDER BY id")
    suspend fun getPayments(bookingId: Long): List<BookingPaymentEntity>

    @Query("SELECT * FROM booking_payments WHERE id = :id")
    suspend fun getPayment(id: Long): BookingPaymentEntity?

    @Query("SELECT * FROM booking_payments")
    suspend fun getAllPayments(): List<BookingPaymentEntity>

    @Insert
    suspend fun insertPayment(p: BookingPaymentEntity): Long

    @Update
    suspend fun updatePayment(p: BookingPaymentEntity)
}

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallets ORDER BY sortOrder, id")
    fun observeWallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets ORDER BY sortOrder, id")
    suspend fun getWallets(): List<WalletEntity>

    @Insert
    suspend fun insertWallet(w: WalletEntity): Long

    @Update
    suspend fun updateWallet(w: WalletEntity)

    @Query("SELECT * FROM wallet_txns ORDER BY date DESC, id DESC")
    fun observeTxns(): Flow<List<WalletTxnEntity>>

    @Query("SELECT * FROM wallet_txns")
    suspend fun getAllTxns(): List<WalletTxnEntity>

    @Query("SELECT * FROM wallet_txns WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun getTxnsForSource(sourceType: String, sourceId: Long): List<WalletTxnEntity>

    @Insert
    suspend fun insertTxns(t: List<WalletTxnEntity>)

    @Update
    suspend fun updateTxns(t: List<WalletTxnEntity>)

    @Query("UPDATE wallet_txns SET transferredAt = :time WHERE walletId = :walletId AND transferredAt IS NULL AND voidedAt IS NULL AND kind != 'EXPENSE'")
    suspend fun markTransferred(walletId: Long, time: Long)

    @Query("SELECT * FROM wallet_percent_changes ORDER BY changedAt DESC, id DESC")
    fun observePercentChanges(): Flow<List<WalletPercentChangeEntity>>

    @Insert
    suspend fun insertPercentChange(c: WalletPercentChangeEntity): Long

    @Query("SELECT * FROM day_closes ORDER BY date DESC")
    fun observeDayCloses(): Flow<List<DayCloseEntity>>

    @Query("SELECT * FROM day_closes WHERE date = :date")
    suspend fun getDayClose(date: String): DayCloseEntity?

    @Insert
    suspend fun insertDayClose(d: DayCloseEntity)
}
