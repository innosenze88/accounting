package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupInfo
import com.example.data.backup.BackupManager
import com.example.data.backup.BackupPrefs
import com.example.data.settings.AiSettingsRepository
import com.example.data.sheets.SheetsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Backup / restore screen state. Also runs the daily automatic backup to Google Drive. */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = BackupManager(application)
    private val prefs = BackupPrefs(application)
    private val sheets = SheetsClient()
    private val aiSettings = AiSettingsRepository(application)

    data class State(
        val busy: Boolean = false,
        val message: String? = null,
        val lastBackupAt: Long = 0L,
        val lastDriveBackupAt: Long = 0L,
        val lastDriveError: String? = null,
        val autoDrive: Boolean = true,
        val sheetsReady: Boolean = false,
        /** A picked backup waiting for "confirm restore". */
        val pendingRestore: Pair<File, BackupInfo>? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
        autoDriveBackupIfDue()
    }

    private fun refresh(message: String? = _state.value.message) {
        _state.value = _state.value.copy(
            message = message,
            lastBackupAt = prefs.lastBackupAt,
            lastDriveBackupAt = prefs.lastDriveBackupAt,
            lastDriveError = prefs.lastDriveError,
            autoDrive = prefs.autoDriveBackup,
            sheetsReady = aiSettings.settings.value.sheetsEnabled
        )
    }

    fun clearMessage() = refresh(null)

    private fun run(block: suspend () -> String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val msg = try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Backup action failed", e)
                "✕ ${e.localizedMessage ?: "ไม่สำเร็จ"}"
            }
            _state.value = _state.value.copy(busy = false)
            refresh(msg)
        }
    }

    /** "บันทึกเป็นไฟล์": writes the backup to a place the person picked (phone, Google Drive, ...). */
    fun saveTo(uri: Uri) = run {
        val file = manager.createBackup()
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("เขียนไฟล์ไม่ได้")
        }
        prefs.markBackup()
        "✓ บันทึกไฟล์สำรองแล้ว (${file.length() / 1024} KB)"
    }

    /** "ส่งไฟล์สำรอง": opens the share sheet (LINE Keep, e-mail, Drive...). */
    fun share() = run {
        val app = getApplication<Application>()
        val file = manager.createBackup()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, "ส่งไฟล์สำรองไปที่...").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        prefs.markBackup()
        "✓ สร้างไฟล์สำรองแล้ว — เลือกที่ส่ง (แนะนำ LINE Keep หรือ Google Drive)"
    }

    fun uploadToDriveNow() = run { uploadToDrive() }

    private suspend fun uploadToDrive(): String {
        val s = aiSettings.settings.value
        check(s.sheetsEnabled) { "ต้องตั้งค่า Google Sheets (Apps Script) ก่อน — ดูหัวข้อ Google Sheets ด้านล่าง" }
        val file = manager.createBackup()
        val result = sheets.saveBackup(s.sheetsWebAppUrl, s.sheetsToken, file)
        val error = result.exceptionOrNull()?.let { e ->
            val m = e.localizedMessage ?: "ไม่สำเร็จ"
            if ("unknown action" in m) "ต้องอัปเดตโค้ด Apps Script และกด Run ฟังก์ชัน authorize หนึ่งครั้ง" else m
        }
        prefs.markDriveBackup(error)
        return error?.let { "✕ สำรองขึ้น Google Drive ไม่สำเร็จ: $it" }
            ?: "✓ สำรองขึ้น Google Drive แล้ว (โฟลเดอร์ Resort Accounting Backups เก็บ 7 ไฟล์ล่าสุด)"
    }

    fun setAutoDrive(on: Boolean) {
        prefs.autoDriveBackup = on
        refresh()
    }

    /** Once a day, in the background, when Google Sheets is set up. Silent unless it fails. */
    private fun autoDriveBackupIfDue() {
        val s = aiSettings.settings.value
        if (!prefs.autoDriveBackup || !s.sheetsEnabled) return
        if (System.currentTimeMillis() - prefs.lastDriveBackupAt < AUTO_EVERY_MS) return
        viewModelScope.launch {
            try {
                uploadToDrive()
            } catch (e: Exception) {
                prefs.markDriveBackup(e.localizedMessage ?: "ไม่สำเร็จ")
            }
            refresh()
        }
    }

    /** Step 1 of a restore: read the picked file and show what is inside. */
    fun pickRestore(uri: Uri) = run {
        val picked = manager.inspect(uri)
        _state.value = _state.value.copy(pendingRestore = picked)
        ""
    }

    fun cancelRestore() {
        _state.value = _state.value.copy(pendingRestore = null)
    }

    /** Step 2: replace all data with the backup, then restart the app. */
    fun confirmRestore() {
        val (file, _) = _state.value.pendingRestore ?: return
        _state.value = _state.value.copy(pendingRestore = null)
        run {
            manager.restore(file)
            manager.restartApp()
            "✓ กู้คืนแล้ว — กำลังเปิดแอปใหม่"
        }
    }

    companion object {
        private const val TAG = "BackupViewModel"
        private const val AUTO_EVERY_MS = 20L * 60 * 60 * 1000 // about once a day
    }
}
