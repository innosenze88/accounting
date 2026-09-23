package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** What kind of file was imported manually (from the upload button or "Share" in Gmail). */
enum class ImportKind(val code: String, val titleTh: String) {
    EZEE_REPORT("EZEE_REPORT", "รายงาน eZee (PDF)"),
    TABLE("TABLE", "ตาราง CSV/Excel");

    companion object {
        fun fromCode(code: String?): ImportKind = entries.find { it.code == code } ?: TABLE
    }
}

/** Save / Google Sheets state of an imported file. */
enum class ImportStatus(val code: String, val titleTh: String) {
    SAVED("SAVED", "บันทึกในเครื่องแล้ว"),
    SYNCED("SYNCED", "ส่งไป Google Sheets แล้ว"),
    SYNC_FAILED("SYNC_FAILED", "ส่งไป Sheets ไม่สำเร็จ");

    companion object {
        fun fromCode(code: String?): ImportStatus = entries.find { it.code == code } ?: SAVED
    }
}

/**
 * A file imported by hand: an eZee PDF report (read by AI) or a CSV/Excel table (read locally).
 * The original file is always kept in app storage ([storedPath]).
 */
@Entity(tableName = "imported_files")
data class ImportedFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val kind: String,
    val fileName: String,
    val mimeType: String?,
    val storedPath: String?,
    val reportType: String?,
    val reportDate: String?,
    /** eZee report: the AI JSON result. Table: null (re-read from the stored file). */
    val extractedJson: String?,
    val rowCount: Int?,
    val targetSheet: String?,
    val status: String,
    val errorMessage: String?,
    val sheetSyncedAt: Long?,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ImportedFileDao {
    @Query("SELECT * FROM imported_files ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImportedFileEntity>>

    @Query("SELECT * FROM imported_files WHERE id = :id")
    suspend fun getById(id: Long): ImportedFileEntity?

    @Insert
    suspend fun insert(entity: ImportedFileEntity): Long

    @Update
    suspend fun update(entity: ImportedFileEntity)

    @Query("DELETE FROM imported_files WHERE id = :id")
    suspend fun deleteById(id: Long)
}
