package com.example.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Type of file the user picked or shared into the app. */
enum class FileKind { IMAGE, PDF, CSV, XLSX, HTML_TABLE, UNSUPPORTED }

/** A file read from a content Uri (upload button or Share from Gmail). */
class PickedFile(
    val fileName: String,
    val mimeType: String?,
    val kind: FileKind,
    val bytes: ByteArray
) {
    val extension: String
        get() = fileName.substringAfterLast('.', "").lowercase().ifEmpty {
            when (kind) {
                FileKind.PDF -> "pdf"
                FileKind.CSV -> "csv"
                FileKind.XLSX -> "xlsx"
                FileKind.HTML_TABLE -> "xls"
                FileKind.IMAGE -> "jpg"
                FileKind.UNSUPPORTED -> "bin"
            }
        }
}

/** A simple table: first row = headers. */
data class ParsedTable(val headers: List<String>, val rows: List<List<String>>)

object FileImporter {

    /** Files larger than this are refused (AI request limits are around 20 MB). */
    const val MAX_BYTES = 20 * 1024 * 1024

    suspend fun read(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if ((size ?: 0L) > MAX_BYTES) throw IllegalArgumentException("ไฟล์ใหญ่เกิน 20 MB")
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IllegalArgumentException("ไฟล์ใหญ่เกิน 20 MB")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("เปิดไฟล์ไม่ได้")
        val mime = resolver.getType(uri)
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        PickedFile(fileName, mime, detectKind(fileName, mime, bytes), bytes)
    }

    fun detectKind(fileName: String, mime: String?, bytes: ByteArray): FileKind {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val head = bytes.take(8).toByteArray()
        val m = mime?.lowercase().orEmpty()
        return when {
            head.startsWith("%PDF") || m == "application/pdf" || ext == "pdf" -> FileKind.PDF
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic") -> FileKind.IMAGE
            // xlsx is a zip file ("PK")
            ext == "xlsx" || (head.startsWith("PK") && m.contains("spreadsheetml")) -> FileKind.XLSX
            ext == "csv" || ext == "txt" || m.contains("csv") || m == "text/plain" -> FileKind.CSV
            // Many PMS "Excel" exports are really HTML tables saved as .xls
            ext == "xls" || ext == "htm" || ext == "html" || m.contains("ms-excel") || m.contains("html") ->
                if (looksLikeHtml(bytes)) FileKind.HTML_TABLE else FileKind.UNSUPPORTED
            else -> FileKind.UNSUPPORTED
        }
    }

    private fun ByteArray.startsWith(prefix: String): Boolean {
        val p = prefix.toByteArray(Charsets.US_ASCII)
        if (size < p.size) return false
        return p.indices.all { this[it] == p[it] }
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val start = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).trimStart().lowercase()
        return start.startsWith("<") && (start.contains("<table") || start.contains("<html") || start.contains("<!doctype"))
    }

    fun decodeImage(bytes: ByteArray, maxDimension: Int = 2400): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Renders page 1 of a PDF (white background) for preview and as evidence image. */
    suspend fun renderPdfFirstPage(context: Context, bytes: ByteArray, targetWidth: Int = 1600): Pair<Bitmap?, Int> =
        withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("import", ".pdf", context.cacheDir)
            try {
                tmp.writeBytes(bytes)
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val pageCount = renderer.pageCount
                        if (pageCount == 0) return@withContext null to 0
                        renderer.openPage(0).use { page ->
                            val scale = targetWidth.toFloat() / page.width
                            val bmp = Bitmap.createBitmap(
                                targetWidth,
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp to pageCount
                        }
                    }
                }
            } catch (e: Exception) {
                // Password-protected or damaged PDF: the AI may still read it, just no preview.
                null to 0
            } finally {
                tmp.delete()
            }
        }

    // ------------------------------------------------------------------ tables

    fun parseTable(file: PickedFile): ParsedTable = when (file.kind) {
        FileKind.CSV -> parseCsv(decodeText(file.bytes))
        FileKind.XLSX -> parseXlsx(file.bytes)
        FileKind.HTML_TABLE -> parseHtmlTable(decodeText(file.bytes))
        else -> throw IllegalArgumentException("ไฟล์นี้ไม่ใช่ตาราง")
    }

    /** UTF-8 (with or without BOM) first; falls back to Thai Windows-874 for older exports. */
    fun decodeText(bytes: ByteArray): String {
        val data = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (e: CharacterCodingException) {
            String(data, Charset.forName("windows-874"))
        }
    }

    /** RFC 4180 CSV (quotes, commas and new lines inside quotes). Also accepts ; or tab separated files. */
    fun parseCsv(text: String): ParsedTable {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t').maxByOrNull { c -> firstLine.count { it == c } } ?: ','
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    sep -> { record.add(field.toString()); field = StringBuilder() }
                    '\r' -> {}
                    '\n' -> {
                        record.add(field.toString()); field = StringBuilder()
                        records.add(record); record = mutableListOf()
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record.add(field.toString())
            records.add(record)
        }
        return toTable(records)
    }

    /** Reads the first worksheet of an .xlsx file (shared strings, inline strings and numbers). */
    fun parseXlsx(bytes: ByteArray): ParsedTable {
        var sharedStringsXml: ByteArray? = null
        val sheets = sortedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "xl/sharedStrings.xml" -> sharedStringsXml = zip.readBytes()
                    entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml") ->
                        sheets[entry.name] = zip.readBytes()
                }
            }
        }
        val sheetXml = sheets["xl/worksheets/sheet1.xml"] ?: sheets.values.firstOrNull()
            ?: throw IllegalArgumentException("ไม่พบแผ่นงานในไฟล์ Excel")
        val shared = sharedStringsXml?.let { readSharedStrings(it) } ?: emptyList()

        val records = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(sheetXml), "UTF-8")
        var row: MutableMap<Int, String>? = null
        var cellCol = 0
        var cellType: String? = null
        var value = StringBuilder()
        var inValue = false
        var nextCol = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> { row = sortedMapOf(); nextCol = 0 }
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r")
                        cellCol = ref?.let { columnIndex(it) } ?: nextCol
                        cellType = parser.getAttributeValue(null, "t")
                        value = StringBuilder()
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (cellType) {
                            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: raw
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            else -> raw
                        }
                        row?.put(cellCol, text)
                        nextCol = cellCol + 1
                    }
                    "row" -> {
                        val r = row
                        if (r != null) {
                            val width = (r.keys.maxOrNull() ?: -1) + 1
                            records.add((0 until width).map { r[it].orEmpty() })
                        }
                        row = null
                    }
                }
            }
            parser.next()
        }
        return toTable(records)
    }

    private fun readSharedStrings(xml: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        var current: StringBuilder? = null
        var inT = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT) current?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inT = false
                    "si" -> { result.add(current?.toString().orEmpty()); current = null }
                }
            }
            parser.next()
        }
        return result
    }

    /** "C12" -> 2 */
    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }

    /** Old "Excel" exports that are really an HTML <table>. */
    fun parseHtmlTable(html: String): ParsedTable {
        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val records = rowRegex.findAll(html).map { tr ->
            cellRegex.findAll(tr.groupValues[1]).map { td -> htmlToText(td.groupValues[1]) }.toList()
        }.toList()
        return toTable(records)
    }

    private fun htmlToText(s: String): String = s
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

    /** Drops empty rows, uses the first non-empty row as headers and makes header names unique. */
    private fun toTable(records: List<List<String>>): ParsedTable {
        val nonEmpty = records.map { r -> r.map { it.trim() } }.filter { r -> r.any { it.isNotEmpty() } }
        if (nonEmpty.isEmpty()) return ParsedTable(emptyList(), emptyList())
        val width = nonEmpty.maxOf { it.size }
        val seen = mutableMapOf<String, Int>()
        val headers = (0 until width).map { i ->
            val base = nonEmpty[0].getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "column_${i + 1}"
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            if (count == 1) base else "${base}_$count"
        }
        val rows = nonEmpty.drop(1).map { r -> (0 until width).map { r.getOrNull(it).orEmpty() } }
        return ParsedTable(headers, rows)
    }
}
