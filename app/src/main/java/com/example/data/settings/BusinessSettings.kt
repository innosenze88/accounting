package com.example.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Resort details printed on documents, PromptPay, rooms and booking rules. */
data class BusinessSettings(
    val name: String = "",
    val branch: String = "สำนักงานใหญ่",
    val address: String = "",
    val taxId: String = "",
    val phone: String = "",
    /** PromptPay phone number (10 digits) or tax ID (13 digits) used for the QR code. */
    val promptPayId: String = "",
    /** Registered for VAT: only then can the app issue tax invoices. */
    val vatRegistered: Boolean = false,
    val vatRate: Double = 7.0,
    /** Room prices already include VAT (usual for hotels). */
    val pricesIncludeVat: Boolean = true,
    /** % of the deposit given back when a direct booking is cancelled. */
    val refundPercent: Double = 50.0,
    /** Room numbers/names, comma separated. */
    val rooms: String = (1..16).joinToString(","),
    /** Text at the bottom of receipts. */
    val footer: String = "ขอบคุณที่ใช้บริการ",
    /** Wallet ids that count as profit (savings, owner) in the yearly plan; null = default rule. */
    val profitWallets: String? = null
) {
    val profitWalletIds: Set<Long>? get() = profitWallets?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
    val roomList: List<String> get() = rooms.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val canIssueTaxInvoice: Boolean get() = vatRegistered && taxId.filter { it.isDigit() }.length == 13 && name.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("branch", branch); put("address", address); put("taxId", taxId); put("phone", phone)
        put("promptPayId", promptPayId); put("vatRegistered", vatRegistered); put("vatRate", vatRate)
        put("pricesIncludeVat", pricesIncludeVat); put("refundPercent", refundPercent); put("rooms", rooms); put("footer", footer)
        if (profitWallets != null) put("profitWallets", profitWallets)
    }

    companion object {
        fun fromJson(o: JSONObject): BusinessSettings {
            val d = BusinessSettings()
            return BusinessSettings(
                name = o.optString("name", d.name), branch = o.optString("branch", d.branch),
                address = o.optString("address", d.address), taxId = o.optString("taxId", d.taxId),
                phone = o.optString("phone", d.phone), promptPayId = o.optString("promptPayId", d.promptPayId),
                vatRegistered = o.optBoolean("vatRegistered", d.vatRegistered), vatRate = o.optDouble("vatRate", d.vatRate),
                pricesIncludeVat = o.optBoolean("pricesIncludeVat", d.pricesIncludeVat),
                refundPercent = o.optDouble("refundPercent", d.refundPercent), rooms = o.optString("rooms", d.rooms),
                footer = o.optString("footer", d.footer),
                profitWallets = if (o.has("profitWallets")) o.optString("profitWallets") else null
            )
        }
    }
}

/** Stores [BusinessSettings] as one JSON string (easy to back up and restore). */
class BusinessSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<BusinessSettings> = _settings.asStateFlow()

    private fun load(): BusinessSettings = prefs.getString(KEY, null)
        ?.let { runCatching { BusinessSettings.fromJson(JSONObject(it)) }.getOrNull() } ?: BusinessSettings()

    fun save(s: BusinessSettings) {
        prefs.edit().putString(KEY, s.toJson().toString()).apply()
        _settings.value = s
    }

    /** Raw JSON for backups. */
    fun exportJson(): String = settings.value.toJson().toString()

    companion object {
        const val PREFS_NAME = "business_settings"
        private const val KEY = "json"

        /** Writes settings straight into the prefs file (used by restore, before the app restarts). */
        fun importJson(context: Context, json: String) {
            BusinessSettings.fromJson(JSONObject(json)) // validate
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY, json).commit()
        }
    }
}
