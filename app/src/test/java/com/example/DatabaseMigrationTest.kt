package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.booking.BookingEntity
import com.example.data.booking.DayCloseEntity
import com.example.data.booking.WalletEntity
import com.example.data.booking.WalletTxnEntity
import com.example.data.docs.IssuedDocumentEntity
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.ImportedFileEntity
import com.example.data.local.countsInAccounting
import com.example.data.local.looksLikeDuplicateOf
import com.example.data.local.canVoid
import com.example.data.local.canDelete
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
    fun `migration 1 to 6 keeps existing rows and marks them PENDING`() = runBlocking<Unit> {
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
        // v4 columns
        assertNull(row.voidReason)
        assertNull(row.voidedAt)
        assertNull(row.contentHash)
        assertTrue("never counted -> may be deleted", row.canDelete())

        // v3 table exists and works
        val importId = roomDb.importedFileDao().insert(
            ImportedFileEntity(
                kind = "TABLE", fileName = "a.csv", mimeType = "text/csv", storedPath = null,
                reportType = null, reportDate = null, extractedJson = null, rowCount = 2,
                targetSheet = "Import", status = "SAVED", errorMessage = null, sheetSyncedAt = null
            )
        )
        assertEquals("a.csv", roomDb.importedFileDao().getById(importId)?.fileName)

        // v5 tables (bookings, wallets, day close, issued documents) exist and work
        val walletId = roomDb.walletDao().insertWallet(WalletEntity(name = "ทดสอบ", role = "SAVINGS", percent = 100.0))
        roomDb.walletDao().insertTxns(listOf(WalletTxnEntity(walletId = walletId, amount = 10.0, kind = "ALLOCATION", date = "2026-09-25", sourceType = "MANUAL")))
        assertEquals(1, roomDb.walletDao().getAllTxns().size)
        val bookingId = roomDb.bookingDao().insertBooking(
            BookingEntity(guestName = "ลูกค้าทดสอบ", roomNo = "1", checkIn = "2026-09-25", checkOut = "2026-09-26", nightlyRate = 800.0, totalAmount = 800.0)
        )
        assertEquals("ลูกค้าทดสอบ", roomDb.bookingDao().getBooking(bookingId)?.guestName)
        roomDb.walletDao().insertDayClose(DayCloseEntity(date = "2026-09-25", closedAt = 1L))
        assertNotNull(roomDb.walletDao().getDayClose("2026-09-25"))
        val docId = roomDb.issuedDocDao().insert(
            IssuedDocumentEntity(type = "RECEIPT", number = "RC2609-0001", issueDate = "2026-09-25", customerName = "ก", itemsJson = "{}", subtotal = 1.0, vatAmount = 0.0, total = 1.0)
        )
        assertEquals("RC2609-0001", roomDb.issuedDocDao().get(docId)?.number)

        // v6 table (history of wallet % changes) exists and works
        roomDb.walletDao().insertPercentChange(
            com.example.data.booking.WalletPercentChangeEntity(
                reason = "ค่าไฟสูง", summary = "ค่าน้ำ-ค่าไฟ 10→15%", beforePercents = "3=10.0", afterPercents = "3=15.0"
            )
        )

        roomDb.close()
        context.deleteDatabase(name)
    }

    private val doc = ExtractedDocumentEntity(
        id = 1, documentType = "RECEIPT", transactionType = "INCOME", documentNo = "TX-1", date = "2026-09-25",
        sellerName = "ร้าน A", sellerTaxId = null, customerName = null, customerTaxId = null,
        subtotal = null, vatAmount = null, totalAmount = 1500.0, depositAmount = null,
        paymentMethod = null, lineItemsJson = "[]", rawJson = "{}"
    )

    @Test
    fun `counted documents cannot be deleted, only voided`() {
        assertTrue(doc.canDelete())
        assertFalse(doc.canVoid())
        val verified = doc.copy(status = DocumentStatus.VERIFIED.code, verifiedAt = 1L)
        assertFalse(verified.canDelete())
        assertTrue(verified.canVoid())
        // Sent back to review after being counted: still protected.
        val reopened = verified.copy(status = DocumentStatus.PENDING.code)
        assertFalse(reopened.canDelete())
        assertTrue(reopened.canVoid())
        val voided = verified.copy(status = DocumentStatus.VOIDED.code, voidReason = "สลิปซ้ำ", voidedAt = 2L)
        assertFalse(voided.canDelete())
        assertFalse(voided.canVoid())
        assertFalse("voided never counts", voided.countsInAccounting())
    }

    @Test
    fun `duplicates are found by file, by number and by date plus seller`() {
        val other = doc.copy(id = 2, status = DocumentStatus.VERIFIED.code)
        assertTrue(doc.looksLikeDuplicateOf(other)) // same no. + total
        assertTrue(doc.copy(documentNo = null).looksLikeDuplicateOf(other.copy(documentNo = null))) // same date + total + seller
        assertFalse(doc.looksLikeDuplicateOf(other.copy(totalAmount = 1499.0)))
        // Same file even when the AI read the amount differently.
        assertTrue(doc.copy(contentHash = "abc").looksLikeDuplicateOf(other.copy(totalAmount = 99.0, contentHash = "abc")))
        // Voided / rejected documents are not duplicates any more.
        assertFalse(doc.looksLikeDuplicateOf(other.copy(status = DocumentStatus.VOIDED.code)))
        assertFalse(doc.looksLikeDuplicateOf(other.copy(status = DocumentStatus.REJECTED.code)))
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
