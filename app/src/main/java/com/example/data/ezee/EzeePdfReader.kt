package com.example.data.ezee

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads eZee PDF reports on the phone, without AI and without internet:
 * PDF -> glyph positions (PdfBox) -> text lines ([PdfLineBuilder]) -> numbers ([EzeeReportParser]).
 */
object EzeePdfReader {

    private const val TAG = "EzeePdfReader"
    private const val MAX_PAGES = 30

    /**
     * Reads a PDF with the user's own readers ([templates], newest first) or the built-in eZee reader.
     * Returns null when no reader fits (or the PDF has no text layer, e.g. a scan) -> the app uses AI.
     */
    suspend fun read(
        context: Context,
        bytes: ByteArray,
        fileName: String?,
        templates: List<ReportTemplate> = emptyList()
    ): EzeeReport? =
        withContext(Dispatchers.Default) {
            try {
                val pages = pageLines(context, bytes)
                TemplateEngine.find(templates, pages, fileName)?.let { return@withContext TemplateEngine.apply(it, pages) }
                EzeeReportParser.parse(pages, fileName)
            } catch (e: Exception) {
                Log.w(TAG, "Local eZee read failed, AI will be used instead", e)
                null
            }
        }

    /** Text lines of every page (table columns separated by 3 spaces). */
    fun pageLines(context: Context, bytes: ByteArray): List<List<String>> {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(bytes).use { doc ->
            val collector = GlyphCollector()
            return (1..minOf(doc.numberOfPages, MAX_PAGES)).map { page ->
                collector.glyphs.clear()
                collector.setStartPage(page)
                collector.setEndPage(page)
                collector.getText(doc)
                PdfLineBuilder.build(collector.glyphs.toList())
            }
        }
    }

    /** Collects every character with its position instead of PdfBox's own line layout. */
    private class GlyphCollector : PDFTextStripper() {
        val glyphs = mutableListOf<PdfGlyph>()

        init {
            setSortByPosition(false)
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            textPositions?.forEach { tp ->
                val size = tp.fontSizeInPt.takeIf { it > 0f } ?: tp.heightDir
                glyphs.add(PdfGlyph(tp.xDirAdj, tp.yDirAdj, tp.widthDirAdj, size, tp.unicode ?: ""))
            }
        }
    }

    /** Same JSON shape as the AI report reader, plus local-only fields (parser, labels, checks, suggestion). */
    fun toJson(r: EzeeReport): JSONObject = JSONObject().apply {
        put("parser", r.parser)
        put("report_type", r.type)
        put("report_title", r.title)
        put("property_name", r.propertyName ?: JSONObject.NULL)
        put("report_date", r.reportDate ?: JSONObject.NULL)
        put("period_from", r.periodFrom ?: JSONObject.NULL)
        put("period_to", r.periodTo ?: JSONObject.NULL)
        put("currency", r.currency ?: JSONObject.NULL)
        put("summary", JSONObject().apply { r.summary.forEach { (k, v) -> put(k, v) } })
        put("labels", JSONObject().apply { r.labels.forEach { (k, v) -> put(k, v) } })
        put("rows", JSONArray().apply {
            r.rows.forEach { row ->
                put(JSONObject().apply { row.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
            }
        })
        put("checks", JSONArray().apply {
            r.checks.forEach { c -> put(JSONObject().put("label", c.label).put("ok", c.ok)) }
        })
        put("notes", if (r.notes.isEmpty()) JSONObject.NULL else r.notes.joinToString("\n"))
        put("suggested_transaction", r.suggestedTransaction ?: JSONObject.NULL)
        put("suggested_amount", r.suggestedAmount ?: JSONObject.NULL)
        put("count_by_default", r.countByDefault)
    }
}
