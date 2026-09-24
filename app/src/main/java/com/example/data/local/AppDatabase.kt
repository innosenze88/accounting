package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ExtractedDocumentEntity::class, ImportedFileEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun importedFileDao(): ImportedFileDao

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

        /** Every future schema change must add its Migration here. Never use destructive migration. */
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
