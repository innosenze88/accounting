package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ezee.LineInfo
import com.example.data.ezee.ReportTemplate
import com.example.data.ezee.TemplateEngine
import com.example.data.ezee.TemplateField
import com.example.data.ezee.TemplateTable
import java.util.Locale
import java.util.UUID

private val Picked = Color(0xFF2E7D32)

/** A table the user marked in the builder (by tapping the table icon on a sample line). */
private data class TableDraft(val name: String, val columns: String)

/**
 * Full-screen "สร้างตัวอ่านเฉพาะ" (reader builder).
 * Shows every line of the report; the user taps the numbers to keep and names them.
 * A live preview shows what the saved reader will read.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TemplateBuilderDialog(
    fileName: String,
    pages: List<List<String>>,
    lines: List<LineInfo>,
    editing: ReportTemplate?,
    onSave: (ReportTemplate) -> Unit,
    onClose: () -> Unit
) {
    // ---------------------------------------------------------------- state
    var name by remember { mutableStateOf(editing?.name ?: TemplateEngine.suggestMatchText(pages)) }
    var matchText by remember { mutableStateOf(editing?.matchText ?: TemplateEngine.suggestMatchText(pages)) }
    var dateAnchor by remember { mutableStateOf(editing?.dateAnchor ?: TemplateEngine.suggestDateAnchor(lines) ?: "") }
    /** (line index, number index) -> Thai label */
    val picks = remember {
        mutableStateMapOf<Pair<Int, Int>, String>().apply {
            editing?.fields?.forEach { f ->
                TemplateEngine.locate(f, lines)?.let { l -> put(l.index to f.index, f.label) }
            }
        }
    }
    /** line index -> table draft */
    val tables = remember {
        mutableStateMapOf<Int, TableDraft>().apply {
            editing?.tables?.forEach { t ->
                lines.firstOrNull { l ->
                    l.numbers.size == t.numberCount && l.label.isNotBlank() &&
                        (t.heading == null || l.heading.equals(t.heading, ignoreCase = true))
                }?.let { l -> put(l.index, TableDraft(t.name, t.columns.joinToString(", "))) }
            }
        }
    }
    var countAs by remember { mutableStateOf(editing?.countAs) }
    var countPick by remember {
        mutableStateOf(
            editing?.let { e ->
                e.fields.firstOrNull { it.key == e.countKey }?.let { f -> TemplateEngine.locate(f, lines)?.let { it.index to f.index } }
            }
        )
    }
    var pickDialog by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val draftId = remember { editing?.id ?: UUID.randomUUID().toString() }
    val createdAt = remember { System.currentTimeMillis() }
    var tableDialog by remember { mutableStateOf<Int?>(null) }

    // ---------------------------------------------------------------- draft + live preview
    val draft = buildDraft(editing, draftId, createdAt, name, matchText, dateAnchor, picks.toMap(), tables.toMap(), countAs, countPick, lines)
    val preview = remember(draft) { TemplateEngine.apply(draft, pages) }
    val matchesFile = remember(draft) { TemplateEngine.matches(draft, pages, fileName) }
    val canSave = name.isNotBlank() && matchText.isNotBlank() && matchesFile && (picks.isNotEmpty() || tables.isNotEmpty())

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag("template_builder")) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (editing != null) "แก้ตัวอ่านเฉพาะ" else "สร้างตัวอ่านเฉพาะ", fontSize = 17.sp) },
                        navigationIcon = {
                            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "ปิด") }
                        },
                        actions = {
                            Button(
                                onClick = { onSave(draft) },
                                enabled = canSave,
                                modifier = Modifier.padding(end = 8.dp).testTag("template_save_button")
                            ) { Text("บันทึก") }
                        }
                    )
                }
            ) { inner ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(inner),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        SetupCard(
                            name = name, onName = { name = it },
                            matchText = matchText, onMatchText = { matchText = it }, matchesFile = matchesFile,
                            dateAnchor = dateAnchor, onDateAnchor = { dateAnchor = it }, foundDate = preview.reportDate
                        )
                    }
                    item {
                        Text(
                            "แตะตัวเลขที่ต้องการเก็บ (สีเขียว = เลือกแล้ว)  •  แตะ ▦ ท้ายบรรทัด = เก็บทั้งตาราง",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    items(lines, key = { it.index }) { line ->
                        LineRow(
                            line = line,
                            picked = { i -> picks.containsKey(line.index to i) },
                            counted = { i -> countPick == (line.index to i) },
                            isTable = tables.containsKey(line.index),
                            onPick = { i -> pickDialog = line.index to i },
                            onTable = { tableDialog = line.index }
                        )
                    }
                    item {
                        ResultCard(
                            preview = preview,
                            picks = picks.toMap(),
                            lines = lines,
                            countAs = countAs,
                            onCountAs = { countAs = it },
                            countPick = countPick,
                            onCountPick = { countPick = it },
                            draft = draft,
                            onRemove = { pos ->
                                picks.remove(pos)
                                if (countPick == pos) countPick = null
                            }
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------ dialogs
        pickDialog?.let { pos ->
            val line = lines[pos.first]
            val existing = picks[pos]
            var label by remember(pos) {
                mutableStateOf(existing ?: line.label.ifBlank { line.heading ?: "ตัวเลข ${pos.second + 1}" })
            }
            AlertDialog(
                onDismissRequest = { pickDialog = null },
                title = { Text("เก็บตัวเลข ${line.numberTexts.getOrNull(pos.second) ?: ""}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("ชื่อที่จะแสดง (ภาษาไทยได้)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (line.label.isBlank()) {
                            Text(
                                "บรรทัดนี้ไม่มีชื่อกำกับ — ถ้าจำนวนบรรทัดในรายงานเปลี่ยน อาจอ่านผิดตำแหน่ง แนะนำให้ใช้ ▦ เก็บเป็นตารางแทน",
                                fontSize = 11.sp,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (label.isNotBlank()) picks[pos] = label.trim()
                            pickDialog = null
                        }
                    ) { Text("เก็บ") }
                },
                dismissButton = {
                    Row {
                        if (existing != null) {
                            TextButton(onClick = {
                                picks.remove(pos)
                                if (countPick == pos) countPick = null
                                pickDialog = null
                            }) { Text("เอาออก", color = MaterialTheme.colorScheme.error) }
                        }
                        TextButton(onClick = { pickDialog = null }) { Text("ยกเลิก") }
                    }
                }
            )
        }

        tableDialog?.let { lineIdx ->
            val line = lines[lineIdx]
            val existing = tables[lineIdx]
            var tName by remember(lineIdx) { mutableStateOf(existing?.name ?: (line.heading ?: "table")) }
            var cols by remember(lineIdx) {
                mutableStateOf(existing?.columns ?: (1..line.numbers.size).joinToString(", ") { "col_$it" })
            }
            val sameShape = lines.count { l ->
                l.numbers.size == line.numbers.size && l.label.isNotBlank() &&
                    l.heading == line.heading && !l.label.startsWith("total", ignoreCase = true)
            }
            AlertDialog(
                onDismissRequest = { tableDialog = null },
                title = { Text("เก็บเป็นตาราง") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "จะเก็บทุกบรรทัดใต้หัวข้อ \"${line.heading ?: "-"}\" ที่มีตัวเลข ${line.numbers.size} ช่องเหมือนบรรทัดนี้ " +
                                "(ในไฟล์นี้มี $sameShape บรรทัด)",
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = tName, onValueChange = { tName = it },
                            label = { Text("ชื่อตาราง") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = cols, onValueChange = { cols = it },
                            label = { Text("ชื่อคอลัมน์ตัวเลข (คั่นด้วย ,)") }, modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        tables[lineIdx] = TableDraft(tName.trim().ifBlank { "table" }, cols)
                        tableDialog = null
                    }) { Text("เก็บตาราง") }
                },
                dismissButton = {
                    Row {
                        if (existing != null) {
                            TextButton(onClick = { tables.remove(lineIdx); tableDialog = null }) {
                                Text("เอาออก", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = { tableDialog = null }) { Text("ยกเลิก") }
                    }
                }
            )
        }
    }
}

/** Turns the builder state into a [ReportTemplate]. Keys of an edited reader are kept when the same number is picked. */
private fun buildDraft(
    editing: ReportTemplate?,
    id: String,
    createdAt: Long,
    name: String,
    matchText: String,
    dateAnchor: String,
    picks: Map<Pair<Int, Int>, String>,
    tables: Map<Int, TableDraft>,
    countAs: String?,
    countPick: Pair<Int, Int>?,
    lines: List<LineInfo>
): ReportTemplate {
    val keys = mutableListOf<String>()
    var countKey: String? = null
    val fields = picks.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (pos, label) ->
        val line = lines[pos.first]
        val old = editing?.fields?.firstOrNull { it.anchor == line.label && it.index == pos.second && it.key !in keys }
        val key = old?.key ?: TemplateEngine.newKey(line.label.ifBlank { label }, keys)
        keys.add(key)
        if (pos == countPick) countKey = key
        TemplateField(key, label, line.label, pos.second, line.heading, line.occurrence)
    }
    val tableList = tables.entries.sortedBy { it.key }.map { (idx, t) ->
        val line = lines[idx]
        TemplateTable(
            name = t.name,
            heading = line.heading,
            numberCount = line.numbers.size,
            columns = t.columns.split(",").map { it.trim() }
        )
    }
    return ReportTemplate(
        id = id,
        name = name.trim(),
        matchText = matchText.trim(),
        dateAnchor = dateAnchor.trim().ifBlank { null },
        fields = fields,
        tables = tableList,
        countKey = countKey,
        countAs = if (countKey != null) countAs else null,
        // A saved edit becomes the newest reader, so it wins over older ones for the same file.
        createdAt = maxOf(createdAt, editing?.createdAt ?: 0L)
    )
}

@Composable
private fun SetupCard(
    name: String,
    onName: (String) -> Unit,
    matchText: String,
    onMatchText: (String) -> Unit,
    matchesFile: Boolean,
    dateAnchor: String,
    onDateAnchor: (String) -> Unit,
    foundDate: String?
) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = onName, label = { Text("ชื่อตัวอ่าน") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("template_name_field")
            )
            OutlinedTextField(
                value = matchText, onValueChange = onMatchText,
                label = { Text("คำที่ใช้จำไฟล์ (มีอยู่ด้านบนของรายงานทุกฉบับ)") },
                singleLine = true, isError = !matchesFile, modifier = Modifier.fillMaxWidth()
            )
            if (!matchesFile) {
                Text("ไม่พบคำนี้ที่ด้านบนของไฟล์หรือในชื่อไฟล์ — ใช้ชื่อรายงานที่พิมพ์อยู่หัวกระดาษ", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = dateAnchor, onValueChange = onDateAnchor,
                label = { Text("วันที่รายงาน อยู่หลังคำว่า (เว้นว่าง = วันที่แรกที่เจอ)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(
                "วันที่ที่อ่านได้: ${foundDate ?: "ไม่พบ"}",
                fontSize = 12.sp,
                color = if (foundDate != null) Picked else MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineRow(
    line: LineInfo,
    picked: (Int) -> Boolean,
    counted: (Int) -> Boolean,
    isTable: Boolean,
    onPick: (Int) -> Unit,
    onTable: () -> Unit
) {
    val bg = if (isTable) Color(0x1A2E7D32) else Color.Transparent
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        if (line.numbers.isEmpty()) {
            val isHeading = line.dates.isEmpty()
            Text(
                line.text,
                fontSize = if (isHeading) 12.sp else 11.sp,
                fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
                color = if (isHeading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                line.label.ifBlank { "(ไม่มีชื่อ)" },
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                color = if (line.label.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onTable, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.TableChart,
                    contentDescription = "เก็บเป็นตาราง",
                    tint = if (isTable) Picked else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            line.numberTexts.forEachIndexed { i, t ->
                FilterChip(
                    selected = picked(i),
                    onClick = { onPick(i) },
                    label = { Text((if (counted(i)) "★ " else "") + t, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Picked,
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.height(30.dp)
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 3.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    preview: com.example.data.ezee.EzeeReport,
    picks: Map<Pair<Int, Int>, String>,
    lines: List<LineInfo>,
    countAs: String?,
    onCountAs: (String?) -> Unit,
    countPick: Pair<Int, Int>?,
    onCountPick: (Pair<Int, Int>?) -> Unit,
    draft: ReportTemplate,
    onRemove: (Pair<Int, Int>) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ผลทดลองอ่าน", fontWeight = FontWeight.Bold)
            if (picks.isEmpty() && draft.tables.isEmpty()) {
                Text("ยังไม่ได้เลือกตัวเลข — แตะตัวเลขในรายงานด้านบน", fontSize = 12.sp)
            }
            val sorted = picks.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            sorted.forEach { (pos, label) ->
                val field = draft.fields.firstOrNull { it.anchor == lines[pos.first].label && it.index == pos.second && it.label == label }
                val v = field?.let { preview.summary[it.key] }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        v?.let { String.format(Locale.US, "%,.2f", it) } ?: "ไม่พบ",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (v != null) Color.Unspecified else MaterialTheme.colorScheme.error
                    )
                    IconButton(onClick = { onRemove(pos) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "เอาออก", modifier = Modifier.size(16.dp))
                    }
                }
            }
            draft.tables.forEach { t ->
                val n = preview.rows.count { it["table"] == t.name }
                Text("ตาราง \"${t.name}\": $n แถว (${t.columns.joinToString(", ")})", fontSize = 12.sp)
            }

            HorizontalDivider()
            Text("นับเข้ายอดแดชบอร์ดไหม?", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "ไม่นับ", "INCOME" to "รายรับ", "EXPENSE" to "รายจ่าย").forEach { (code, title) ->
                    FilterChip(selected = countAs == code, onClick = { onCountAs(code) }, label = { Text(title) })
                }
            }
            if (countAs != null) {
                Text("ใช้ตัวเลขไหนเป็นยอด:", fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sorted.forEach { (pos, label) ->
                        FilterChip(
                            selected = countPick == pos,
                            onClick = { onCountPick(pos) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                if (countPick == null) {
                    Text("เลือกตัวเลขที่จะนับ (★)", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "ตัวอ่านนี้จะใช้กับไฟล์ที่มีคำว่า \"${draft.matchText}\" ด้านบน และจะใช้ก่อนตัวอ่าน eZee ในแอป",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** List of saved readers with delete / copy all (backup) / paste (import). */
@Composable
fun TemplateListCard(
    templates: List<ReportTemplate>,
    onDelete: (String) -> Unit,
    exportJson: () -> String,
    onImport: (String) -> Unit
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<ReportTemplate?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ตัวอ่านที่สร้างเอง (${templates.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "สร้างได้จากไฟล์ PDF แบบใหม่: เลือกไฟล์ → \"สร้างตัวอ่านเฉพาะจากไฟล์นี้\"",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            templates.sortedByDescending { it.createdAt }.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "จำจากคำว่า \"${t.matchText}\" • ${t.fields.size} ตัวเลข" +
                                (if (t.tables.isNotEmpty()) " • ${t.tables.size} ตาราง" else "") +
                                when (t.countAs) {
                                    "INCOME" -> " • นับเป็นรายรับ"
                                    "EXPENSE" -> " • นับเป็นรายจ่าย"
                                    else -> ""
                                },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { confirmDelete = t }) {
                        Icon(Icons.Default.Delete, contentDescription = "ลบตัวอ่าน")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ตัวอ่านเฉพาะ", exportJson()))
                        copied = true
                    },
                    enabled = templates.isNotEmpty()
                ) { Text(if (copied) "คัดลอกแล้ว ✓" else "คัดลอกทั้งหมด (สำรอง)", fontSize = 12.sp) }
                OutlinedButton(onClick = { showImport = true }) { Text("วางเพื่อนำเข้า", fontSize = 12.sp) }
            }
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("ลบตัวอ่าน \"${t.name}\"?") },
            text = { Text("รายงานที่บันทึกไว้แล้วไม่หาย แต่ไฟล์แบบนี้ครั้งต่อไปจะใช้ตัวอ่าน eZee ในแอปหรือ AI แทน") },
            confirmButton = {
                TextButton(onClick = { onDelete(t.id); confirmDelete = null }) {
                    Text("ลบ", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("ยกเลิก") } }
        )
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("นำเข้าตัวอ่าน") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    }) { Text("วางจากคลิปบอร์ด") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("ข้อความตัวอ่าน (JSON)") },
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onImport(text); showImport = false }, enabled = text.isNotBlank()) { Text("นำเข้า") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("ยกเลิก") } }
        )
    }
}
