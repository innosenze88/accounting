package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ExtractedDocumentEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

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

        /** Every future schema change must add its Migration here. Never use destructive migration. */
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

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
