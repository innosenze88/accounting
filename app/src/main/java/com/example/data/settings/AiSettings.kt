package com.example.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which AI service reads the documents. */
enum class AiProvider(val code: String, val title: String) {
    GEMINI("GEMINI", "Google Gemini"),
    CLAUDE("CLAUDE", "Anthropic Claude");

    companion object {
        fun fromCode(code: String?): AiProvider = entries.find { it.code == code } ?: GEMINI
    }
}

data class AiSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val claudeApiKey: String = "",
    val claudeModel: String = DEFAULT_CLAUDE_MODEL,
    /** Console workspace ID (wrkspc_...). Needed only for keys not scoped to one workspace. */
    val claudeWorkspaceId: String = "",
    /** Google Apps Script Web App URL (ends with /exec). Empty = Google Sheets off. */
    val sheetsWebAppUrl: String = "",
    /** Shared secret that must match TOKEN in the Apps Script code. */
    val sheetsToken: String = "",
    /** Send a document to Google Sheets automatically when it is verified. */
    val sheetsAutoSync: Boolean = true
) {
    val sheetsEnabled: Boolean
        get() = sheetsWebAppUrl.startsWith("https://")

    val activeApiKey: String
        get() = when (provider) {
            AiProvider.GEMINI -> geminiApiKey
            AiProvider.CLAUDE -> claudeApiKey
        }

    val activeModel: String
        get() = when (provider) {
            AiProvider.GEMINI -> geminiModel.ifBlank { DEFAULT_GEMINI_MODEL }
            AiProvider.CLAUDE -> claudeModel.ifBlank { DEFAULT_CLAUDE_MODEL }
        }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5"

        /** Suggested models shown as quick-pick chips in Settings. */
        val GEMINI_MODEL_SUGGESTIONS = listOf("gemini-2.5-flash", "gemini-2.5-pro")
        val CLAUDE_MODEL_SUGGESTIONS = listOf("claude-sonnet-5", "claude-haiku-4-5-20251001", "claude-opus-5-5")
    }
}

/**
 * Stores AI provider settings in app-private SharedPreferences ("ai_settings").
 * The file is excluded from cloud backup and device transfer (see res/xml backup rules).
 */
class AiSettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    private fun load(): AiSettings = AiSettings(
        provider = AiProvider.fromCode(prefs.getString(KEY_PROVIDER, null)),
        geminiApiKey = prefs.getString(KEY_GEMINI_KEY, "") ?: "",
        geminiModel = prefs.getString(KEY_GEMINI_MODEL, AiSettings.DEFAULT_GEMINI_MODEL) ?: AiSettings.DEFAULT_GEMINI_MODEL,
        claudeApiKey = prefs.getString(KEY_CLAUDE_KEY, "") ?: "",
        claudeModel = prefs.getString(KEY_CLAUDE_MODEL, AiSettings.DEFAULT_CLAUDE_MODEL) ?: AiSettings.DEFAULT_CLAUDE_MODEL,
        claudeWorkspaceId = prefs.getString(KEY_CLAUDE_WORKSPACE, "") ?: "",
        sheetsWebAppUrl = prefs.getString(KEY_SHEETS_URL, "") ?: "",
        sheetsToken = prefs.getString(KEY_SHEETS_TOKEN, null) ?: newToken().also {
            prefs.edit().putString(KEY_SHEETS_TOKEN, it).apply()
        },
        sheetsAutoSync = prefs.getBoolean(KEY_SHEETS_AUTO, true)
    )

    fun save(settings: AiSettings) {
        val clean = settings.copy(
            geminiApiKey = settings.geminiApiKey.trim(),
            geminiModel = settings.geminiModel.trim(),
            claudeApiKey = settings.claudeApiKey.filterNot { it.isWhitespace() },
            claudeModel = settings.claudeModel.trim(),
            claudeWorkspaceId = settings.claudeWorkspaceId.trim(),
            sheetsWebAppUrl = settings.sheetsWebAppUrl.trim(),
            sheetsToken = settings.sheetsToken.trim()
        )
        prefs.edit()
            .putString(KEY_PROVIDER, clean.provider.code)
            .putString(KEY_GEMINI_KEY, clean.geminiApiKey)
            .putString(KEY_GEMINI_MODEL, clean.geminiModel)
            .putString(KEY_CLAUDE_KEY, clean.claudeApiKey)
            .putString(KEY_CLAUDE_MODEL, clean.claudeModel)
            .putString(KEY_CLAUDE_WORKSPACE, clean.claudeWorkspaceId)
            .putString(KEY_SHEETS_URL, clean.sheetsWebAppUrl)
            .putString(KEY_SHEETS_TOKEN, clean.sheetsToken)
            .putBoolean(KEY_SHEETS_AUTO, clean.sheetsAutoSync)
            .apply()
        _settings.value = clean
    }

    companion object {
        private const val PREFS_NAME = "ai_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_CLAUDE_KEY = "claude_api_key"
        private const val KEY_CLAUDE_MODEL = "claude_model"
        private const val KEY_CLAUDE_WORKSPACE = "claude_workspace_id"
        private const val KEY_SHEETS_URL = "sheets_web_app_url"
        private const val KEY_SHEETS_TOKEN = "sheets_token"
        private const val KEY_SHEETS_AUTO = "sheets_auto_sync"

        /** Random 24-character secret for the Apps Script. */
        fun newToken(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val random = java.security.SecureRandom()
            return (1..24).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }
    }
}
