package com.example.data.ezee

import java.util.Locale

/**
 * A number the user picked in the builder: "on the line that starts with [anchor], take number no. [index]".
 * [heading] / [occurrence] tell lines with the same text apart (e.g. several "Total" lines).
 */
data class TemplateField(
    val key: String,
    val label: String,
    val anchor: String,
    val index: Int,
    val heading: String? = null,
    val occurrence: Int = 0
)

/** A table the user picked: every line under [heading] that has [numberCount] numbers (like the sample line). */
data class TemplateTable(
    val name: String,
    val heading: String?,
    val numberCount: Int,
    val columns: List<String>
)

/**
 * A reader the user created in the app for a new report layout (no programming, no AI at read time).
 * [matchText] = text that appears at the top of every report of this kind (usually the title).
 */
data class ReportTemplate(
    val id: String,
    val name: String,
    val matchText: String,
    val dateAnchor: String? = null,
    val fields: List<TemplateField> = emptyList(),
    val tables: List<TemplateTable> = emptyList(),
    /** Field key whose value is suggested as the amount to count, with [countAs] INCOME / EXPENSE. */
    val countKey: String? = null,
    val countAs: String? = null,
    val createdAt: Long = 0L
) {
    val type: String get() = "custom_" + (EzeeReportParser.slug(name).ifBlank { id.take(8) })
}

/** One line of a report as the builder shows it: text, the numbers in it, and the heading above it. */
data class LineInfo(
    val index: Int,
    val text: String,
    val label: String,
    val numbers: List<Double>,
    val numberTexts: List<String>,
    val heading: String?,
    /** How many earlier lines have the same [label] (0 = first). */
    val occurrence: Int,
    val dates: List<String>
)

/**
 * Runs [ReportTemplate]s. Pure Kotlin (no Android) so it is unit-tested on the JVM.
 * Order in the app: user templates first, then the built-in eZee reader, then AI.
 */
object TemplateEngine {

    const val PARSER_PREFIX = "template:"

    /** dd/mm/yyyy, dd-mm-yyyy, dd.mm.yyyy or yyyy-mm-dd (Buddhist years are converted). */
    private val DATE_ANY = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b|\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b")

    fun parseDate(text: String): String? = DATE_ANY.find(text)?.let(::toIso)

    private fun toIso(m: MatchResult): String? {
        val g = m.groupValues
        val (y, mo, d) = if (g[1].isNotEmpty()) Triple(g[3].toInt(), g[2].toInt(), g[1].toInt())
        else Triple(g[4].toInt(), g[5].toInt(), g[6].toInt())
        if (mo !in 1..12 || d !in 1..31) return null
        val year = if (y > 2400) y - 543 else y
        return String.format(Locale.US, "%04d-%02d-%02d", year, mo, d)
    }

    /** Splits the report into lines with their numbers, as shown in the builder. */
    fun analyze(pages: List<List<String>>): List<LineInfo> {
        val lines = EzeeReportParser.clean(pages)
        val seen = HashMap<String, Int>()
        var heading: String? = null
        return lines.mapIndexed { i, text ->
            // Prefer table columns ("Room 101   1   1,200.00" -> label "Room 101"), else single words.
            val cols = text.trim().split(EzeeReportParser.SPLIT)
            val colCount = trailingNumberCount(cols)
            val (label, numberTexts) = if (colCount > 0) {
                cols.dropLast(colCount).joinToString(" ") to cols.takeLast(colCount)
            } else {
                val tokens = text.trim().split(Regex("\\s+"))
                val n = trailingNumberCount(tokens)
                tokens.dropLast(n).joinToString(" ") to tokens.takeLast(n)
            }
            val numbers = numberTexts.mapNotNull { EzeeReportParser.num(it) }
            val dates = DATE_ANY.findAll(text).mapNotNull(::toIso).toList()
            val occ = seen.getOrDefault(label, 0)
            seen[label] = occ + 1
            val info = LineInfo(i, text, label, numbers, numberTexts, heading, occ, dates)
            // A line with no numbers and no date is a heading for the lines below it.
            if (numbers.isEmpty() && dates.isEmpty() && label.isNotBlank()) heading = label
            info
        }
    }

    private fun trailingNumberCount(tokens: List<String>): Int {
        var n = 0
        while (n < tokens.size && EzeeReportParser.num(tokens[tokens.size - 1 - n]) != null) n++
        return n
    }

    fun matches(t: ReportTemplate, pages: List<List<String>>, fileName: String?): Boolean {
        val needle = t.matchText.trim()
        if (needle.isEmpty()) return false
        val head = pages.firstOrNull().orEmpty().take(8).joinToString("\n")
        return head.contains(needle, ignoreCase = true) || (fileName?.contains(needle, ignoreCase = true) == true)
    }

    /** Newest matching template wins (so a corrected copy replaces an old one). */
    fun find(templates: List<ReportTemplate>, pages: List<List<String>>, fileName: String?): ReportTemplate? =
        templates.sortedByDescending { it.createdAt }.firstOrNull { matches(it, pages, fileName) }

    /** Finds the line for [f]: same label under the same heading, else the same occurrence of the label. */
    fun locate(f: TemplateField, lines: List<LineInfo>): LineInfo? {
        val same = lines.filter { it.label.equals(f.anchor, ignoreCase = true) && it.numbers.size > f.index }
        if (same.isEmpty()) return null
        if (same.size == 1) return same[0]
        val underHeading = f.heading?.let { h -> same.filter { it.heading.equals(h, ignoreCase = true) } }.orEmpty()
        val candidates = underHeading.ifEmpty { same }
        return candidates.firstOrNull { it.occurrence == f.occurrence } ?: candidates.first()
    }

    fun apply(t: ReportTemplate, pages: List<List<String>>): EzeeReport {
        val lines = analyze(pages)
        val summary = LinkedHashMap<String, Double>()
        val labels = LinkedHashMap<String, String>()
        val missing = mutableListOf<String>()
        for (f in t.fields) {
            val line = locate(f, lines)
            val v = line?.numbers?.getOrNull(f.index)
            if (v == null) {
                missing.add(f.label)
            } else {
                summary[f.key] = EzeeReportParser.round2(v)
                labels[f.key] = f.label
            }
        }

        val rows = mutableListOf<Map<String, Any?>>()
        for (table in t.tables) rows.addAll(tableRows(table, lines))

        val date = t.dateAnchor?.let { anchor ->
            lines.firstOrNull { it.text.contains(anchor, ignoreCase = true) }?.let { l ->
                parseDate(l.text.substring(l.text.indexOf(anchor, ignoreCase = true)))
            }
        } ?: lines.firstNotNullOfOrNull { it.dates.firstOrNull() }

        val notes = mutableListOf<String>()
        if (missing.isNotEmpty()) notes.add("หาไม่เจอในไฟล์นี้: ${missing.joinToString(", ")} — รูปแบบอาจเปลี่ยน ลองสร้างตัวอ่านใหม่")
        val amount = t.countKey?.let { summary[it] }
        val tx = t.countAs?.takeIf { it == "INCOME" || it == "EXPENSE" }
        return EzeeReport(
            type = t.type,
            title = t.name,
            propertyName = null,
            reportDate = date,
            periodFrom = date,
            periodTo = date,
            currency = null,
            summary = summary,
            labels = labels,
            rows = rows,
            notes = notes,
            checks = t.fields.map { f -> EzeeCheck("พบ \"${f.label}\"", f.label !in missing) },
            suggestedTransaction = if (amount != null) tx else null,
            suggestedAmount = if (tx != null) amount else null,
            parser = PARSER_PREFIX + t.id
        )
    }

    private fun tableRows(table: TemplateTable, lines: List<LineInfo>): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        for (l in lines) {
            if (table.heading != null && !l.heading.equals(table.heading, ignoreCase = true)) continue
            if (l.numbers.size != table.numberCount || l.label.isBlank()) continue
            if (l.label.startsWith("total", ignoreCase = true) || l.label.startsWith("รวม")) continue
            val row = linkedMapOf<String, Any?>("table" to table.name, "item" to l.label)
            l.numbers.forEachIndexed { i, n -> row[table.columns.getOrNull(i)?.ifBlank { null } ?: "col_${i + 1}"] = n }
            out.add(row)
        }
        return out
    }

    /** Suggests a key for a new field: English label -> snake_case, Thai -> field_N. */
    fun newKey(label: String, existing: Collection<String>): String {
        val base = EzeeReportParser.slug(label).ifBlank { "field" }
        if (base !in existing && base != "field") return base
        var n = 1
        while ("${base}_$n" in existing) n++
        return "${base}_$n"
    }

    /** Suggests the text that identifies this kind of report: the first line that is not a number/date line. */
    fun suggestMatchText(pages: List<List<String>>): String {
        val first = pages.firstOrNull().orEmpty().take(4).map { it.trim() }.filter { it.isNotEmpty() }
        // The title is usually the last column of the first line ("Hotel name   Report title"),
        // or the next line when the first line is only the hotel name.
        val cols = first.firstOrNull()?.split(EzeeReportParser.SPLIT).orEmpty()
        val raw = if (cols.size >= 2) cols.last() else first.getOrNull(1)?.split(EzeeReportParser.SPLIT)?.first() ?: cols.firstOrNull()
        // Dates / month numbers change every file, so they are not part of the match text.
        return raw.orEmpty().replace(Regex("[\\d/.,:-]+\\s*$"), "").trim().take(60)
    }

    /** Suggests the date anchor: text before the first date in the report (e.g. "As on Date"). */
    fun suggestDateAnchor(lines: List<LineInfo>): String? {
        val l = lines.firstOrNull { it.dates.isNotEmpty() } ?: return null
        val m = DATE_ANY.find(l.text) ?: return null
        return l.text.substring(0, m.range.first).split(EzeeReportParser.SPLIT).lastOrNull { it.isNotBlank() }?.trim()
            ?.takeIf { it.length in 2..40 }
    }
}
