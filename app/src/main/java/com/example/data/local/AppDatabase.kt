package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.booking.BookingDao
import com.example.data.booking.BookingEntity
import com.example.data.booking.BookingPaymentEntity
import com.example.data.booking.DayCloseEntity
import com.example.data.booking.WalletDao
import com.example.data.booking.WalletEntity
import com.example.data.booking.WalletTxnEntity
import com.example.data.docs.DocSequenceEntity
import com.example.data.docs.IssuedDocDao
import com.example.data.docs.IssuedDocumentEntity

@Database(
    entities = [
        ExtractedDocumentEntity::class, ImportedFileEntity::class,
        BookingEntity::class, BookingPaymentEntity::class, WalletEntity::class, WalletTxnEntity::class,
        DayCloseEntity::class, IssuedDocumentEntity::class, DocSequenceEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun importedFileDao(): ImportedFileDao
    abstract fun bookingDao(): BookingDao
    abstract fun walletDao(): WalletDao
    abstract fun issuedDocDao(): IssuedDocDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: add review status, original-image path and verification timestamps.
         * Rows that existed before this version were saved automatically without review,
         * so they start as PENDING and must be checked before they count in totals.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN status TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN imagePath TEXT")
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN verifiedAt INTEGER")
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN updatedAt INTEGER")
            }
        }

        /**
         * v2 -> v3: original uploaded file path + Google Sheets sync time on documents,
         * and a new table for manually imported eZee reports and CSV/Excel files.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN sourceFilePath TEXT")
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN sheetSyncedAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `imported_files` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `fileName` TEXT NOT NULL, `mimeType` TEXT, " +
                        "`storedPath` TEXT, `reportType` TEXT, `reportDate` TEXT, " +
                        "`extractedJson` TEXT, `rowCount` INTEGER, `targetSheet` TEXT, " +
                        "`status` TEXT NOT NULL, `errorMessage` TEXT, `sheetSyncedAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v3 -> v4: cancelling (voiding) counted documents instead of deleting them,
         * and a fingerprint of the original file to catch the same slip imported twice.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN voidReason TEXT")
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN voidedAt INTEGER")
                db.execSQL("ALTER TABLE extracted_documents ADD COLUMN contentHash TEXT")
            }
        }

        /**
         * v4 -> v5: direct bookings and their payments, wallets (กระเป๋า) and wallet movements,
         * day closing, and documents issued by the resort with their running numbers.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `guestName` TEXT NOT NULL, `phone` TEXT, " +
                        "`idNumber` TEXT, `nationality` TEXT, `address` TEXT, `comeFrom` TEXT, `goTo` TEXT, " +
                        "`guests` INTEGER NOT NULL, `roomNo` TEXT NOT NULL, `checkIn` TEXT NOT NULL, `checkOut` TEXT NOT NULL, " +
                        "`nightlyRate` REAL NOT NULL, `totalAmount` REAL NOT NULL, `status` TEXT NOT NULL, `note` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER, `settledAt` INTEGER)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `booking_payments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookingId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`method` TEXT NOT NULL, `amount` REAL NOT NULL, `date` TEXT NOT NULL, `note` TEXT, " +
                        "`slipDocumentId` INTEGER, `createdAt` INTEGER NOT NULL, `voidedAt` INTEGER, `voidReason` TEXT)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallets` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                        "`percent` REAL NOT NULL, `monthlyTarget` REAL, `sortOrder` INTEGER NOT NULL, `active` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallet_txns` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `walletId` INTEGER NOT NULL, `amount` REAL NOT NULL, " +
                        "`kind` TEXT NOT NULL, `date` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceId` INTEGER, " +
                        "`note` TEXT, `createdAt` INTEGER NOT NULL, `transferredAt` INTEGER, `voidedAt` INTEGER)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `day_closes` (" +
                        "`date` TEXT NOT NULL, `closedAt` INTEGER NOT NULL, `ezeeIncome` REAL NOT NULL, " +
                        "`cashExpected` REAL, `cashCounted` REAL, `note` TEXT, PRIMARY KEY(`date`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `issued_documents` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `number` TEXT NOT NULL, " +
                        "`issueDate` TEXT NOT NULL, `bookingId` INTEGER, `paymentId` INTEGER, `customerName` TEXT NOT NULL, " +
                        "`customerAddress` TEXT, `customerTaxId` TEXT, `itemsJson` TEXT NOT NULL, `subtotal` REAL NOT NULL, " +
                        "`vatAmount` REAL NOT NULL, `total` REAL NOT NULL, `note` TEXT, `pdfPath` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `copies` INTEGER NOT NULL, `voidedAt` INTEGER, `voidReason` TEXT)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `doc_sequences` (`key` TEXT NOT NULL, `lastNo` INTEGER NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        /** Every future schema change must add its Migration here. Never use destructive migration. */
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        /**
         * Closes the database so its file can be replaced (restore from a backup).
         * The next [getInstance] opens it again.
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "accounting_ocr.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
