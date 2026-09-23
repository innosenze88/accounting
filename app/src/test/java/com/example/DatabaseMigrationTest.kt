package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.ImportedFileEntity
import com.example.data.local.countsInAccounting
import com.example.data.model.DocumentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Exact v1 table as Room created it before this change. */
    private val createV1 = """
        CREATE TABLE IF NOT EXISTS `extracted_documents` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `documentType` TEXT NOT NULL, `transactionType` TEXT NOT NULL,
          `documentNo` TEXT, `date` TEXT, `sellerName` TEXT, `sellerTaxId` TEXT,
          `customerName` TEXT, `customerTaxId` TEXT, `subtotal` REAL, `vatAmount` REAL,
          `totalAmount` REAL, `depositAmount` REAL, `paymentMethod` TEXT,
          `lineItemsJson` TEXT NOT NULL, `rawJson` TEXT NOT NULL, `sampleId` TEXT,
          `createdAt` INTEGER NOT NULL)
    """.trimIndent()

    @Test
    fun `migration 1 to 3 keeps existing rows and marks them PENDING`() = runBlocking<Unit> {
        val name = "migration_test.db"
        context.deleteDatabase(name)

        // 1) Build a v1 database containing one real document.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            db.execSQL(createV1)
            db.execSQL(
                """INSERT INTO extracted_documents
                   (documentType, transactionType, documentNo, totalAmount, lineItemsJson, rawJson, createdAt)
                   VALUES ('RECEIPT', 'INCOME', 'INV-001', 1070.0, '[]', '{}', 1700000000000)"""
            )
            db.version = 1
        }

        // 2) Open it with the current Room schema: this runs MIGRATION_1_2 and validates the schema.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val row = roomDb.documentDao().getDocumentById(1)
        assertNotNull("existing row must survive the migration", row)
        assertEquals("INV-001", row!!.documentNo)
        assertEquals(1070.0, row.totalAmount!!, 0.0)
        assertEquals(DocumentStatus.PENDING.code, row.status)
        assertNull(row.imagePath)
        assertFalse("un-reviewed rows must not count in totals", row.countsInAccounting())
        assertNull(row.sourceFilePath)
        assertNull(row.sheetSyncedAt)

        // v3 table exists and works
        val importId = roomDb.importedFileDao().insert(
            ImportedFileEntity(
                kind = "TABLE", fileName = "a.csv", mimeType = "text/csv", storedPath = null,
                reportType = null, reportDate = null, extractedJson = null, rowCount = 2,
                targetSheet = "Import", status = "SAVED", errorMessage = null, sheetSyncedAt = null
            )
        )
        assertEquals("a.csv", roomDb.importedFileDao().getById(importId)?.fileName)

        roomDb.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `only verified real documents count in accounting`() {
        val base = ExtractedDocumentEntity(
            documentType = "RECEIPT", transactionType = "INCOME", documentNo = null, date = null,
            sellerName = null, sellerTaxId = null, customerName = null, customerTaxId = null,
            subtotal = null, vatAmount = null, totalAmount = 100.0, depositAmount = null,
            paymentMethod = null, lineItemsJson = "[]", rawJson = "{}"
        )
        assertFalse(base.countsInAccounting()) // PENDING by default
        assertTrue(base.copy(status = DocumentStatus.VERIFIED.code).countsInAccounting())
        assertFalse(base.copy(status = DocumentStatus.REJECTED.code).countsInAccounting())
        assertFalse(
            "samples never count, even if verified",
            base.copy(status = DocumentStatus.VERIFIED.code, sampleId = "sample_1").countsInAccounting()
        )
    }
}
