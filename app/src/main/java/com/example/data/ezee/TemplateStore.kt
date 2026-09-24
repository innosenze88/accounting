package com.example.data.ezee

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the readers the user created ("ตัวอ่านที่สร้างเอง") in a JSON file inside the app.
 * The same JSON is used for export / import (copy & paste), e.g. as a backup or to move to another phone.
 */
class TemplateStore(context: Context) {

    private val file = File(context.filesDir, "report_templates.json")
    private val _templates = MutableStateFlow(load())
    val templates: StateFlow<List<ReportTemplate>> = _templates.asStateFlow()

    private fun load(): List<ReportTemplate> = try {
        if (file.exists()) fromJson(file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Could not read templates", e)
        emptyList()
    }

    private suspend fun persist(list: List<ReportTemplate>) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(list))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _templates.value = list
    }

    /** Adds [t], or replaces the template with the same id. */
    suspend fun save(t: ReportTemplate) {
        persist(_templates.value.filter { it.id != t.id } + t)
    }

    suspend fun delete(id: String) {
        persist(_templates.value.filter { it.id != id })
    }

    /** Imports templates from pasted JSON. Returns how many were added/replaced. */
    suspend fun importJson(text: String): Int {
        val incoming = fromJson(text)
        if (incoming.isEmpty()) return 0
        val ids = incoming.map { it.id }.toSet()
        persist(_templates.value.filter { it.id !in ids } + incoming)
        return incoming.size
    }

    fun exportJson(): String = toJson(_templates.value)

    companion object {
        private const val TAG = "TemplateStore"

        fun toJson(list: List<ReportTemplate>): String = JSONObject().apply {
            put("format", "resort-report-templates")
            put("version", 1)
            put("templates", JSONArray().apply { list.forEach { put(templateToJson(it)) } })
        }.toString(2)

        fun templateToJson(t: ReportTemplate): JSONObject = JSONObject().apply {
            put("id", t.id)
            put("name", t.name)
            put("match_text", t.matchText)
            put("date_anchor", t.dateAnchor ?: JSONObject.NULL)
            put("count_key", t.countKey ?: JSONObject.NULL)
            put("count_as", t.countAs ?: JSONObject.NULL)
            put("created_at", t.createdAt)
            put("fields", JSONArray().apply {
                t.fields.forEach { f ->
                    put(JSONObject().apply {
                        put("key", f.key)
                        put("label", f.label)
                        put("anchor", f.anchor)
                        put("index", f.index)
                        put("heading", f.heading ?: JSONObject.NULL)
                        put("occurrence", f.occurrence)
                    })
                }
            })
            put("tables", JSONArray().apply {
                t.tables.forEach { tb ->
                    put(JSONObject().apply {
                        put("name", tb.name)
                        put("heading", tb.heading ?: JSONObject.NULL)
                        put("number_count", tb.numberCount)
                        put("columns", JSONArray(tb.columns))
                    })
                }
            })
        }

        /** Accepts the export format, a plain array, or a single template object. */
        fun fromJson(text: String): List<ReportTemplate> {
            val trimmed = text.trim()
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> JSONObject(trimmed).let { o -> o.optJSONArray("templates") ?: JSONArray().put(o) }
            }
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::templateFromJson) }
        }

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private fun templateFromJson(o: JSONObject): ReportTemplate? {
            val name = o.str("name") ?: return null
            val match = o.str("match_text") ?: return null
            val fields = o.optJSONArray("fields") ?: JSONArray()
            val tables = o.optJSONArray("tables") ?: JSONArray()
            return ReportTemplate(
                id = o.str("id") ?: java.util.UUID.randomUUID().toString(),
                name = name,
                matchText = match,
                dateAnchor = o.str("date_anchor"),
                countKey = o.str("count_key"),
                countAs = o.str("count_as"),
                createdAt = o.optLong("created_at", System.currentTimeMillis()),
                fields = (0 until fields.length()).mapNotNull { i ->
                    val f = fields.optJSONObject(i) ?: return@mapNotNull null
                    TemplateField(
                        key = f.str("key") ?: return@mapNotNull null,
                        label = f.str("label") ?: f.str("key")!!,
                        anchor = if (f.isNull("anchor")) "" else f.optString("anchor"),
                        index = f.optInt("index", 0),
                        heading = f.str("heading"),
                        occurrence = f.optInt("occurrence", 0)
                    )
                },
                tables = (0 until tables.length()).mapNotNull { i ->
                    val tb = tables.optJSONObject(i) ?: return@mapNotNull null
                    val cols = tb.optJSONArray("columns") ?: JSONArray()
                    TemplateTable(
                        name = tb.str("name") ?: "table_${i + 1}",
                        heading = tb.str("heading"),
                        numberCount = tb.optInt("number_count", 1),
                        columns = (0 until cols.length()).map { cols.optString(it) }
                    )
                }
            )
        }
    }
}
