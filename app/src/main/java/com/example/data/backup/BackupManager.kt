package com.example.data.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.settings.BusinessSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** What is inside a backup file. */
data class BackupInfo(
    val createdAt: Long,
    val appVersion: String,
    val dbVersion: Int,
    val documents: Int,
    val bookings: Int,
    val files: Int,
    val sizeBytes: Long
)

/**
 * One-file backup of everything the app keeps: the database (documents, bookings, wallets, issued documents),
 * original images/PDFs, the user's report readers and the resort settings.
 * API keys and the Google Sheets token are NOT included (they must be entered again on a new phone).
 */
class BackupManager(private val context: Context) {

    companion object {
        const val FORMAT = "resort-accounting-backup"
        const val DB_NAME = "accounting_ocr.db"
        /** Database version this app writes (must match AppDatabase). */
        const val DB_VERSION = 6
        private const val MANIFEST = "manifest.json"
        private const val DB_ENTRY = "database/$DB_NAME"
        private const val FILES_PREFIX = "files/"
        private const val PREFS_ENTRY = "prefs/business_settings.json"
        private const val KEEP_SAFETY = 3

        fun fileName(time: Long = System.currentTimeMillis()): String =
            "resort-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(time)) + ".zip"
    }

    private val app = context.applicationContext
    private val backupDir get() = File(app.cacheDir, "backups").apply { mkdirs() }
    /** Safety copies made right before a restore. Outside filesDir, so a restore never touches them. */
    private val safetyDir get() = File(app.noBackupFilesDir, "safety_backups").apply { mkdirs() }

    /** Creates a backup zip in the cache folder and returns it (share it, save it or upload it). */
    suspend fun createBackup(): File = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(app)
        // Write everything from the WAL journal into the main file, so copying one file is enough.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val documents = db.documentDao().getAllOnce().size
        val bookings = db.bookingDao().getAllBookings().size

        // Keep only the newest backup in the cache (never the file picked for a restore).
        backupDir.listFiles()?.filter { it.name.startsWith("resort-backup-") }?.forEach { it.delete() }
        val out = File(backupDir, fileName())
        var fileCount = 0
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            app.getDatabasePath(DB_NAME).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            val root = app.filesDir
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(FILES_PREFIX + f.relativeTo(root).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                fileCount++
            }

            zip.putNextEntry(ZipEntry(PREFS_ENTRY))
            zip.write(BusinessSettingsRepository(app).exportJson().toByteArray())
            zip.closeEntry()

            val manifest = JSONObject().apply {
                put("format", FORMAT)
                put("created_at", System.currentTimeMillis())
                put("app_version", BuildConfig.VERSION_NAME)
                put("db_version", DB_VERSION)
                put("files_dir", root.absolutePath)
                put("documents", documents)
                put("bookings", bookings)
                put("files", fileCount)
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()
        }
        out
    }

    /** Copies a picked file into the cache and reads its manifest. Throws with a Thai message if it is not a backup. */
    suspend fun inspect(uri: Uri): Pair<File, BackupInfo> = withContext(Dispatchers.IO) {
        val copy = File(backupDir, "restore-candidate.zip")
        app.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
            ?: error("เปิดไฟล์ไม่ได้")
        copy to readInfo(copy)
    }

    private fun readInfo(zipFile: File): BackupInfo {
        val manifest = try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(MANIFEST) ?: error("ไม่ใช่ไฟล์สำรองของแอปนี้")
                check(zip.getEntry(DB_ENTRY) != null) { "ไฟล์สำรองไม่สมบูรณ์ (ไม่มีฐานข้อมูล)" }
                JSONObject(zip.getInputStream(entry).bufferedReader().readText())
            }
        } catch (e: java.util.zip.ZipException) {
            error("ไฟล์นี้ไม่ใช่ไฟล์สำรอง หรือไฟล์เสีย")
        }
        check(manifest.optString("format") == FORMAT) { "ไม่ใช่ไฟล์สำรองของแอปนี้" }
        val dbVersion = manifest.optInt("db_version", 0)
        check(dbVersion in 1..DB_VERSION) {
            "ไฟล์สำรองมาจากแอปเวอร์ชันใหม่กว่า (${manifest.optString("app_version")}) — อัปเดตแอปก่อนแล้วค่อยกู้คืน"
        }
        return BackupInfo(
            createdAt = manifest.optLong("created_at"),
            appVersion = manifest.optString("app_version"),
            dbVersion = dbVersion,
            documents = manifest.optInt("documents"),
            bookings = manifest.optInt("bookings"),
            files = manifest.optInt("files"),
            sizeBytes = zipFile.length()
        )
    }

    /**
     * Replaces ALL app data with the backup. A safety backup of the current data is made first
     * (kept in the app, see [safetyBackups]). The app must restart afterwards ([restartApp]).
     */
    suspend fun restore(zipFile: File) = withContext(Dispatchers.IO) {
        readInfo(zipFile) // validate again
        // 1) Safety copy of what is on the phone now.
        val safety = createBackup()
        safety.copyTo(File(safetyDir, "before-restore-" + safety.name), overwrite = true)
        safetyDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_SAFETY)?.forEach { it.delete() }

        ZipFile(zipFile).use { zip ->
            val manifest = JSONObject(zip.getInputStream(zip.getEntry(MANIFEST)).bufferedReader().readText())

            // 2) Database: close, replace the file, remove the old journal.
            AppDatabase.closeInstance()
            val dbFile = app.getDatabasePath(DB_NAME)
            listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"), File(dbFile.path + "-journal"))
                .forEach { it.delete() }
            dbFile.parentFile?.mkdirs()
            zip.getInputStream(zip.getEntry(DB_ENTRY)).use { input -> dbFile.outputStream().use { input.copyTo(it) } }

            // 3) Files: replace the whole filesDir content.
            val root = app.filesDir
            root.listFiles()?.forEach { it.deleteRecursively() }
            val canonicalRoot = root.canonicalPath + File.separator
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(FILES_PREFIX) }.forEach { e ->
                val target = File(root, e.name.removePrefix(FILES_PREFIX))
                check(target.canonicalPath.startsWith(canonicalRoot)) { "ไฟล์สำรองมีชื่อไฟล์ไม่ปลอดภัย" }
                target.parentFile?.mkdirs()
                zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
            }

            // 4) Resort settings.
            zip.getEntry(PREFS_ENTRY)?.let { e ->
                BusinessSettingsRepository.importJson(app, zip.getInputStream(e).bufferedReader().readText())
            }

            // 5) Stored file paths are absolute: fix them if this phone keeps files somewhere else.
            val oldRoot = manifest.optString("files_dir")
            if (oldRoot.isNotBlank() && oldRoot != root.absolutePath) fixPaths(dbFile, oldRoot, root.absolutePath)
        }
        BackupPrefs(app).markBackup()
    }

    private fun fixPaths(dbFile: File, oldRoot: String, newRoot: String) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val updates = listOf(
                "extracted_documents" to "imagePath", "extracted_documents" to "sourceFilePath",
                "imported_files" to "storedPath", "issued_documents" to "pdfPath"
            )
            for ((table, column) in updates) {
                // Older backups may not have every table yet (they are created when the app upgrades).
                runCatching {
                    db.execSQL(
                        "UPDATE `$table` SET `$column` = replace(`$column`, ?, ?) WHERE `$column` LIKE ?",
                        arrayOf(oldRoot, newRoot, "$oldRoot%")
                    )
                }
            }
        } finally {
            db.close()
        }
    }

    /** Safety copies made before each restore (newest first). */
    fun safetyBackups(): List<File> = safetyDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Restarts the app so every screen reloads the restored data. */
    fun restartApp() {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        val restart = Intent.makeRestartActivityTask(launch.component)
        app.startActivity(restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Process.killProcess(Process.myPid())
    }
}

/** When the last backups were made. */
class BackupPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("backup_state", Context.MODE_PRIVATE)

    val lastBackupAt: Long get() = prefs.getLong("last_backup_at", 0L)
    val lastDriveBackupAt: Long get() = prefs.getLong("last_drive_backup_at", 0L)
    var autoDriveBackup: Boolean
        get() = prefs.getBoolean("auto_drive_backup", true)
        set(v) { prefs.edit().putBoolean("auto_drive_backup", v).apply() }
    val lastDriveError: String? get() = prefs.getString("last_drive_error", null)

    fun markBackup(time: Long = System.currentTimeMillis()) = prefs.edit().putLong("last_backup_at", time).apply()

    fun markDriveBackup(error: String?, time: Long = System.currentTimeMillis()) {
        val e = prefs.edit().putString("last_drive_error", error)
        if (error == null) e.putLong("last_drive_backup_at", time).putLong("last_backup_at", time)
        e.apply()
    }

    /** Days since the last backup of any kind, or null when there was never one. */
    fun daysSinceBackup(now: Long = System.currentTimeMillis()): Int? =
        lastBackupAt.takeIf { it > 0 }?.let { ((now - it) / 86_400_000L).toInt() }
}
