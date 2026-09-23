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
import com.example.data.network.GeminiOcrService
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
    private val ocrService = GeminiOcrService()

    init {
        val db = AppDatabase.getInstance(application)
        repository = DocumentRepository(db.documentDao(), DocumentImageStore(application))
    }

    val historyList: StateFlow<List<ExtractedDocumentEntity>> = repository.allDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedBitmap: StateFlow<Bitmap?> = _selectedBitmap.asStateFlow()

    private val _selectedSample = MutableStateFlow<SampleDocument?>(null)
    val selectedSample: StateFlow<SampleDocument?> = _selectedSample.asStateFlow()

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
        _selectedSample.value = sample
        _selectedBitmap.value = SampleDocumentGenerator.renderDocumentBitmap(sample.id)
        resetResult()
    }

    fun setImageBitmap(bitmap: Bitmap) {
        _selectedSample.value = null
        _selectedBitmap.value = bitmap
        resetResult()
    }

    fun clearImage() {
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
                val apiKey = BuildConfig.GEMINI_API_KEY
                val hasValidApiKey = !apiKey.isNullOrBlank() && apiKey != "MY_GEMINI_API_KEY"

                when {
                    // Sample document: show the result for testing, but never save it.
                    currentSample != null -> runSampleExtraction(currentSample, bitmap, hasValidApiKey)

                    !hasValidApiKey -> _errorMessage.value =
                        "ยังไม่ได้ระบุ GEMINI_API_KEY ใน AI Studio Secrets! ท่านสามารถทดสอบสแกนตัวอย่างเอกสารด้านบนได้ (ผลตัวอย่างจะไม่ถูกบันทึกลงบัญชี)"

                    else -> runRealExtraction(bitmap)
                }
            } finally {
                _isExtracting.value = false
            }
        }
    }

    private suspend fun runSampleExtraction(sample: SampleDocument, bitmap: Bitmap, hasValidApiKey: Boolean) {
        _isDemoResult.value = true
        if (hasValidApiKey) {
            val result = ocrService.extractDocumentFromBitmap(bitmap)
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

    private suspend fun runRealExtraction(bitmap: Bitmap) {
        val result = ocrService.extractDocumentFromBitmap(bitmap)
        result.fold(
            onSuccess = { (doc, raw) ->
                _extractedDocument.value = doc
                _rawJsonOutput.value = raw
                try {
                    // Keep the original image as evidence, then save the AI result as PENDING.
                    val imagePath = repository.saveImage(bitmap)
                    val id = repository.savePendingDocument(doc, raw, imagePath)
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

    companion object {
        private const val TAG = "AccountantViewModel"
    }
}
