package com.example.data.ezee

import java.util.Locale
import kotlin.math.abs

/** One automatic cross-check, e.g. "sum of rows = printed total". */
data class EzeeCheck(val label: String, val ok: Boolean)

/**
 * An eZee Absolute report read WITHOUT AI: numbers come straight from the PDF text.
 * [summary] keys are snake_case (stable for Google Sheets), [labels] holds the Thai name for each key.
 */
data class EzeeReport(
    val type: String,
    val title: String,
    val propertyName: String?,
    val reportDate: String?,
    val periodFrom: String?,
    val periodTo: String?,
    val currency: String?,
    val summary: LinkedHashMap<String, Double>,
    val labels: Map<String, String>,
    val rows: List<Map<String, Any?>>,
    val notes: List<String>,
    val checks: List<EzeeCheck>,
    /** "INCOME" / "EXPENSE", or null when the report should not be added to the dashboard totals. */
    val suggestedTransaction: String?,
    val suggestedAmount: Double?,
    /** Which reader produced this: the built-in eZee reader or "template:<id>" for a user-made reader. */
    val parser: String = EzeeReportParser.PARSER_ID
) {
    val countByDefault: Boolean get() = suggestedTransaction != null && (suggestedAmount ?: 0.0) > 0.0
}

/**
 * Reads the eZee Absolute PDF reports that the resort receives by e-mail every night.
 * Input = text lines of each page (from [PdfLineBuilder]); table columns are separated by 2+ spaces.
 *
 * Supported: Manager Report, Night Audit, Expense Voucher, City Ledger Summary, No Show Report,
 * Monthly Statistics, Yearly Statistics, Front Desk Activities, Monthly Occupancy (title only).
 * Returns null when the file is not a known eZee report (the app then falls back to AI).
 */
object EzeeReportParser {

    const val PARSER_ID = "ezee_local_v1"

    private enum class Kind(val id: String, val titleKey: String, val fileKey: String) {
        MANAGER("manager_report", "manager report", "manager_report"),
        NIGHT_AUDIT("night_audit", "night audit", "nightaudit"),
        EXPENSE_VOUCHER("expense_voucher", "expense voucher", "expensevoucher"),
        CITY_LEDGER("city_ledger_summary", "city ledger", "cityledger"),
        NO_SHOW("no_show_report", "no show report", "noshowreport"),
        MONTHLY_STATS("monthly_statistics", "monthly statistics", "monthlystatistics"),
        YEARLY_STATS("yearly_statistics", "yearly statistics", "yearlystatistics"),
        FRONT_DESK("front_desk_activities", "front desk activities", "frontdeskactivit"),
        OCCUPANCY("occupancy_monthly", "monthly occupancy", "occupancymonthly")
    }

    internal val SPLIT = Regex("\\s{2,}")
    private val NUMBER = Regex("^-?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?$")
    internal val DATE = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})")
    private val PAGE_FOOTER = Regex("^Page \\d+ of \\d+$", RegexOption.IGNORE_CASE)
    private val JUNK = setOf("Variable1", "NoShowDate", "Powered by TCPDF (www.tcpdf.org)")

    fun parse(pages: List<List<String>>, fileName: String? = null): EzeeReport? {
        if (pages.isEmpty() || pages.all { it.isEmpty() }) return null
        val head = pages.first().take(6).joinToString("\n").lowercase()
        val file = fileName?.lowercase().orEmpty().replace(Regex("[^a-z_]"), "")
        val kind = Kind.entries.firstOrNull { it.titleKey in head }
            ?: Kind.entries.firstOrNull { file.isNotEmpty() && it.fileKey in file }
            ?: return null

        val lines = clean(pages)
        val property = pages.first().firstOrNull()?.split(SPLIT)?.firstOrNull()?.trim()
            ?.takeIf { !it.lowercase().contains(kind.titleKey) && !it.contains('?') }
        val title = pages.first().take(3).flatMap { it.split(SPLIT) }
            .firstOrNull { it.lowercase().contains(kind.titleKey) }?.trim() ?: kind.titleKey

        return when (kind) {
            Kind.MANAGER -> manager(lines)
            Kind.NIGHT_AUDIT -> nightAudit(lines)
            Kind.EXPENSE_VOUCHER -> expenseVoucher(lines)
            Kind.CITY_LEDGER -> cityLedger(lines)
            Kind.NO_SHOW -> noShow(lines)
            Kind.MONTHLY_STATS -> monthlyStats(lines)
            Kind.YEARLY_STATS -> yearlyStats(lines)
            Kind.FRONT_DESK -> frontDesk(lines)
            Kind.OCCUPANCY -> occupancy(lines)
        }.copy(type = kind.id, title = title, propertyName = property)
    }

    // ------------------------------------------------------------------ helpers

    /** All pages as one list, without repeated page headers/footers and hidden template text. */
    internal fun clean(pages: List<List<String>>): List<String> {
        val out = mutableListOf<String>()
        pages.forEachIndexed { pi, page ->
            page.forEachIndexed { li, raw ->
                // Hidden template fields ("Variable1") are printed next to real values on some pages.
                val line = raw.trim().split(SPLIT).filter { it !in JUNK }.joinToString(PdfLineBuilder.COLUMN_GAP)
                if (line.isEmpty() || PAGE_FOOTER.matches(line)) return@forEachIndexed
                // Repeated header lines on page 2+ (same text as on page 1).
                if (pi > 0 && li < 4 && pages[0].take(4).any { it.trim() == line }) return@forEachIndexed
                out.add(line)
            }
        }
        return out
    }

    internal fun num(s: String): Double? {
        val t = s.trim()
        return if (NUMBER.matches(t)) t.replace(",", "").toDoubleOrNull() else null
    }

    /** "Room Charges   2,699.26   87,066.25   0" -> ("Room Charges", [2699.26, 87066.25, 0.0]) */
    internal fun labelAndNumbers(line: String): Pair<String, List<Double>> {
        val tokens = line.trim().split(Regex("\\s+"))
        var i = tokens.size
        while (i > 0 && num(tokens[i - 1]) != null) i--
        return tokens.take(i).joinToString(" ") to tokens.drop(i).mapNotNull { num(it) }
    }

    internal fun isoDate(d: String?): String? {
        val m = d?.let { DATE.find(it) } ?: return null
        val (dd, mm, yyyy) = m.destructured
        var year = yyyy.toInt()
        if (year > 2400) year -= 543 // Buddhist year
        return ymd(year, mm.toInt(), dd.toInt())
    }

    /** Always ASCII digits, whatever the phone language is. */
    private fun ymd(y: Int, m: Int, d: Int) = String.format(Locale.US, "%04d-%02d-%02d", y, m, d)

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun dateAfter(lines: List<String>, label: String): String? =
        lines.firstOrNull { it.contains(label, ignoreCase = true) }?.let { l ->
            val idx = l.indexOf(label, ignoreCase = true)
            isoDate(l.substring(idx))
        }

    internal fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun near(a: Double, b: Double) = abs(a - b) < 0.02

    internal fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    private fun money(v: Double) = String.format(Locale.US, "%,.2f", v)

    private fun check(label: String, sum: Double, printed: Double?): EzeeCheck? =
        printed?.let { EzeeCheck("$label: ${money(round2(sum))} = ${money(it)}", near(sum, it)) }

    private fun empty(kind: String) = EzeeReport(
        type = kind, title = kind, propertyName = null, reportDate = null, periodFrom = null, periodTo = null,
        currency = "THB", summary = LinkedHashMap(), labels = emptyMap(), rows = emptyList(), notes = emptyList(),
        checks = emptyList(), suggestedTransaction = null, suggestedAmount = null
    )

    /** Keeps summary + Thai labels together. */
    private class Summary {
        val values = LinkedHashMap<String, Double>()
        val labels = LinkedHashMap<String, String>()
        fun put(key: String, labelTh: String, value: Double?) {
            if (value == null) return
            values[key] = round2(value)
            labels[key] = labelTh
        }
    }

    // ------------------------------------------------------------------ Manager Report

    private val MANAGER_SECTIONS = listOf(
        "Room Charges", "Tax", "Paid Outs", "Payment", "City Ledger", "Advance Deposit Ledger",
        "Guest Ledger", "Room Summary", "Statistics", "Revenue Summary (without tax)"
    )

    private fun manager(lines: List<String>): EzeeReport {
        val date = dateAfter(lines, "As on Date")
        val ptd = dateAfter(lines, "PTD")
        val rows = mutableListOf<Map<String, Any?>>()
        // section -> particular -> [today, ptd, ytd]
        val data = LinkedHashMap<String, LinkedHashMap<String, List<Double>>>()
        var section = ""
        for (line in lines) {
            if (line.startsWith("*") || line.startsWith("Particulars") || line.contains("As on Date", true)) continue
            if (line.contains("|")) continue // "Complimentary Room | Complimentary Day Use" pairs
            val (label, nums) = labelAndNumbers(line)
            if (nums.isEmpty()) {
                MANAGER_SECTIONS.firstOrNull { it.equals(line.trim(), ignoreCase = true) }?.let { section = it }
                continue
            }
            // Columns: Today, PTD, YTD (+ an unlabelled flag column on some rows, ignored).
            val values = nums.take(3)
            data.getOrPut(section) { LinkedHashMap() }[label] = values
            rows.add(
                linkedMapOf(
                    "section" to section,
                    "particular" to label,
                    "today" to values.getOrNull(0),
                    "ptd" to values.getOrNull(1),
                    "ytd" to values.getOrNull(2)
                )
            )
            // Lines after a section Total (e.g. "Total Revenue with Tax" under Tax) are not items of that section.
            if (label == "Total" || label == "Total Payment") section = "$section/after"
        }
        // "No of Guest (Adult/Child)   5/0   230/0" has no plain numbers -> keep the raw text.
        lines.firstOrNull { it.startsWith("No of Guest") }?.let { l ->
            val parts = l.split(SPLIT)
            rows.add(
                linkedMapOf(
                    "section" to "Room Summary", "particular" to parts.first(),
                    "today" to parts.getOrNull(1), "ptd" to parts.getOrNull(2), "ytd" to parts.getOrNull(3)
                )
            )
        }

        fun v(sec: String, name: String, col: Int = 0): Double? = data[sec]?.get(name)?.getOrNull(col)
        fun anyV(name: String, col: Int = 0): Double? =
            data.values.firstNotNullOfOrNull { it[name]?.getOrNull(col) }

        val s = Summary()
        s.put("room_charges", "ค่าห้อง (วันนี้)", v("Room Charges", "Room Charges"))
        s.put("cancellation_revenue", "รายได้ค่ายกเลิก (วันนี้)", v("Room Charges", "Cancellation Revenue"))
        s.put("no_show_revenue", "รายได้ No Show (วันนี้)", v("Room Charges", "No Show Revenue"))
        s.put("service_charge", "Service Charge (วันนี้)", v("Tax", "Service Charge"))
        s.put("vat", "VAT (วันนี้)", v("Tax", "VAT"))
        s.put("total_revenue_without_tax", "รายได้รวมก่อนภาษี (วันนี้)", anyV("Total Revenue without Tax"))
        s.put("total_revenue_with_tax", "รายได้รวมรวมภาษี (วันนี้)", anyV("Total Revenue with Tax"))
        s.put("paid_outs_total", "ค่าใช้จ่าย Paid Outs (วันนี้)", v("Paid Outs", "Total"))
        data["Payment"]?.forEach { (name, vals) ->
            if (!name.equals("Total Payment", true)) s.put("payment_${slug(name)}", "รับเงิน: $name (วันนี้)", vals.getOrNull(0))
        }
        s.put("total_payment", "รับเงินรวม (วันนี้)", v("Payment", "Total Payment"))
        s.put("city_ledger_closing", "ยอดค้าง City Ledger (OTA/บริษัท)", v("City Ledger", "Closing Balance"))
        s.put("advance_deposit_closing", "เงินมัดจำคงเหลือ", v("Advance Deposit Ledger", "Closing Balance"))
        s.put("guest_ledger_closing", "ยอดค้างชำระของแขก", v("Guest Ledger", "Closing Balance*"))
        s.put("rooms_sold", "ห้องที่ขายได้ (วันนี้)", v("Room Summary", "Sold Room"))
        s.put("no_show_rooms", "ห้อง No Show (วันนี้)", v("Room Summary", "No Show Rooms"))
        s.put("occupancy_percent", "อัตราเข้าพัก % (วันนี้)", v("Statistics", "Occupancy Rate(%)"))
        s.put("adr", "ราคาห้องเฉลี่ย ADR (วันนี้)", v("Statistics", "Average Daily Rate(ADR)"))
        s.put("revpar", "RevPAR (วันนี้)", v("Statistics", "Revenue Per Available Room"))
        s.put("ptd_total_revenue_with_tax", "รายได้รวมรวมภาษี (ต้นเดือนถึงวันนี้)", anyV("Total Revenue with Tax", 1))
        s.put("ptd_total_payment", "รับเงินรวม (ต้นเดือนถึงวันนี้)", v("Payment", "Total Payment", 1))
        s.put("ptd_paid_outs_total", "ค่าใช้จ่าย Paid Outs (ต้นเดือนถึงวันนี้)", v("Paid Outs", "Total", 1))
        s.put("ytd_total_revenue_with_tax", "รายได้รวมรวมภาษี (ต้นปีถึงวันนี้)", anyV("Total Revenue with Tax", 2))
        s.put("ytd_paid_outs_total", "ค่าใช้จ่าย Paid Outs (ต้นปีถึงวันนี้)", v("Paid Outs", "Total", 2))

        // Cross-checks (for every column: items add up to the printed Total).
        val checks = mutableListOf<EzeeCheck>()
        val colNames = listOf("วันนี้", "ต้นเดือน", "ต้นปี")
        for (sec in listOf("Room Charges", "Tax", "Paid Outs")) {
            val items = data[sec] ?: continue
            val total = items["Total"] ?: continue
            for (c in total.indices) {
                val sum = items.filterKeys { it != "Total" }.values.sumOf { it.getOrNull(c) ?: 0.0 }
                check("$sec รวม (${colNames[c]})", sum, total[c])?.let(checks::add)
            }
        }
        data["Payment"]?.let { items ->
            val total = items["Total Payment"]
            total?.indices?.forEach { c ->
                val sum = items.filterKeys { it != "Total Payment" }.values.sumOf { it.getOrNull(c) ?: 0.0 }
                check("Payment รวม (${colNames[c]})", sum, total[c])?.let(checks::add)
            }
        }
        val roomTotal = v("Room Charges", "Total")
        val taxTotal = v("Tax", "Total")
        if (roomTotal != null && taxTotal != null) {
            check("ค่าห้อง + ภาษี = รายได้รวมภาษี", roomTotal + taxTotal, anyV("Total Revenue with Tax"))?.let(checks::add)
        }

        val notes = mutableListOf(
            "ยอดที่แนะนำให้นับ = \"Total Revenue with Tax\" ของวันนี้ (รายได้ที่เกิดขึ้น ไม่ใช่เงินที่รับจริง)",
            "เงินที่รับจริงวันนี้ดูที่ \"รับเงินรวม\" — ถ้าสแกนสลิป/ใบเสร็จของวันเดียวกันด้วย ให้นับทางเดียว"
        )
        val suggested = s.values["total_revenue_with_tax"]
        return empty("manager_report").copy(
            reportDate = date, periodFrom = ptd, periodTo = date,
            summary = s.values, labels = s.labels, rows = rows, notes = notes, checks = checks,
            suggestedTransaction = "INCOME", suggestedAmount = suggested
        )
    }

    // ------------------------------------------------------------------ Night Audit

    private val DAILY_SALES_COLS = listOf(
        "room_charges", "extra_charges", "room_tax", "extra_tax", "discount", "adjustment", "total_sales"
    )

    private fun nightAudit(lines: List<String>): EzeeReport {
        val date = dateAfter(lines, "As On Date")
        val rows = mutableListOf<Map<String, Any?>>()
        val sales = LinkedHashMap<String, List<Double>>()
        var salesTotal: List<Double>? = null
        var roomChargesTotal: List<Double>? = null
        var roomStatus: List<Double>? = null
        var section = ""
        val s = Summary()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed == "Room Charges" -> { section = "room_charges"; continue }
                trimmed == "Daily Sales" -> { section = "daily_sales"; continue }
                trimmed == "Room Status" -> { section = "room_status"; continue }
                trimmed == "Pax Status" -> { section = "pax_status"; continue }
                trimmed == "Pax Analysis" -> { section = "pax_analysis"; continue }
            }
            val (label, nums) = labelAndNumbers(line)
            when (section) {
                "room_charges" -> {
                    if (label.startsWith("Total (THB)") && nums.size >= 4) {
                        roomChargesTotal = nums
                    } else if (Regex("^\\d+\\s*-\\s*").containsMatchIn(trimmed) && DATE.containsMatchIn(trimmed)) {
                        roomChargeRow(trimmed)?.let(rows::add)
                    }
                }
                "daily_sales" -> {
                    if (nums.size == 7) {
                        if (label.startsWith("Total")) salesTotal = nums
                        else {
                            sales[label] = nums
                            rows.add(linkedMapOf<String, Any?>("table" to "daily_sales", "sales_type" to label).apply {
                                DAILY_SALES_COLS.forEachIndexed { i, c -> put(c, nums[i]) }
                            })
                        }
                    }
                }
                "room_status" -> if (nums.size >= 7) roomStatus = nums.takeLast(7)
                "pax_status" -> if (nums.size == 3) {
                    s.put("${slug(label)}_rooms", "ห้อง $label", nums[0])
                    s.put("${slug(label)}_adult", "ผู้ใหญ่ $label", nums[1])
                }
            }
        }

        salesTotal?.let { t ->
            s.put("total_sales", "ยอดขายรวม (รวมภาษี)", t[6])
            s.put("room_charges", "ค่าห้องรวม", t[0])
            s.put("extra_charges", "ค่าบริการเสริม", t[1])
            s.put("room_tax", "ภาษีค่าห้อง", t[2])
            s.put("discount", "ส่วนลด", t[4])
        }
        s.put("room_sales", "ยอดขายห้องพัก", sales["Room Sales"]?.get(6))
        s.put("no_show_sales", "ยอดขาย No Show", sales["No Show Sales"]?.get(6))
        s.put("cancellation_sales", "ยอดขายค่ายกเลิก", sales["Cancellation Sales"]?.get(6))
        roomChargesTotal?.let { s.put("room_rent_total", "ค่าห้องแขกที่พักคืนนี้", it.getOrNull(3)) }
        roomStatus?.let { r ->
            s.put("total_rooms", "ห้องทั้งหมด", r[0])
            s.put("occupied_rooms", "ห้องมีแขก", r[1])
            s.put("due_out_rooms", "ห้องที่ต้องเช็คเอาท์", r[2])
            s.put("vacant_rooms", "ห้องว่าง", r[3])
        }

        val checks = mutableListOf<EzeeCheck>()
        salesTotal?.let { t ->
            DAILY_SALES_COLS.forEachIndexed { i, c ->
                check("Daily Sales $c", sales.values.sumOf { it[i] }, t[i])?.let(checks::add)
            }
            sales.forEach { (name, v) ->
                val calc = v[0] + v[1] + v[2] + v[3] - v[4] + v[5]
                if (!near(calc, v[6])) checks.add(EzeeCheck("$name: ส่วนประกอบไม่เท่ากับยอดรวม", false))
            }
        }
        roomChargesTotal?.let { t ->
            val rent = rows.mapNotNull { it["total_rent"] as? Double }
            if (rent.isNotEmpty()) check("ค่าห้องรายห้องรวม", rent.sum(), t.getOrNull(3))?.let(checks::add)
        }

        return empty("night_audit").copy(
            reportDate = date, periodFrom = date, periodTo = date,
            summary = s.values, labels = s.labels, rows = rows, checks = checks,
            notes = listOf("ยอดที่แนะนำ = ยอดขายรวมของคืนนี้ (เท่ากับ Total Revenue with Tax ใน Manager Report วันเดียวกัน — นับรายงานใดรายงานหนึ่งเท่านั้น)"),
            suggestedTransaction = "INCOME", suggestedAmount = s.values["total_sales"]
        )
    }

    /** "11 - Deluxe   1179   Guest   Source   13/09/2026 Room Only   1,680.00 ... admin" */
    private fun roomChargeRow(line: String): Map<String, Any?>? {
        val dateMatch = DATE.find(line) ?: return null
        val before = line.substring(0, dateMatch.range.first).trim().split(SPLIT)
        val after = line.substring(dateMatch.range.last + 1).trim()
        val (rateType, nums) = labelAndNumbers(after.substringBeforeLast(" ").trim())
            .let { (l, n) -> if (n.size >= 5) l to n else labelAndNumbers(after) }
        if (nums.size < 5) return null
        val user = after.split(Regex("\\s+")).lastOrNull()?.takeIf { num(it) == null }
        return linkedMapOf(
            "table" to "room_charges",
            "room" to before.getOrNull(0),
            "folio_no" to before.getOrNull(1),
            "guest" to before.getOrNull(2),
            "source" to before.drop(3).joinToString(" ").ifBlank { null },
            "rent_date" to isoDate(dateMatch.value),
            "rate_type" to rateType.ifBlank { null },
            "normal_tariff" to nums[0],
            "offered_tariff" to nums[1],
            "total_tax" to nums[2],
            "total_rent" to nums[3],
            "variance_percent" to nums[4],
            "checkin_by" to user
        )
    }

    // ------------------------------------------------------------------ Expense Voucher

    private fun expenseVoucher(lines: List<String>): EzeeReport {
        val from = dateAfter(lines, "Date From")
        val to = lines.firstOrNull { it.contains("Date From", true) }?.let { l ->
            DATE.findAll(l).toList().getOrNull(1)?.value?.let(::isoDate)
        } ?: from
        val rows = mutableListOf<Map<String, Any?>>()
        var grandTotal: Double? = null
        var inSummary = false
        val byMethod = LinkedHashMap<String, Double>()
        val byCharge = LinkedHashMap<String, Double>()

        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("Pay Method Wise Summary", true)) { inSummary = true; continue }
            if (!inSummary) {
                if (t.startsWith("Grand Total", true)) {
                    grandTotal = labelAndNumbers(t).second.firstOrNull()
                    continue
                }
                if (DATE.find(t)?.range?.first == 0) {
                    val cols = t.split(SPLIT)
                    val amountIdx = cols.indexOfLast { num(it) != null }
                    if (amountIdx > 0) {
                        rows.add(
                            linkedMapOf(
                                "table" to "voucher",
                                "date" to isoDate(cols[0]),
                                "voucher_no" to cols.getOrNull(1),
                                "bill_to" to cols.getOrNull(2)?.takeIf { amountIdx > 2 },
                                "charge" to cols.getOrNull(3)?.takeIf { amountIdx > 3 },
                                "comment" to cols.subList(minOf(4, amountIdx), amountIdx).joinToString(" ").ifBlank { null },
                                "amount" to num(cols[amountIdx]),
                                "pay_method" to cols.getOrNull(amountIdx + 1),
                                "user" to cols.getOrNull(amountIdx + 2)
                            )
                        )
                    }
                }
            } else {
                // "Cash   500.00   ค่าไฟ   500.00" (two summary tables side by side)
                if (t.startsWith("Pay Method", true)) continue
                val cols = t.split(SPLIT)
                val pairs = mutableListOf<Pair<String, Double>>()
                var i = 0
                while (i < cols.size - 1) {
                    val n = num(cols[i + 1])
                    if (num(cols[i]) == null && n != null) { pairs.add(cols[i] to n); i += 2 } else i++
                }
                pairs.getOrNull(0)?.let { (k, n) -> if (!k.startsWith("Grand Total", true)) byMethod[k] = n }
                pairs.getOrNull(1)?.let { (k, n) -> if (!k.startsWith("Grand Total", true)) byCharge[k] = n }
            }
        }

        val s = Summary()
        s.put("total_expense", "ค่าใช้จ่ายรวม", grandTotal)
        s.put("voucher_count", "จำนวนใบสำคัญจ่าย", rows.size.toDouble())
        byMethod.forEach { (k, n) -> s.put("paid_by_${slug(k).ifBlank { "other" }}", "จ่ายด้วย $k", n) }
        val checks = mutableListOf<EzeeCheck>()
        if (rows.isNotEmpty()) check("ใบสำคัญจ่ายรวม", rows.sumOf { (it["amount"] as? Double) ?: 0.0 }, grandTotal)?.let(checks::add)
        if (byMethod.isNotEmpty()) check("แยกตามวิธีจ่ายรวม", byMethod.values.sum(), grandTotal)?.let(checks::add)
        if (byCharge.isNotEmpty()) check("แยกตามหมวดรวม", byCharge.values.sum(), grandTotal)?.let(checks::add)
        val chargeRows = byCharge.map { (k, n) -> linkedMapOf<String, Any?>("table" to "charge_summary", "charge_type" to k, "amount" to n) }

        return empty("expense_voucher").copy(
            reportDate = to, periodFrom = from, periodTo = to,
            summary = s.values, labels = s.labels, rows = rows + chargeRows, checks = checks,
            notes = if ((grandTotal ?: 0.0) == 0.0) listOf("ไม่มีค่าใช้จ่ายในช่วงนี้") else
                listOf("ถ้าสแกนใบเสร็จของค่าใช้จ่ายเดียวกันไว้แล้ว อย่านับซ้ำ"),
            suggestedTransaction = "EXPENSE", suggestedAmount = grandTotal
        )
    }

    // ------------------------------------------------------------------ City Ledger Summary

    private fun cityLedger(lines: List<String>): EzeeReport {
        val from = dateAfter(lines, "Date From")
        val to = lines.firstOrNull { it.contains("Date From", true) }?.let { l ->
            DATE.findAll(l).toList().getOrNull(1)?.value?.let(::isoDate)
        } ?: from
        val rows = mutableListOf<Map<String, Any?>>()
        var total: List<Double>? = null
        for (line in lines) {
            val (label, nums) = labelAndNumbers(line)
            if (label.equals("Total", true) && nums.size >= 3) { total = nums.takeLast(3); continue }
            if (nums.size >= 4 && label.isNotBlank()) {
                val cols = line.trim().split(SPLIT)
                val n = nums.takeLast(4)
                rows.add(
                    linkedMapOf(
                        "account" to cols.first(),
                        "contact" to cols.drop(1).dropLast(4).firstOrNull { it != "-" },
                        "opening_balance" to n[0], "debit" to n[1], "credit" to n[2], "closing_balance" to n[3]
                    )
                )
            }
        }
        val s = Summary()
        s.put("opening_balance", "ยอดยกมา", rows.sumOf { it["opening_balance"] as Double })
        s.put("total_debit", "ตั้งหนี้เพิ่ม (Debit)", total?.get(0))
        s.put("total_credit", "รับชำระ (Credit)", total?.get(1))
        s.put("closing_balance", "ยอดค้างรับจาก OTA/บริษัท", total?.get(2))
        rows.forEach { r -> s.put("closing_${slug(r["account"].toString())}", "ค้างรับ ${r["account"]}", r["closing_balance"] as Double) }
        val checks = listOfNotNull(
            check("ยอดคงเหลือรายบัญชีรวม", rows.sumOf { it["closing_balance"] as Double }, total?.get(2)),
            check("ตั้งหนี้รวม", rows.sumOf { it["debit"] as Double }, total?.get(0)),
            check("รับชำระรวม", rows.sumOf { it["credit"] as Double }, total?.get(1))
        )
        return empty("city_ledger_summary").copy(
            reportDate = to, periodFrom = from, periodTo = to,
            summary = s.values, labels = s.labels, rows = rows, checks = checks,
            notes = listOf("เป็นยอดลูกหนี้ (เงินที่ OTA/บริษัทยังค้างจ่าย) — ไม่ใช่รายรับใหม่ จึงไม่นับเข้ายอด"),
            suggestedTransaction = null, suggestedAmount = null
        )
    }

    // ------------------------------------------------------------------ No Show Report

    private fun noShow(lines: List<String>): EzeeReport {
        val date = dateAfter(lines, "As On Date")
        val count = lines.firstNotNullOfOrNull { Regex("#\\((\\d+)\\)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val remarks = lines.count { it.startsWith("Remark") }
        val s = Summary()
        s.put("no_show_count", "จำนวนการจอง No Show", count ?: remarks.toDouble())
        return empty("no_show_report").copy(
            reportDate = date, periodFrom = date, periodTo = date,
            summary = s.values, labels = s.labels,
            checks = listOfNotNull(count?.let { EzeeCheck("จำนวนรายการ ${remarks} = ${it.toInt()}", remarks == it.toInt()) }),
            notes = listOf(
                "ตารางในรายงานนี้แคบจนตัวเลขถูกตัดขึ้นบรรทัดใหม่ จึงอ่านเฉพาะจำนวนรายการ",
                "รายได้ No Show ดูได้ครบใน Night Audit / Manager Report ของวันเดียวกัน"
            ),
            suggestedTransaction = null, suggestedAmount = null
        )
    }

    // ------------------------------------------------------------------ Monthly / Yearly Statistics

    private val STATS_COLS = listOf(
        "available_rooms", "nights_sold", "complimentary", "occupancy_percent", "adr", "revpar", "pax",
        "room_charges", "extra_charges", "tax", "receipt", "expense"
    )
    private val STATS_LABELS = mapOf(
        "available_rooms" to "ห้องที่ขายได้ทั้งหมด (ห้อง-คืน)",
        "nights_sold" to "ห้อง-คืนที่ขายได้",
        "complimentary" to "ห้องฟรี",
        "occupancy_percent" to "อัตราเข้าพัก %",
        "adr" to "ราคาห้องเฉลี่ย ADR",
        "revpar" to "RevPAR",
        "pax" to "จำนวนแขก",
        "room_charges" to "ค่าห้อง",
        "extra_charges" to "ค่าบริการเสริม",
        "tax" to "ภาษี",
        "receipt" to "รับเงิน",
        "expense" to "ค่าใช้จ่าย"
    )
    private val DAY_ROW = Regex("^(\\d{1,2})\\s+(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\b")
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    private fun statsRow(first: Pair<String, Any?>, nums: List<Double>): Map<String, Any?> =
        linkedMapOf(first).apply { STATS_COLS.forEachIndexed { i, c -> put(c, nums[i]) } }

    private fun statsSummary(total: List<Double>?, rows: List<Map<String, Any?>>, s: Summary, checks: MutableList<EzeeCheck>) {
        total ?: return
        STATS_COLS.forEachIndexed { i, c -> s.put(c, STATS_LABELS.getValue(c), total[i]) }
        for (c in listOf("nights_sold", "room_charges", "tax", "receipt", "expense")) {
            val i = STATS_COLS.indexOf(c)
            check("${STATS_LABELS[c]} รวมทุกแถว", rows.sumOf { (it[c] as? Double) ?: 0.0 }, total[i])?.let(checks::add)
        }
    }

    private fun monthlyStats(lines: List<String>): EzeeReport {
        val monthLine = lines.firstOrNull { it.startsWith("Month", true) }.orEmpty()
        val m = Regex("(${MONTHS.joinToString("|")})\\s*,?\\s*(\\d{4})").find(monthLine)
        val monthIdx = m?.let { MONTHS.indexOf(it.groupValues[1]) + 1 }
        val year = m?.groupValues?.get(2)?.toInt()?.let { if (it > 2400) it - 543 else it }
        val rows = mutableListOf<Map<String, Any?>>()
        var total: List<Double>? = null
        for (line in lines) {
            val t = line.trim()
            val (label, nums) = labelAndNumbers(t)
            if (nums.size < 12) continue
            val day = DAY_ROW.find(t)
            when {
                day != null && monthIdx != null && year != null ->
                    rows.add(statsRow("date" to ymd(year, monthIdx, day.groupValues[1].toInt()), nums.takeLast(12)))
                day != null -> rows.add(statsRow("day" to day.groupValues[1], nums.takeLast(12)))
                label.startsWith("Total", true) -> total = nums.takeLast(12)
            }
        }
        val s = Summary()
        val checks = mutableListOf<EzeeCheck>()
        statsSummary(total, rows, s, checks)
        val from = if (monthIdx != null && year != null) ymd(year, monthIdx, 1) else null
        val to = if (monthIdx != null && year != null) ymd(year, monthIdx, daysInMonth(year, monthIdx)) else null
        return empty("monthly_statistics").copy(
            reportDate = to, periodFrom = from, periodTo = to,
            summary = s.values, labels = s.labels, rows = rows, checks = checks,
            notes = listOf(
                "รายงานสรุปทั้งเดือน (รวมวันที่ยังไม่ถึงซึ่งเป็นยอดจองล่วงหน้า) — ไม่นับเข้ายอดเพื่อไม่ให้ซ้ำกับรายงานรายวัน",
                "\"รับเงิน\" = เงินที่รับจริงในแต่ละวัน, \"ค่าห้อง\" = รายได้ที่เกิดขึ้น"
            ),
            suggestedTransaction = null, suggestedAmount = null
        )
    }

    private fun yearlyStats(lines: List<String>): EzeeReport {
        val year = lines.firstNotNullOfOrNull { Regex("^Year\\s+(\\d{4})").find(it.trim())?.groupValues?.get(1)?.toInt() }
            ?.let { if (it > 2400) it - 543 else it }
        val rows = mutableListOf<Map<String, Any?>>()
        var total: List<Double>? = null
        for (line in lines) {
            val (label, nums) = labelAndNumbers(line)
            if (nums.size < 12) continue
            val month = MONTHS.indexOfFirst { label.equals(it, true) }
            when {
                month >= 0 -> rows.add(statsRow("month" to (year?.let { ymd(it, month + 1, 1).take(7) } ?: MONTHS[month]), nums.takeLast(12)))
                label.startsWith("Grand Total", true) || label.startsWith("Total", true) -> total = nums.takeLast(12)
            }
        }
        val s = Summary()
        val checks = mutableListOf<EzeeCheck>()
        statsSummary(total, rows, s, checks)
        return empty("yearly_statistics").copy(
            reportDate = year?.let { "$it-12-31" }, periodFrom = year?.let { "$it-01-01" }, periodTo = year?.let { "$it-12-31" },
            summary = s.values, labels = s.labels, rows = rows, checks = checks,
            notes = listOf("รายงานสรุปทั้งปี (เดือนข้างหน้าเป็นยอดจองล่วงหน้า) — ไม่นับเข้ายอดเพื่อไม่ให้ซ้ำกับรายงานรายวัน"),
            suggestedTransaction = null, suggestedAmount = null
        )
    }

    // ------------------------------------------------------------------ Front Desk Activities

    private val ROOM_LINE = Regex("^(\\d+)\\s*-\\s*(\\S+)\\s+(.*?)\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}/\\d{2}/\\d{4})\\s+(\\d+(?:\\.\\d+)?)\\s+(\\d+/\\d+)\\s+(.*)$")
    private val FOLIO_LINE = Regex("^(\\d+)\\s+(-?[\\d,]+\\.\\d{2})\\s+(-?[\\d,]+\\.\\d{2})\\s+(-?[\\d,]+\\.\\d{2})$")

    private fun frontDesk(lines: List<String>): EzeeReport {
        val date = dateAfter(lines, "Date")
        val rows = mutableListOf<MutableMap<String, Any?>>()
        var section = ""
        for (line in lines) {
            val t = line.trim()
            val lower = t.lower()
            if (!t.any { it.isDigit() } && t.split(SPLIT).size == 1 &&
                listOf("guests", "check-outs", "check-ins", "arrivals", "departures", "in house").any { it in lower }
            ) {
                section = t
                continue
            }
            ROOM_LINE.find(t)?.let { m ->
                val g = m.groupValues
                val guestAndMobile = g[3].split(SPLIT)
                val rest = g[8].split(SPLIT)
                rows.add(
                    linkedMapOf(
                        "section" to section,
                        "room" to "${g[1]} - ${g[2]}",
                        "guest" to guestAndMobile.firstOrNull()?.ifBlank { null },
                        "arrival" to isoDate(g[4]),
                        "departure" to isoDate(g[5]),
                        "nights" to g[6].toDoubleOrNull(),
                        "pax" to g[7],
                        "rate_type" to rest.getOrNull(0),
                        "source" to rest.getOrNull(1)?.takeIf { rest.size >= 3 },
                        "res_no" to (if (rest.size >= 3) rest.getOrNull(2) else rest.getOrNull(1))
                    )
                )
                return@let
            }
            FOLIO_LINE.find(t)?.let { m ->
                rows.lastOrNull()?.let { r ->
                    if (r["folio_no"] == null) {
                        r["folio_no"] = m.groupValues[1]
                        r["total"] = num(m.groupValues[2])
                        r["paid"] = num(m.groupValues[3])
                        r["balance"] = num(m.groupValues[4])
                    }
                }
            }
        }
        val s = Summary()
        rows.groupBy { it["section"].toString() }.forEach { (sec, list) ->
            s.put("${slug(sec).ifBlank { "rooms" }}_count", "จำนวนห้อง: $sec", list.size.toDouble())
            s.put("${slug(sec).ifBlank { "rooms" }}_balance", "ยอดค้างชำระ: $sec", list.sumOf { (it["balance"] as? Double) ?: 0.0 })
        }
        s.put("total_balance_due", "ยอดค้างชำระรวม", rows.sumOf { (it["balance"] as? Double) ?: 0.0 })
        val notes = mutableListOf("รายงานสถานะห้อง/แขก — ไม่นับเข้ายอด")
        if (rows.any { (it["guest"] as? String)?.contains('?') == true }) {
            notes.add("ชื่อภาษาไทยในไฟล์นี้อ่านไม่ได้ (eZee ฝังฟอนต์ไม่ครบ) จึงแสดงเป็น ???")
        }
        return empty("front_desk_activities").copy(
            reportDate = date, periodFrom = date, periodTo = date,
            summary = s.values, labels = s.labels, rows = rows, notes = notes,
            suggestedTransaction = null, suggestedAmount = null
        )
    }

    private fun String.lower() = lowercase()

    // ------------------------------------------------------------------ Monthly Occupancy (chart)

    private fun occupancy(lines: List<String>): EzeeReport {
        val m = lines.firstNotNullOfOrNull { Regex("(${MONTHS.joinToString("|")})\\s+(\\d{4})").find(it) }
        val monthIdx = m?.let { MONTHS.indexOf(it.groupValues[1]) + 1 }
        val year = m?.groupValues?.get(2)?.toInt()
        val from = if (monthIdx != null && year != null) ymd(year, monthIdx, 1) else null
        return empty("occupancy_monthly").copy(
            reportDate = from, periodFrom = from,
            notes = listOf("รายงานนี้เป็นกราฟ อ่านตัวเลขรายวันไม่ได้ — ใช้ Monthly Statistics แทน (มีอัตราเข้าพักรายวันครบ)"),
            suggestedTransaction = null, suggestedAmount = null
        )
    }
}
