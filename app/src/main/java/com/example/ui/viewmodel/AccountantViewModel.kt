package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentImageStore
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.DocumentStatus
import com.example.data.importer.FileImporter
import com.example.data.importer.FileKind
import com.example.data.importer.ParsedTable
import com.example.data.importer.PickedFile
import com.example.data.local.ImportKind
import com.example.data.local.ImportStatus
import com.example.data.local.ImportedFileDao
import com.example.data.local.ImportedFileEntity
import com.example.data.network.ClaudeOcrService
import com.example.data.network.DocumentInput
import com.example.data.sheets.SheetsClient
import android.net.Uri
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import com.example.data.network.GeminiOcrService
import com.example.data.network.OcrService
import com.example.data.settings.AiProvider
import com.example.data.settings.AiSettings
import com.example.data.settings.AiSettingsRepository
import com.example.data.repository.DocumentRepository
import com.example.util.SampleDocument
import com.example.util.SampleDocumentGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AccountantViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DocumentRepository
    private val importedFileDao: ImportedFileDao
    private val sheetsClient = SheetsClient()
    private val settingsRepository = AiSettingsRepository(application)
    private val geminiService = GeminiOcrService()
    private val claudeService = ClaudeOcrService()

    init {
        val db = AppDatabase.getInstance(application)
        repository = DocumentRepository(db.documentDao(), DocumentImageStore(application))
        importedFileDao = db.importedFileDao()
    }

    // ---------------- AI settings ----------------

    val aiSettings: StateFlow<AiSettings> = settingsRepository.settings

    private val _connectionTestMessage = MutableStateFlow<String?>(null)
    val connectionTestMessage: StateFlow<String?> = _connectionTestMessage.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    fun saveAiSettings(settings: AiSettings) {
        settingsRepository.save(settings)
        _connectionTestMessage.value = null
    }

    /** Saves [settings], then sends a tiny request to check the key and model of the chosen provider. */
    fun testAiConnection(settings: AiSettings) {
        saveAiSettings(settings)
        val (service, key, model) = resolveProvider(settingsRepository.settings.value)
            ?: run {
                _connectionTestMessage.value = "✕ ยังไม่ได้ใส่ API key ของ ${settings.provider.title}"
                return
            }
        viewModelScope.launch {
            _isTestingConnection.value = true
            _connectionTestMessage.value = null
            service.testConnection(key, model).fold(
                onSuccess = { _connectionTestMessage.value = "✓ เชื่อมต่อ ${settings.provider.title} ($model) สำเร็จ" },
                onFailure = { _connectionTestMessage.value = "✕ ${it.localizedMessage ?: "เชื่อมต่อไม่สำเร็จ"}" }
            )
            _isTestingConnection.value = false
        }
    }

    /**
     * Returns the service, API key and model to use, or null when no key is set.
     * Gemini falls back to the build-time key (AI Studio Secrets) when the Settings key is empty.
     */
    private fun resolveProvider(settings: AiSettings): Triple<OcrService, String, String>? {
        return when (settings.provider) {
            AiProvider.GEMINI -> {
                val buildKey = BuildConfig.GEMINI_API_KEY
                    ?.takeIf { it.isNotBlank() && it != "MY_GEMINI_API_KEY" }
                val key = settings.geminiApiKey.ifBlank { buildKey ?: "" }
                if (key.isBlank()) null else Triple(geminiService, key, settings.activeModel)
            }
            AiProvider.CLAUDE -> {
                val key = settings.claudeApiKey
                if (key.isBlank()) null else Triple(claudeService, key, settings.activeModel)
            }
        }
    }

    val historyList: StateFlow<List<ExtractedDocumentEntity>> = repository.allDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedBitmap: StateFlow<Bitmap?> = _selectedBitmap.asStateFlow()

    private val _selectedSample = MutableStateFlow<SampleDocument?>(null)
    val selectedSample: StateFlow<SampleDocument?> = _selectedSample.asStateFlow()

    /** Original PDF when the scanner is working on an uploaded PDF slip/receipt (null = image). */
    private val _selectedPdf = MutableStateFlow<PickedFile?>(null)
    val selectedPdfName: StateFlow<String?> = _selectedPdf
        .map { it?.fileName }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _extractedDocument = MutableStateFlow<AccountingDocumentJson?>(null)
    val extractedDocument: StateFlow<AccountingDocumentJson?> = _extractedDocument.asStateFlow()

    private val _rawJsonOutput = MutableStateFlow<String?>(null)
    val rawJsonOutput: StateFlow<String?> = _rawJsonOutput.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * True when the result on screen comes from a sample document.
     * Sample results are shown for testing only and are NEVER saved to the accounting database.
     */
    private val _isDemoResult = MutableStateFlow(false)
    val isDemoResult: StateFlow<Boolean> = _isDemoResult.asStateFlow()

    /** Id of the saved record currently shown on the scanner screen (null = not saved). */
    private val _currentRecordId = MutableStateFlow<Long?>(null)

    /** Live copy of the current record from Room, so status changes show up immediately. */
    val currentRecord: StateFlow<ExtractedDocumentEntity?> = _currentRecordId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeDocument(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Pre-select the first sample document so user immediately sees rich content
        selectSample(SampleDocumentGenerator.SAMPLES[0])
    }

    private fun resetResult() {
        _extractedDocument.value = null
        _rawJsonOutput.value = null
        _errorMessage.value = null
        _isDemoResult.value = false
        _currentRecordId.value = null
    }

    fun selectSample(sample: SampleDocument) {
        _selectedPdf.value = null
        _selectedSample.value = sample
        _selectedBitmap.value = SampleDocumentGenerator.renderDocumentBitmap(sample.id)
        resetResult()
    }

    fun setImageBitmap(bitmap: Bitmap) {
        _selectedPdf.value = null
        _selectedSample.value = null
        _selectedBitmap.value = bitmap
        resetResult()
    }

    fun clearImage() {
        _selectedPdf.value = null
        _selectedSample.value = null
        _selectedBitmap.value = null
        resetResult()
    }

    fun showError(message: String) {
        _errorMessage.value = message
    }

    fun performExtraction() {
        val bitmap = _selectedBitmap.value
        if (bitmap == null) {
            _errorMessage.value = "กรุณาเลือกรูปภาพหรือเอกสารตัวอย่างก่อนสกัดข้อมูล"
            return
        }
        if (_isExtracting.value) return

        viewModelScope.launch {
            _isExtracting.value = true
            _errorMessage.value = null
            _isDemoResult.value = false
            _currentRecordId.value = null

            try {
                val currentSample = _selectedSample.value
                val provider = resolveProvider(settingsRepository.settings.value)

                when {
                    // Sample document: show the result for testing, but never save it.
                    currentSample != null -> runSampleExtraction(currentSample, bitmap, provider)

                    provider == null -> _errorMessage.value =
                        "ยังไม่ได้ใส่ API key ของ ${settingsRepository.settings.value.provider.title} — ไปที่แท็บ \"ตั้งค่า\" เพื่อใส่ key (ระหว่างนี้ทดสอบกับเอกสารตัวอย่างได้ ผลตัวอย่างจะไม่ถูกบันทึกลงบัญชี)"

                    else -> runRealExtraction(bitmap, _selectedPdf.value, requireNotNull(provider))
                }
            } finally {
                _isExtracting.value = false
            }
        }
    }

    private suspend fun runSampleExtraction(
        sample: SampleDocument,
        bitmap: Bitmap,
        provider: Triple<OcrService, String, String>?
    ) {
        _isDemoResult.value = true
        if (provider != null) {
            val (service, key, model) = provider
            val result = service.extract(DocumentInput.Image(bitmap), key, model)
            result.fold(
                onSuccess = { (doc, raw) ->
                    _extractedDocument.value = doc
                    _rawJsonOutput.value = raw
                },
                onFailure = { error ->
                    _errorMessage.value = "สกัดข้อมูลล้มเหลว: ${error.localizedMessage ?: "Unknown"}"
                }
            )
        } else {
            delay(1200) // Realistic UX progress
            _extractedDocument.value = sample.documentData
            _rawJsonOutput.value = sample.rawJson
        }
    }

    private suspend fun runRealExtraction(
        bitmap: Bitmap,
        pdf: PickedFile?,
        provider: Triple<OcrService, String, String>
    ) {
        val (service, key, model) = provider
        // Uploaded PDFs go to the AI as the original file (all pages); photos go as images.
        val input = if (pdf != null) DocumentInput.Pdf(pdf.bytes) else DocumentInput.Image(bitmap)
        val result = service.extract(input, key, model)
        result.fold(
            onSuccess = { (doc, raw) ->
                _extractedDocument.value = doc
                _rawJsonOutput.value = raw
                try {
                    // Keep the original image as evidence, then save the AI result as PENDING.
                    val imagePath = repository.saveImage(bitmap)
                    val sourcePath = pdf?.let { repository.saveFile(it.bytes, it.extension) }
                    val id = repository.savePendingDocument(doc, raw, imagePath, sourcePath)
                    _currentRecordId.value = id
                } catch (e: Exception) {
                    Log.e(TAG, "Saving document failed", e)
                    _errorMessage.value = "สกัดข้อมูลสำเร็จ แต่บันทึกลงเครื่องไม่สำเร็จ: ${e.localizedMessage ?: "Unknown"}"
                }
            },
            onFailure = { error ->
                // No sample fallback here: a failed scan must never produce made-up accounting data.
                _errorMessage.value = "สกัดข้อมูลล้มเหลว: ${error.localizedMessage ?: "กรุณาตรวจสอบการเชื่อมต่ออินเทอร์เน็ตหรือ API Key"}"
            }
        )
    }

    /** Person has checked (and possibly corrected) the fields: store them and mark VERIFIED. */
    fun verifyCurrent(reviewed: AccountingDocumentJson) {
        val id = _currentRecordId.value ?: return
        viewModelScope.launch {
            try {
                repository.verifyDocument(id, reviewed)
                _extractedDocument.value = reviewed
                _errorMessage.value = null
                val settings = settingsRepository.settings.value
                if (settings.sheetsEnabled && settings.sheetsAutoSync) {
                    syncDocumentToSheets(id)?.let { error ->
                        _errorMessage.value = "ยืนยันแล้ว แต่ส่งไป Google Sheets ไม่สำเร็จ: $error (ส่งซ้ำได้ที่หน้าตั้งค่า)"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Verify failed", e)
                _errorMessage.value = "ยืนยันเอกสารไม่สำเร็จ: ${e.localizedMessage ?: "Unknown"}"
            }
        }
    }

    fun rejectCurrent() = setCurrentStatus(DocumentStatus.REJECTED)

    /** Send a verified/rejected document back to PENDING so it can be corrected. */
    fun reopenCurrent() = setCurrentStatus(DocumentStatus.PENDING)

    private fun setCurrentStatus(status: DocumentStatus) {
        val id = _currentRecordId.value ?: return
        viewModelScope.launch {
            try {
                repository.setStatus(id, status)
            } catch (e: Exception) {
                Log.e(TAG, "Status change failed", e)
                _errorMessage.value = "เปลี่ยนสถานะไม่สำเร็จ: ${e.localizedMessage ?: "Unknown"}"
            }
        }
    }

    fun loadFromHistory(entity: ExtractedDocumentEntity) {
        _extractedDocument.value = entity.toAccountingDocument()
        _rawJsonOutput.value = entity.rawJson
        _errorMessage.value = null

        val sample = entity.sampleId?.let { sid -> SampleDocumentGenerator.SAMPLES.find { it.id == sid } }
        _isDemoResult.value = entity.sampleId != null
        _currentRecordId.value = entity.id
        _selectedSample.value = sample

        if (sample != null) {
            _selectedBitmap.value = SampleDocumentGenerator.renderDocumentBitmap(sample.id)
        } else {
            _selectedBitmap.value = null
            viewModelScope.launch {
                val image = repository.loadImage(entity.imagePath)
                // Only apply if the user has not switched to another record meanwhile.
                if (_currentRecordId.value == entity.id) {
                    _selectedBitmap.value = image
                }
            }
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch {
            repository.deleteDocument(id)
            if (_currentRecordId.value == id) clearImage()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.deleteAllDocuments()
            clearImage()
        }
    }

    // ================================================================ Google Sheets

    private val _sheetsMessage = MutableStateFlow<String?>(null)
    val sheetsMessage: StateFlow<String?> = _sheetsMessage.asStateFlow()

    private val _isSheetsBusy = MutableStateFlow(false)
    val isSheetsBusy: StateFlow<Boolean> = _isSheetsBusy.asStateFlow()

    /** Verified documents not yet in Google Sheets (or edited after the last send). */
    val unsyncedCount: StateFlow<Int> = repository.allDocuments
        .map { list ->
            list.count { it.sampleId == null && it.status == DocumentStatus.VERIFIED.code &&
                (it.sheetSyncedAt == null || it.sheetSyncedAt < (it.verifiedAt ?: 0L)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Returns null on success, otherwise the error text. */
    private suspend fun syncDocumentToSheets(id: Long): String? {
        val settings = settingsRepository.settings.value
        val doc = repository.getDocumentById(id) ?: return "ไม่พบเอกสาร"
        return sheetsClient.upsertDocument(settings.sheetsWebAppUrl, settings.sheetsToken, doc).fold(
            onSuccess = { repository.markSheetSynced(id); null },
            onFailure = { it.localizedMessage ?: "Unknown" }
        )
    }

    /** Saves settings, then checks that the Apps Script answers with the same token. */
    fun testSheets(settings: AiSettings) {
        saveAiSettings(settings)
        viewModelScope.launch {
            _isSheetsBusy.value = true
            _sheetsMessage.value = null
            val s = settingsRepository.settings.value
            _sheetsMessage.value = sheetsClient.ping(s.sheetsWebAppUrl, s.sheetsToken).fold(
                onSuccess = { name -> "✓ เชื่อมต่อ Google Sheets สำเร็จ" + (if (name.isNotBlank()) " ($name)" else "") },
                onFailure = { "✕ ${it.localizedMessage}" }
            )
            _isSheetsBusy.value = false
        }
    }

    /** Sends every verified document that is not in Google Sheets yet. */
    fun syncAllToSheets() {
        viewModelScope.launch {
            _isSheetsBusy.value = true
            _sheetsMessage.value = null
            val pending = repository.getVerifiedNotSynced()
            var ok = 0
            var lastError: String? = null
            for (doc in pending) {
                val error = syncDocumentToSheets(doc.id)
                if (error == null) ok++ else lastError = error
            }
            _sheetsMessage.value = when {
                pending.isEmpty() -> "✓ ไม่มีเอกสารค้างส่ง"
                lastError == null -> "✓ ส่งแล้ว $ok เอกสาร"
                else -> "✕ ส่งได้ $ok จาก ${pending.size} เอกสาร — $lastError"
            }
            _isSheetsBusy.value = false
        }
    }

    // ================================================================ Manual file import

    /** A file picked/shared by the user, waiting for the user to choose what to do with it. */
    class PendingImport(
        val file: PickedFile,
        val preview: Bitmap?,
        val pageCount: Int,
        val table: ParsedTable?
    )

    /** AI result of reading an eZee report, shown before saving. */
    class ReportPreview(val json: JSONObject, val raw: String) {
        val reportType: String get() = json.optString("report_type").ifBlank { "other" }
        val title: String get() = json.optString("report_title").ifBlank { reportType }
        val reportDate: String? get() = json.optString("report_date").takeIf { it.isNotBlank() && it != "null" }
        val summary: List<Pair<String, String>>
            get() {
                val s = json.optJSONObject("summary") ?: return emptyList()
                return s.keys().asSequence().map { k -> k to s.opt(k).toString() }.toList()
            }
        val rowCount: Int get() = json.optJSONArray("rows")?.length() ?: 0
    }

    val importedFiles: StateFlow<List<ImportedFileEntity>> = importedFileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    private val _reportPreview = MutableStateFlow<ReportPreview?>(null)
    val reportPreview: StateFlow<ReportPreview?> = _reportPreview.asStateFlow()

    private val _isImportBusy = MutableStateFlow(false)
    val isImportBusy: StateFlow<Boolean> = _isImportBusy.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun clearPendingImport() {
        _pendingImport.value = null
        _reportPreview.value = null
    }

    /** Called with a file from the upload button or from "Share" in Gmail / Files. */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            _isImportBusy.value = true
            _importMessage.value = null
            _reportPreview.value = null
            _pendingImport.value = null
            try {
                val app = getApplication<Application>()
                val file = FileImporter.read(app, uri)
                _pendingImport.value = when (file.kind) {
                    FileKind.IMAGE -> PendingImport(
                        file,
                        withContext(Dispatchers.Default) { FileImporter.decodeImage(file.bytes) },
                        1,
                        null
                    )
                    FileKind.PDF -> {
                        val (preview, pages) = FileImporter.renderPdfFirstPage(app, file.bytes)
                        PendingImport(file, preview, pages, null)
                    }
                    FileKind.CSV, FileKind.XLSX, FileKind.HTML_TABLE -> PendingImport(
                        file,
                        null,
                        0,
                        withContext(Dispatchers.Default) { FileImporter.parseTable(file) }
                    )
                    FileKind.UNSUPPORTED -> {
                        _importMessage.value = if (file.extension == "xls") {
                            "✕ ไฟล์ .xls แบบเก่ายังอ่านไม่ได้ — ใน Excel/eZee ให้ Save As เป็น .xlsx หรือ .csv แล้วลองใหม่"
                        } else {
                            "✕ ยังไม่รองรับไฟล์ประเภทนี้ (${file.fileName}) — รองรับ PDF, รูปภาพ, CSV, XLSX"
                        }
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                _importMessage.value = "✕ เปิดไฟล์ไม่สำเร็จ: ${e.localizedMessage ?: "Unknown"}"
            } finally {
                _isImportBusy.value = false
            }
        }
    }

    /**
     * Sends the pending image/PDF to the scanner as an accounting document (slip / receipt / bill).
     * Returns true when the UI should switch to the scanner tab.
     */
    fun useImportAsAccountingDocument(): Boolean {
        val pending = _pendingImport.value ?: return false
        if (pending.file.kind != FileKind.IMAGE && pending.file.kind != FileKind.PDF) return false
        val preview = pending.preview ?: placeholderBitmap()
        setImageBitmap(preview) // also clears any previous result
        if (pending.file.kind == FileKind.PDF) _selectedPdf.value = pending.file
        clearPendingImport()
        return true
    }

    private fun placeholderBitmap(): Bitmap =
        Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.LTGRAY) }

    /** Reads the pending PDF/image as an eZee report with the selected AI. */
    fun readPendingAsReport() {
        val pending = _pendingImport.value ?: return
        val provider = resolveProvider(settingsRepository.settings.value)
        if (provider == null) {
            _importMessage.value = "✕ ยังไม่ได้ใส่ API key — ไปที่แท็บ \"ตั้งค่า\""
            return
        }
        val input = when (pending.file.kind) {
            FileKind.PDF -> DocumentInput.Pdf(pending.file.bytes)
            FileKind.IMAGE -> pending.preview?.let { DocumentInput.Image(it) }
            else -> null
        } ?: run {
            _importMessage.value = "✕ ไฟล์นี้อ่านเป็นรายงานไม่ได้"
            return
        }
        viewModelScope.launch {
            _isImportBusy.value = true
            _importMessage.value = null
            val (service, key, model) = provider
            service.extractReport(input, key, model).fold(
                onSuccess = { raw ->
                    try {
                        _reportPreview.value = ReportPreview(JSONObject(raw), raw)
                    } catch (e: Exception) {
                        _importMessage.value = "✕ AI ตอบกลับไม่ใช่ JSON ที่อ่านได้ ลองอีกครั้ง"
                    }
                },
                onFailure = { _importMessage.value = "✕ อ่านรายงานไม่สำเร็จ: ${it.localizedMessage}" }
            )
            _isImportBusy.value = false
        }
    }

    /** Saves the eZee report (original file + AI summary) and sends it to Google Sheets if set up. */
    fun saveReport() {
        val pending = _pendingImport.value ?: return
        val report = _reportPreview.value ?: return
        viewModelScope.launch {
            _isImportBusy.value = true
            try {
                val path = repository.saveFile(pending.file.bytes, pending.file.extension)
                val id = importedFileDao.insert(
                    ImportedFileEntity(
                        kind = ImportKind.EZEE_REPORT.code,
                        fileName = pending.file.fileName,
                        mimeType = pending.file.mimeType,
                        storedPath = path,
                        reportType = report.reportType,
                        reportDate = report.reportDate,
                        extractedJson = report.raw,
                        rowCount = report.rowCount,
                        targetSheet = "eZee_Reports",
                        status = ImportStatus.SAVED.code,
                        errorMessage = null,
                        sheetSyncedAt = null
                    )
                )
                clearPendingImport()
                _importMessage.value = "✓ บันทึกรายงานแล้ว" + sendImportToSheets(id)
            } catch (e: Exception) {
                Log.e(TAG, "Save report failed", e)
                _importMessage.value = "✕ บันทึกไม่สำเร็จ: ${e.localizedMessage}"
            } finally {
                _isImportBusy.value = false
            }
        }
    }

    /** Saves a CSV/Excel file and sends its rows to [sheetName] in Google Sheets if set up. */
    fun saveTable(sheetName: String) {
        val pending = _pendingImport.value ?: return
        val table = pending.table ?: return
        viewModelScope.launch {
            _isImportBusy.value = true
            try {
                val path = repository.saveFile(pending.file.bytes, pending.file.extension)
                val id = importedFileDao.insert(
                    ImportedFileEntity(
                        kind = ImportKind.TABLE.code,
                        fileName = pending.file.fileName,
                        mimeType = pending.file.mimeType,
                        storedPath = path,
                        reportType = null,
                        reportDate = null,
                        extractedJson = null,
                        rowCount = table.rows.size,
                        targetSheet = sheetName.trim().ifBlank { "Import" },
                        status = ImportStatus.SAVED.code,
                        errorMessage = null,
                        sheetSyncedAt = null
                    )
                )
                clearPendingImport()
                _importMessage.value = "✓ บันทึกไฟล์แล้ว (${table.rows.size} แถว)" + sendImportToSheets(id)
            } catch (e: Exception) {
                Log.e(TAG, "Save table failed", e)
                _importMessage.value = "✕ บันทึกไม่สำเร็จ: ${e.localizedMessage}"
            } finally {
                _isImportBusy.value = false
            }
        }
    }

    /** Re-sends an imported file to Google Sheets (e.g. after a failed send). */
    fun resendImport(id: Long) {
        viewModelScope.launch {
            _isImportBusy.value = true
            _importMessage.value = sendImportToSheets(id).removePrefix(" — ").ifBlank { "Google Sheets ยังไม่ได้ตั้งค่า" }
            _isImportBusy.value = false
        }
    }

    fun deleteImport(id: Long) {
        viewModelScope.launch {
            val entity = importedFileDao.getById(id) ?: return@launch
            importedFileDao.deleteById(id)
            DocumentImageStore(getApplication<Application>()).delete(entity.storedPath)
        }
    }

    /** Sends one imported file to Google Sheets and records the result. Returns a short suffix for messages. */
    private suspend fun sendImportToSheets(id: Long): String {
        val settings = settingsRepository.settings.value
        if (!settings.sheetsEnabled) return ""
        val entity = importedFileDao.getById(id) ?: return ""
        val result: Result<Int> = try {
            when (ImportKind.fromCode(entity.kind)) {
                ImportKind.EZEE_REPORT -> sheetsClient.upsertReport(
                    settings.sheetsWebAppUrl, settings.sheetsToken, "RPT-$id", entity.fileName,
                    JSONObject(entity.extractedJson ?: "{}")
                )
                ImportKind.TABLE -> {
                    val bytes = repository.readFile(entity.storedPath)
                        ?: throw IllegalStateException("ไม่พบไฟล์ต้นฉบับในเครื่อง")
                    val file = PickedFile(
                        entity.fileName, entity.mimeType,
                        FileImporter.detectKind(entity.fileName, entity.mimeType, bytes), bytes
                    )
                    val table = withContext(Dispatchers.Default) { FileImporter.parseTable(file) }
                    sheetsClient.appendTable(
                        settings.sheetsWebAppUrl, settings.sheetsToken, "IMP-$id", entity.fileName,
                        entity.targetSheet ?: "Import", table.headers, table.rows
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
        return result.fold(
            onSuccess = { rows ->
                importedFileDao.update(
                    entity.copy(status = ImportStatus.SYNCED.code, errorMessage = null, sheetSyncedAt = System.currentTimeMillis())
                )
                " — ส่งไป Google Sheets แล้ว ($rows แถว)"
            },
            onFailure = { e ->
                val msg = e.localizedMessage ?: "Unknown"
                importedFileDao.update(entity.copy(status = ImportStatus.SYNC_FAILED.code, errorMessage = msg))
                " — แต่ส่งไป Google Sheets ไม่สำเร็จ: $msg"
            }
        )
    }

    companion object {
        private const val TAG = "AccountantViewModel"
    }
}
