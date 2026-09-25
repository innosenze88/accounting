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
import com.example.data.local.looksLikeDuplicateOf
import com.example.data.local.canDelete
import com.example.data.local.documentStatus
import com.example.util.ContentHash
import com.example.data.local.quickVerifyProblem
import com.example.data.model.TransactionType
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.DocumentStatus
import com.example.data.ezee.EzeePdfReader
import com.example.data.ezee.EzeeReport
import com.example.data.ezee.LineInfo
import com.example.data.ezee.ReportTemplate
import com.example.data.ezee.TemplateEngine
import com.example.data.ezee.TemplateStore
import com.example.data.update.AppUpdater
import com.example.data.update.UpdateInfo
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
    private val templateStore = TemplateStore(application)
    private val updater = AppUpdater(application)

    /** Readers the user created for new report layouts ("ตัวอ่านที่สร้างเอง"). */
    val reportTemplates: StateFlow<List<ReportTemplate>> = templateStore.templates
    private val settingsRepository = AiSettingsRepository(application)
    private val geminiService = GeminiOcrService()
    private val claudeService = ClaudeOcrService { settingsRepository.settings.value.claudeWorkspaceId }

    init {
        val db = AppDatabase.getInstance(application)
        repository = DocumentRepository(db.documentDao(), DocumentImageStore(application))
        importedFileDao = db.importedFileDao()
        // Delete the update APK downloaded last time (the update is installed or was abandoned).
        viewModelScope.launch(Dispatchers.IO) { updater.cleanUp() }
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

    /** SHA-256 of the shared / uploaded file behind the image on screen (null for camera photos). */
    private var _selectedSourceHash: String? = null
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
        _selectedSourceHash = null
        _selectedPdf.value = null
        _selectedSample.value = sample
        _selectedBitmap.value = SampleDocumentGenerator.renderDocumentBitmap(sample.id)
        resetResult()
    }

    fun setImageBitmap(bitmap: Bitmap) {
        _selectedSourceHash = null
        _selectedPdf.value = null
        _selectedSample.value = null
        _selectedBitmap.value = bitmap
        resetResult()
    }

    fun clearImage() {
        _selectedSourceHash = null
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

                    else -> {
                        val sameFile = _selectedSourceHash?.let { findSameFile(it) }
                        if (sameFile != null) {
                            _errorMessage.value = duplicateFileMessage(sameFile)
                        } else {
                            runRealExtraction(bitmap, _selectedPdf.value, requireNotNull(provider), _selectedSourceHash)
                        }
                    }
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
        provider: Triple<OcrService, String, String>,
        contentHash: String?
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
                    val id = repository.savePendingDocument(doc, raw, imagePath, sourcePath, contentHash)
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

    /** Deletes a document that was never counted. Counted documents can only be voided ([voidDocument]). */
    fun deleteHistory(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteDocument(id)
                if (_currentRecordId.value == id) clearImage()
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "ลบไม่สำเร็จ"
            }
        }
    }

    private val _actionMessage = MutableStateFlow<String?>(null)
    /** Result of a void / delete done from the history list (shown once, then cleared). */
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()
    fun clearActionMessage() { _actionMessage.value = null }

    /**
     * Cancels a counted document with a reason: it stops counting, stays in the history as a record,
     * and is moved from "Accounting" to "Voided" in Google Sheets.
     */
    fun voidDocument(id: Long, reason: String) {
        viewModelScope.launch {
            try {
                repository.voidDocument(id, reason) ?: return@launch
                val sheetError = sendVoidToSheets(id)
                _actionMessage.value = "✓ ยกเลิกรายการแล้ว — ไม่นับในยอดอีกต่อไป" +
                    (sheetError?.let { "\n✕ Google Sheets: $it (กด \"ส่งที่ค้าง\" ในหน้าตั้งค่าเพื่อส่งอีกครั้ง)" } ?: "")
            } catch (e: Exception) {
                Log.e(TAG, "Void failed", e)
                _actionMessage.value = "✕ ยกเลิกไม่สำเร็จ: ${e.localizedMessage ?: "Unknown"}"
            }
        }
    }

    /** Sends a void to Google Sheets when the document was sent there before. Returns the error text or null. */
    private suspend fun sendVoidToSheets(id: Long): String? {
        val settings = settingsRepository.settings.value
        val doc = repository.getDocumentById(id) ?: return null
        if (!settings.sheetsEnabled || doc.sheetSyncedAt == null) return null
        return sheetsClient.voidDocument(settings.sheetsWebAppUrl, settings.sheetsToken, doc).fold(
            onSuccess = { repository.markSheetSynced(id); null },
            onFailure = { sheetsErrorText(it) }
        )
    }

    private fun sheetsErrorText(e: Throwable): String {
        val msg = e.localizedMessage ?: "Unknown"
        return if ("unknown action" in msg) {
            "ต้องอัปเดตโค้ด Apps Script ก่อน (ตั้งค่า → คัดลอกโค้ด Apps Script → วางแทนของเดิม → Deploy เวอร์ชันใหม่)"
        } else msg
    }

    /** An active document (not rejected / voided) made from the very same file, if any. */
    private suspend fun findSameFile(hash: String): ExtractedDocumentEntity? =
        repository.getByContentHash(hash).firstOrNull {
            it.sampleId == null && it.documentStatus() != DocumentStatus.REJECTED &&
                it.documentStatus() != DocumentStatus.VOIDED
        }

    private fun describeDocument(e: ExtractedDocumentEntity): String =
        "เอกสาร #${e.id}" + (e.date?.let { " วันที่ $it" } ?: "") +
            (e.totalAmount?.let { " ยอด ${String.format(java.util.Locale.US, "%,.2f", it)} ฿" } ?: "") +
            " (${e.documentStatus().titleTh})"

    private fun duplicateFileMessage(e: ExtractedDocumentEntity): String =
        "ไฟล์นี้นำเข้าไปแล้ว — ${describeDocument(e)}" +
            if (e.documentStatus() == DocumentStatus.PENDING) " ไปที่แท็บประวัติเพื่อตรวจและยืนยันใบเดิม" else ""

    /**
     * Another active document that looks like the one on screen (same file, same no. + amount,
     * or same date + amount + seller). Shown as a warning before verifying.
     */
    val duplicateOfCurrent: StateFlow<ExtractedDocumentEntity?> =
        kotlinx.coroutines.flow.combine(currentRecord, repository.allDocuments) { current, all ->
            if (current == null || current.sampleId != null) return@combine null
            val matches = all.filter { current.looksLikeDuplicateOf(it) }
            matches.firstOrNull { it.documentStatus() == DocumentStatus.VERIFIED } ?: matches.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
            list.count {
                it.sampleId == null && (
                    (it.status == DocumentStatus.VERIFIED.code &&
                        (it.sheetSyncedAt == null || it.sheetSyncedAt < (it.verifiedAt ?: 0L))) ||
                        (it.status == DocumentStatus.VOIDED.code && it.sheetSyncedAt != null &&
                            it.sheetSyncedAt < (it.voidedAt ?: 0L))
                    )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Returns null on success, otherwise the error text. */
    private suspend fun syncDocumentToSheets(id: Long): String? {
        val settings = settingsRepository.settings.value
        val doc = repository.getDocumentById(id) ?: return "ไม่พบเอกสาร"
        return sheetsClient.upsertDocument(settings.sheetsWebAppUrl, settings.sheetsToken, doc).fold(
            onSuccess = { repository.markSheetSynced(id); null },
            onFailure = { sheetsErrorText(it) }
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
            val pending = repository.getVerifiedNotSynced() + repository.getVoidedNotSynced()
            var ok = 0
            var lastError: String? = null
            for (doc in pending) {
                val error = if (doc.documentStatus() == DocumentStatus.VOIDED) sendVoidToSheets(doc.id)
                else syncDocumentToSheets(doc.id)
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
        /** true = read by the built-in eZee reader (numbers copied exactly from the PDF, no AI). */
        val readLocally: Boolean
            get() = json.optString("parser").let { it.startsWith("ezee_local") || it.startsWith(TemplateEngine.PARSER_PREFIX) }

        /** true = read by a reader the user made in the app ("ตัวอ่านที่สร้างเอง"). */
        val readByTemplate: Boolean get() = json.optString("parser").startsWith(TemplateEngine.PARSER_PREFIX)

        /** Thai name for a summary key (local reader), else the key itself. */
        fun labelFor(key: String): String = json.optJSONObject("labels")?.optString(key)?.ifBlank { null } ?: key

        /** Automatic cross-checks from the local reader: label + passed. */
        val checks: List<Pair<String, Boolean>>
            get() {
                val arr = json.optJSONArray("checks") ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { it.optString("label") to it.optBoolean("ok") }
                }
            }

        val notes: String? get() = json.optString("notes").takeIf { it.isNotBlank() && it != "null" }

        /** Reports like statistics or ledgers must not be added to the totals by default. */
        val countByDefault: Boolean get() = if (json.has("count_by_default")) json.optBoolean("count_by_default") else true

        val reportType: String get() = json.optString("report_type").ifBlank { "other" }
        val title: String get() = json.optString("report_title").ifBlank { reportType }
        val reportDate: String? get() = json.optString("report_date").takeIf { it.isNotBlank() && it != "null" }
        val summary: List<Pair<String, String>>
            get() {
                val s = json.optJSONObject("summary") ?: return emptyList()
                return s.keys().asSequence().map { k -> k to s.opt(k).toString() }.toList()
            }
        val rowCount: Int get() = json.optJSONArray("rows")?.length() ?: 0

        /** Summary values that are numbers (for the "amount to count" quick-pick). */
        val numericSummary: List<Pair<String, Double>>
            get() {
                val s = json.optJSONObject("summary") ?: return emptyList()
                return s.keys().asSequence().mapNotNull { k ->
                    val v = s.opt(k)
                    val d = when (v) {
                        is Number -> v.toDouble()
                        else -> v?.toString()?.replace(",", "")?.trim()?.toDoubleOrNull()
                    }
                    if (d == null || d.isNaN() || isNonMoneyKey(k)) null else k to d
                }.toList()
            }

        /** Expense-type reports (vouchers, purchases, payouts) count as EXPENSE, the rest as INCOME. */
        val suggestedTransaction: TransactionType
            get() {
                TransactionType.fromCode(json.optString("suggested_transaction").takeIf { it != "null" })?.let { return it }
                val t = (reportType + " " + title).lowercase()
                return if (EXPENSE_WORDS.any { it in t }) TransactionType.EXPENSE else TransactionType.INCOME
            }

        /** Best summary value for the chosen direction, e.g. total_revenue for income. */
        fun suggestedAmount(tx: TransactionType): Double? {
            if (readLocally) {
                // The local reader says exactly which number to count (or none, e.g. statistics).
                val suggestedTx = TransactionType.fromCode(json.optString("suggested_transaction").takeIf { it != "null" })
                val amount = json.optDouble("suggested_amount", Double.NaN)
                return if (suggestedTx == tx && !amount.isNaN() && amount > 0.0) amount else null
            }
            val values = numericSummary.toMap()
            val keys = if (tx == TransactionType.INCOME) INCOME_KEYS else EXPENSE_KEYS
            return keys.firstNotNullOfOrNull { values[it] }
        }

        companion object {
            private val EXPENSE_WORDS = listOf("expense", "voucher", "purchase", "payout", "paid_out", "รายจ่าย", "ค่าใช้จ่าย")
            private val INCOME_KEYS = listOf(
                "total_revenue", "net_revenue", "gross_revenue", "daily_revenue", "total_income", "total_sales",
                "total_payments", "total_collection", "room_revenue", "revenue"
            )
            private val EXPENSE_KEYS = listOf(
                "total_expense", "total_expenses", "expense_total", "total_paid", "total_payout", "total_amount", "total"
            )
            private val NON_MONEY_WORDS = listOf(
                "percent", "occupancy", "rooms", "nights", "guests", "pax", "count", "adr", "revpar", "adult", "complimentary"
            )
            fun isNonMoneyKey(key: String): Boolean = NON_MONEY_WORDS.any { it in key.lowercase() }
        }
    }

    /** documentNo used for the accounting row created from imported report [importId]. */
    private fun reportDocumentNo(importId: Long) = "$REPORT_DOC_PREFIX$importId"

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
                        // A known eZee report is shown at once (read on the phone, no AI).
                        EzeePdfReader.read(app, file.bytes, file.fileName, templateStore.templates.value)?.let { report ->
                            val json = EzeePdfReader.toJson(report)
                            _reportPreview.value = ReportPreview(json, json.toString())
                        }
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
        // Fingerprint of the original shared/uploaded file: the same slip imported twice is caught before the AI runs.
        _selectedSourceHash = ContentHash.sha256(pending.file.bytes)
        clearPendingImport()
        return true
    }

    private fun placeholderBitmap(): Bitmap =
        Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.LTGRAY) }

    /**
     * Reads the pending PDF/image as an eZee report.
     * Known eZee PDFs are read on the phone first (exact numbers, no AI, works offline);
     * anything else goes to the selected AI.
     */
    fun readPendingAsReport() {
        val pending = _pendingImport.value ?: return
        if (pending.file.kind == FileKind.PDF) {
            viewModelScope.launch {
                _isImportBusy.value = true
                _importMessage.value = null
                val local = EzeePdfReader.read(
                    getApplication<Application>(), pending.file.bytes, pending.file.fileName, templateStore.templates.value
                )
                _isImportBusy.value = false
                if (local != null) {
                    val json = EzeePdfReader.toJson(local)
                    _reportPreview.value = ReportPreview(json, json.toString())
                } else {
                    readPendingAsReportWithAi(pending)
                }
            }
        } else {
            readPendingAsReportWithAi(pending)
        }
    }

    private fun readPendingAsReportWithAi(pending: PendingImport) {
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
    /**
     * @param countAs  INCOME / EXPENSE to add [amount] to the dashboard totals, or null to only keep the report.
     *                 The person sees the numbers on screen before pressing save, so the row is saved VERIFIED.
     */
    fun saveReport(countAs: TransactionType? = null, amount: Double? = null) {
        val pending = _pendingImport.value ?: return
        val report = _reportPreview.value ?: return
        if (countAs != null && (amount == null || amount.isNaN() || amount <= 0.0)) {
            _importMessage.value = "✕ ใส่ยอดเงินที่จะนับเข้ายอดให้ถูกต้องก่อน (มากกว่า 0)"
            return
        }
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
                var counted = " (ไม่นับรวมในยอด)"
                if (countAs != null && amount != null) {
                    val docId = repository.saveVerifiedDocument(
                        AccountingDocumentJson(
                            documentType = "OTHER",
                            transactionType = countAs.code,
                            documentNo = reportDocumentNo(id),
                            date = report.reportDate,
                            sellerName = if (countAs == TransactionType.EXPENSE) report.title else null,
                            customerName = if (countAs == TransactionType.INCOME) report.title else null,
                            totalAmount = amount,
                            paymentMethod = null,
                            lineItems = emptyList()
                        ),
                        rawJson = report.raw,
                        // The original file belongs to the imported report (deleting this row must not delete it).
                        sourceFilePath = null
                    )
                    counted = " — นับเป็น${countAs.titleTh} ${String.format("%,.2f ฿", amount)} แล้ว"
                    val settings = settingsRepository.settings.value
                    if (settings.sheetsEnabled && settings.sheetsAutoSync) syncDocumentToSheets(docId)
                }
                clearPendingImport()
                _importMessage.value = "✓ บันทึกรายงานแล้ว$counted" + sendImportToSheets(id)
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
                _importMessage.value = "✓ บันทึกไฟล์แล้ว (${table.rows.size} แถว — ตารางไม่ถูกนับรวมในยอดแดชบอร์ด)" + sendImportToSheets(id)
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
            // A report that was counted in the totals: its accounting row is voided (kept as a record), not deleted.
            val counted = repository.getByDocumentNo(reportDocumentNo(id))
            var sheetError: String? = null
            counted.forEach { doc ->
                when {
                    doc.canDelete() -> repository.deleteRowOnly(doc.id)
                    doc.documentStatus() != DocumentStatus.VOIDED -> {
                        repository.voidDocument(doc.id, "ลบรายงาน eZee: ${entity.fileName}")
                        sendVoidToSheets(doc.id)?.let { sheetError = it }
                    }
                }
            }
            if (counted.isNotEmpty()) {
                _importMessage.value = "✓ ลบรายงานแล้ว — ยอดที่นับไว้ถูกยกเลิก (ดูได้ในประวัติ)" +
                    (sheetError?.let { " • Google Sheets: $it" } ?: "")
            }
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

    // ================================================================ Batch import (many files at once)

    enum class BatchState(val titleTh: String) {
        WAITING("รอคิว"),
        READING("กำลังอ่าน..."),
        DOCUMENT("อ่านแล้ว — รอยืนยัน"),
        TABLE("บันทึกตารางแล้ว"),
        REPORT("บันทึกรายงาน eZee แล้ว"),
        FAILED("ไม่สำเร็จ"),
        SKIPPED("ข้าม")
    }

    /** One file in a multi-file upload. [documentId] is set when it became an accounting document. */
    data class BatchItem(
        val index: Int,
        val fileName: String,
        val state: BatchState,
        val message: String? = null,
        val documentId: Long? = null
    )

    private val _batchItems = MutableStateFlow<List<BatchItem>>(emptyList())
    val batchItems: StateFlow<List<BatchItem>> = _batchItems.asStateFlow()

    private fun updateBatch(index: Int, change: (BatchItem) -> BatchItem) {
        _batchItems.value = _batchItems.value.map { if (it.index == index) change(it) else it }
    }

    fun clearBatch() {
        if (!_isImportBusy.value) _batchItems.value = emptyList()
    }

    /** Upload button / "Share" with one or more files. One file keeps the old step-by-step flow. */
    fun onFilesPicked(uris: List<Uri>) {
        val list = uris.distinct()
        when {
            list.isEmpty() -> return
            list.size == 1 -> {
                _batchItems.value = emptyList()
                onFilePicked(list[0])
            }
            else -> startBatch(list)
        }
    }

    /**
     * Reads every file one after another:
     * PDF / image -> AI reads it as a slip / receipt / bill and it is saved as PENDING (needs approval)
     * CSV / Excel -> saved as a table (sent to Google Sheets when set up; never counted in totals)
     */
    private fun startBatch(uris: List<Uri>) {
        if (_isImportBusy.value) {
            _importMessage.value = "✕ กำลังทำงานอยู่ รอให้เสร็จก่อนแล้วเลือกไฟล์ใหม่"
            return
        }
        clearPendingImport()
        _importMessage.value = null
        _batchItems.value = uris.mapIndexed { i, _ -> BatchItem(i, "ไฟล์ที่ ${i + 1}", BatchState.WAITING) }
        val provider = resolveProvider(settingsRepository.settings.value)

        viewModelScope.launch {
            _isImportBusy.value = true
            try {
                val app = getApplication<Application>()
                for ((i, uri) in uris.withIndex()) {
                    updateBatch(i) { it.copy(state = BatchState.READING) }
                    val file = try {
                        FileImporter.read(app, uri)
                    } catch (e: Exception) {
                        updateBatch(i) { it.copy(state = BatchState.FAILED, message = e.localizedMessage ?: "เปิดไฟล์ไม่ได้") }
                        continue
                    }
                    updateBatch(i) { it.copy(fileName = file.fileName) }

                    // eZee reports in a batch are read locally and saved as reports (never as receipts).
                    if (file.kind == FileKind.PDF) {
                        val report = EzeePdfReader.read(app, file.bytes, file.fileName, templateStore.templates.value)
                        if (report != null) {
                            try {
                                val sheets = saveLocalReport(file, report)
                                updateBatch(i) {
                                    it.copy(
                                        state = BatchState.REPORT,
                                        message = "${report.title} ${report.reportDate ?: ""} — ไม่นับในยอด " +
                                            "(เปิดทีละไฟล์ถ้าต้องการนับ)$sheets"
                                    )
                                }
                            } catch (e: Exception) {
                                updateBatch(i) { it.copy(state = BatchState.FAILED, message = e.localizedMessage ?: "บันทึกไม่สำเร็จ") }
                            }
                            continue
                        }
                    }

                    when (file.kind) {
                        FileKind.IMAGE, FileKind.PDF -> {
                            if (provider == null) {
                                updateBatch(i) { it.copy(state = BatchState.FAILED, message = "ยังไม่ได้ใส่ API key (แท็บ \"ตั้งค่า\")") }
                                continue
                            }
                            val sameFile = findSameFile(ContentHash.sha256(file.bytes))
                            if (sameFile != null) {
                                updateBatch(i) {
                                    it.copy(state = BatchState.SKIPPED, message = "ไฟล์ซ้ำ — ${describeDocument(sameFile)}")
                                }
                                continue
                            }
                            readBatchDocument(file, provider).fold(
                                onSuccess = { id ->
                                    updateBatch(i) { it.copy(state = BatchState.DOCUMENT, documentId = id) }
                                },
                                onFailure = { e ->
                                    updateBatch(i) { it.copy(state = BatchState.FAILED, message = e.localizedMessage ?: "อ่านไม่สำเร็จ") }
                                }
                            )
                        }
                        FileKind.CSV, FileKind.XLSX, FileKind.HTML_TABLE -> {
                            try {
                                val table = withContext(Dispatchers.Default) { FileImporter.parseTable(file) }
                                if (table.headers.isEmpty()) throw IllegalArgumentException("ไฟล์ว่าง")
                                val path = repository.saveFile(file.bytes, file.extension)
                                val id = importedFileDao.insert(
                                    ImportedFileEntity(
                                        kind = ImportKind.TABLE.code,
                                        fileName = file.fileName,
                                        mimeType = file.mimeType,
                                        storedPath = path,
                                        reportType = null,
                                        reportDate = null,
                                        extractedJson = null,
                                        rowCount = table.rows.size,
                                        targetSheet = file.fileName.substringBeforeLast('.')
                                            .replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").take(60).ifBlank { "Import" },
                                        status = ImportStatus.SAVED.code,
                                        errorMessage = null,
                                        sheetSyncedAt = null
                                    )
                                )
                                val sheets = sendImportToSheets(id)
                                updateBatch(i) {
                                    it.copy(state = BatchState.TABLE, message = "${table.rows.size} แถว — ไม่นับในยอด$sheets")
                                }
                            } catch (e: Exception) {
                                updateBatch(i) { it.copy(state = BatchState.FAILED, message = e.localizedMessage ?: "อ่านตารางไม่ได้") }
                            }
                        }
                        FileKind.UNSUPPORTED -> updateBatch(i) {
                            it.copy(state = BatchState.SKIPPED, message = "ไม่รองรับไฟล์ประเภทนี้")
                        }
                    }
                }
                val items = _batchItems.value
                val docs = items.count { it.state == BatchState.DOCUMENT }
                val reports = items.count { it.state == BatchState.REPORT }
                val failed = items.count { it.state == BatchState.FAILED }
                _importMessage.value = (if (failed > 0) "✕ " else "✓ ") +
                    "อ่านเสร็จ ${items.size} ไฟล์ — เอกสารบัญชี $docs ใบรอยืนยัน" +
                    (if (reports > 0) ", รายงาน eZee $reports ไฟล์" else "") +
                    (if (failed > 0) ", ไม่สำเร็จ $failed ไฟล์" else "") +
                    (if (docs > 0) " • ต้องกด \"ยืนยัน\" ก่อน ยอดจึงจะขึ้นในแดชบอร์ด" else "")
            } finally {
                _isImportBusy.value = false
            }
        }
    }

    // ================================================================ LINE slips (LINE OA -> Apps Script -> Drive)

    /**
     * Pulls slips/receipts that were sent to the resort's LINE OA (saved in Drive by the Apps Script),
     * reads each one with AI and saves it as PENDING (needs approval, like any other upload).
     * Files already in the app (same content) are marked DUPLICATE and skipped.
     */
    fun pullLineSlips() {
        if (_isImportBusy.value) {
            _importMessage.value = "✕ กำลังทำงานอยู่ รอให้เสร็จก่อน"
            return
        }
        val settings = settingsRepository.settings.value
        if (settings.sheetsWebAppUrl.isBlank() || settings.sheetsToken.isBlank()) {
            _importMessage.value = "✕ ต้องตั้งค่า Google Sheets (Apps Script) ก่อน — ดูวิธีตั้ง LINE ใน LINE_SETUP.md"
            return
        }
        val provider = resolveProvider(settings)
        if (provider == null) {
            _importMessage.value = "✕ ยังไม่ได้ใส่ API key (แท็บ \"ตั้งค่า\")"
            return
        }
        clearPendingImport()
        _importMessage.value = "กำลังดึงรายการจาก LINE..."
        viewModelScope.launch {
            _isImportBusy.value = true
            try {
                val url = settings.sheetsWebAppUrl
                val token = settings.sheetsToken
                val list = sheetsClient.listLineInbox(url, token).getOrElse { e ->
                    _importMessage.value = "✕ ดึงจาก LINE ไม่สำเร็จ: ${sheetsErrorText(e)}"
                    return@launch
                }
                if (list.isEmpty()) {
                    _importMessage.value = "ไม่มีสลิปใหม่ใน LINE"
                    return@launch
                }
                _batchItems.value = list.mapIndexed { i, item ->
                    BatchItem(i, "LINE: ${item.fileName.ifBlank { "ไฟล์" }} (${item.sender.ifBlank { "?" }})", BatchState.WAITING)
                }
                for ((i, item) in list.withIndex()) {
                    updateBatch(i) { it.copy(state = BatchState.READING) }
                    val bytes = sheetsClient.getLineFile(url, token, item.fileId).getOrElse { e ->
                        updateBatch(i) { it.copy(state = BatchState.FAILED, message = "โหลดไฟล์ไม่ได้: ${e.localizedMessage}") }
                        continue
                    }
                    val kind = when {
                        item.mime.startsWith("image/") -> FileKind.IMAGE
                        item.mime == "application/pdf" -> FileKind.PDF
                        else -> FileKind.UNSUPPORTED
                    }
                    if (kind == FileKind.UNSUPPORTED) {
                        updateBatch(i) { it.copy(state = BatchState.SKIPPED, message = "ไม่รองรับไฟล์ ${item.mime}") }
                        sheetsClient.markLineImported(url, token, item.id, "FAILED", null, "unsupported ${item.mime}")
                        continue
                    }
                    val ext = if (kind == FileKind.PDF) "pdf" else "jpg"
                    val name = item.fileName.ifBlank { "line-${item.id}.$ext" }
                    val file = PickedFile(name, item.mime, kind, bytes)
                    val same = findSameFile(ContentHash.sha256(bytes))
                    if (same != null) {
                        updateBatch(i) { it.copy(state = BatchState.SKIPPED, message = "ไฟล์ซ้ำ — ${describeDocument(same)}") }
                        sheetsClient.markLineImported(url, token, item.id, "DUPLICATE", same.id, null)
                        continue
                    }
                    readBatchDocument(file, provider).fold(
                        onSuccess = { id ->
                            updateBatch(i) { it.copy(state = BatchState.DOCUMENT, documentId = id) }
                            sheetsClient.markLineImported(url, token, item.id, "IMPORTED", id, null)
                        },
                        onFailure = { e ->
                            updateBatch(i) { it.copy(state = BatchState.FAILED, message = e.localizedMessage ?: "อ่านไม่สำเร็จ") }
                            // Marked FAILED so it is not retried forever; the file stays in Drive to add by hand.
                            sheetsClient.markLineImported(url, token, item.id, "FAILED", null, e.localizedMessage)
                        }
                    )
                }
                val items = _batchItems.value
                val docs = items.count { it.state == BatchState.DOCUMENT }
                val dup = items.count { it.state == BatchState.SKIPPED }
                val failed = items.count { it.state == BatchState.FAILED }
                _importMessage.value = (if (failed > 0) "✕ " else "✓ ") +
                    "ดึงจาก LINE ${items.size} ไฟล์ — รอยืนยัน $docs ใบ" +
                    (if (dup > 0) ", ข้าม $dup" else "") +
                    (if (failed > 0) ", ไม่สำเร็จ $failed (ไฟล์ยังอยู่ในโฟลเดอร์ LINE Slips ใน Drive)" else "")
            } catch (e: Exception) {
                _importMessage.value = "✕ ${e.localizedMessage ?: "ดึงจาก LINE ไม่สำเร็จ"}"
            } finally {
                _isImportBusy.value = false
            }
        }
    }

    // ================================================================ In-app update from GitHub Releases

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data class Available(val info: UpdateInfo) : UpdateState
        data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
        data class ReadyToInstall(val info: UpdateInfo, val file: java.io.File) : UpdateState
        data class Error(val message: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    val currentAppVersion: String get() = updater.currentVersion

    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            _updateState.value = updater.checkLatest().fold(
                onSuccess = { info -> if (info == null) UpdateState.UpToDate else UpdateState.Available(info) },
                onFailure = { UpdateState.Error("ตรวจสอบไม่สำเร็จ: ${it.localizedMessage ?: "ไม่มีอินเทอร์เน็ต?"}") }
            )
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        if (_updateState.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(info, 0f)
            var lastShown = -1
            updater.download(info) { p ->
                // Update the screen at most every 2 %.
                val step = (p * 50).toInt()
                if (step != lastShown) {
                    lastShown = step
                    _updateState.value = UpdateState.Downloading(info, p)
                }
            }.fold(
                onSuccess = { file ->
                    _updateState.value = UpdateState.ReadyToInstall(info, file)
                    installUpdate()
                },
                onFailure = { _updateState.value = UpdateState.Error(it.localizedMessage ?: "ดาวน์โหลดไม่สำเร็จ") }
            )
        }
    }

    /** Opens Android's installer. First time on Android 8+: asks to allow installing from this app. */
    fun installUpdate() {
        val ready = _updateState.value as? UpdateState.ReadyToInstall ?: return
        if (!updater.canInstall()) {
            updater.openInstallPermissionSettings()
            return
        }
        try {
            updater.install(ready.file)
        } catch (e: Exception) {
            Log.e(TAG, "Install intent failed", e)
            _updateState.value = UpdateState.Error("เปิดหน้าติดตั้งไม่ได้: ${e.localizedMessage}")
        }
    }

    fun canInstallUpdates(): Boolean = updater.canInstall()

    fun openReleasesPage() = updater.openReleasesPage()

    // ================================================================ Custom readers ("ตัวอ่านที่สร้างเอง")

    /** What the reader builder shows: the report lines of the pending PDF. [editing] = the reader being changed. */
    class BuilderSession(
        val fileName: String,
        val pages: List<List<String>>,
        val lines: List<LineInfo>,
        val editing: ReportTemplate?
    )

    private val _builder = MutableStateFlow<BuilderSession?>(null)
    val builder: StateFlow<BuilderSession?> = _builder.asStateFlow()

    /** Opens the reader builder for the pending PDF. */
    fun openTemplateBuilder() {
        val pending = _pendingImport.value ?: return
        if (pending.file.kind != FileKind.PDF) {
            _importMessage.value = "✕ สร้างตัวอ่านได้เฉพาะไฟล์ PDF"
            return
        }
        viewModelScope.launch {
            _isImportBusy.value = true
            val pages = withContext(Dispatchers.Default) {
                try {
                    EzeePdfReader.pageLines(getApplication<Application>(), pending.file.bytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Builder: PDF read failed", e)
                    emptyList()
                }
            }
            val lines = TemplateEngine.analyze(pages)
            _isImportBusy.value = false
            if (lines.none { it.numbers.isNotEmpty() }) {
                _importMessage.value = "✕ ไฟล์นี้ไม่มีตัวอักษรให้อ่าน (น่าจะเป็นรูปสแกน) — สร้างตัวอ่านไม่ได้ ให้ใช้ AI อ่านแทน"
                return@launch
            }
            val editing = TemplateEngine.find(templateStore.templates.value, pages, pending.file.fileName)
            _builder.value = BuilderSession(pending.file.fileName, pages, lines, editing)
        }
    }

    fun closeTemplateBuilder() {
        _builder.value = null
    }

    /** Saves the reader, closes the builder and reads the pending file again with it. */
    fun saveTemplate(t: ReportTemplate) {
        viewModelScope.launch {
            try {
                templateStore.save(t)
                _builder.value = null
                _reportPreview.value = null
                _importMessage.value = "✓ บันทึกตัวอ่าน \"${t.name}\" แล้ว — ไฟล์แบบนี้ครั้งต่อไปจะอ่านให้อัตโนมัติ"
                readPendingAsReport()
            } catch (e: Exception) {
                _importMessage.value = "✕ บันทึกตัวอ่านไม่สำเร็จ: ${e.localizedMessage}"
            }
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch { templateStore.delete(id) }
    }

    /** All readers as JSON (for backup / another phone / sending to a developer to check). */
    fun exportTemplates(): String = templateStore.exportJson()

    fun importTemplates(text: String) {
        viewModelScope.launch {
            _importMessage.value = try {
                val n = templateStore.importJson(text)
                if (n == 0) "✕ ไม่พบตัวอ่านในข้อความที่วาง" else "✓ นำเข้าตัวอ่าน $n แบบแล้ว"
            } catch (e: Exception) {
                "✕ ข้อความนี้ไม่ใช่ตัวอ่านที่ถูกต้อง: ${e.localizedMessage}"
            }
        }
    }

    /** Saves an eZee report read by the local reader (original PDF + JSON), not counted in totals. */
    private suspend fun saveLocalReport(file: PickedFile, report: EzeeReport): String {
        val json = EzeePdfReader.toJson(report).toString()
        val path = repository.saveFile(file.bytes, file.extension)
        val id = importedFileDao.insert(
            ImportedFileEntity(
                kind = ImportKind.EZEE_REPORT.code,
                fileName = file.fileName,
                mimeType = file.mimeType,
                storedPath = path,
                reportType = report.type,
                reportDate = report.reportDate,
                extractedJson = json,
                rowCount = report.rows.size,
                targetSheet = "eZee_Reports",
                status = ImportStatus.SAVED.code,
                errorMessage = null,
                sheetSyncedAt = null
            )
        )
        return sendImportToSheets(id)
    }

    /** AI-reads one slip/receipt file and saves it as PENDING with its original file. Returns the record id. */
    private suspend fun readBatchDocument(
        file: PickedFile,
        provider: Triple<OcrService, String, String>
    ): Result<Long> = runCatching {
        val app = getApplication<Application>()
        val (service, key, model) = provider
        val evidence: Bitmap
        val input: DocumentInput
        if (file.kind == FileKind.PDF) {
            evidence = FileImporter.renderPdfFirstPage(app, file.bytes).first ?: placeholderBitmap()
            input = DocumentInput.Pdf(file.bytes)
        } else {
            evidence = withContext(Dispatchers.Default) { FileImporter.decodeImage(file.bytes) }
                ?: throw IllegalArgumentException("เปิดรูปภาพไม่ได้")
            input = DocumentInput.Image(evidence)
        }
        val (doc, raw) = service.extract(input, key, model).getOrThrow()
        val imagePath = repository.saveImage(evidence)
        val sourcePath = if (file.kind == FileKind.PDF) repository.saveFile(file.bytes, file.extension) else null
        repository.savePendingDocument(doc, raw, imagePath, sourcePath, ContentHash.sha256(file.bytes))
    }

    /** Problems shown next to a batch document (missing data or a possible duplicate). */
    fun batchDocumentNote(entity: ExtractedDocumentEntity, all: List<ExtractedDocumentEntity>): String? {
        val problem = entity.quickVerifyProblem()
        val dup = all.firstOrNull { entity.looksLikeDuplicateOf(it) }
        return listOfNotNull(
            problem?.let { "ต้องตรวจเอง: $it" },
            dup?.let { "อาจซ้ำกับเอกสาร #${it.id}" + (it.documentNo?.let { n -> " ($n)" } ?: "") }
        ).joinToString(" • ").ifEmpty { null }
    }

    /**
     * Approves many PENDING documents at once so they count in the totals.
     * Only documents with complete data (same rules as the review form) are approved;
     * the rest are left PENDING for a person to open and fix.
     */
    fun verifyDocuments(ids: List<Long>) {
        if (ids.isEmpty() || _isImportBusy.value) return
        viewModelScope.launch {
            _isImportBusy.value = true
            var ok = 0
            var skipped = 0
            var duplicates = 0
            var sheetError: String? = null
            try {
                val settings = settingsRepository.settings.value
                for (id in ids) {
                    val entity = repository.getDocumentById(id)
                    if (entity == null || entity.documentStatus() != DocumentStatus.PENDING || entity.quickVerifyProblem() != null) {
                        skipped++
                        continue
                    }
                    // Never count the same slip twice: skip it when an already counted document matches.
                    val all = repository.getAllOnce()
                    if (all.any { it.documentStatus() == DocumentStatus.VERIFIED && entity.looksLikeDuplicateOf(it) }) {
                        duplicates++
                        continue
                    }
                    val tx = TransactionType.fromCode(entity.transactionType)!!.code
                    repository.verifyDocument(id, entity.toAccountingDocument().copy(transactionType = tx))
                    ok++
                    if (settings.sheetsEnabled && settings.sheetsAutoSync) {
                        syncDocumentToSheets(id)?.let { sheetError = it }
                    }
                }
                _importMessage.value = "✓ ยืนยันแล้ว $ok เอกสาร — นับเข้ายอดแดชบอร์ดแล้ว" +
                    (if (skipped > 0) " • ข้าม $skipped เอกสารที่ข้อมูลไม่ครบ (กด \"ตรวจ\" เพื่อแก้)" else "") +
                    (if (duplicates > 0) " • ข้าม $duplicates เอกสารที่ซ้ำกับที่นับไว้แล้ว (เปิดตรวจทีละใบ)" else "") +
                    (sheetError?.let { " • ส่ง Google Sheets ไม่สำเร็จบางรายการ: $it" } ?: "")
            } catch (e: Exception) {
                Log.e(TAG, "Bulk verify failed", e)
                _importMessage.value = "✕ ยืนยันไม่สำเร็จ: ${e.localizedMessage ?: "Unknown"} (ยืนยันไปแล้ว $ok เอกสาร)"
            } finally {
                _isImportBusy.value = false
            }
        }
    }

    companion object {
        private const val TAG = "AccountantViewModel"
        /** documentNo prefix of accounting rows created from an imported eZee report. */
        const val REPORT_DOC_PREFIX = "eZee RPT-"
    }
}
