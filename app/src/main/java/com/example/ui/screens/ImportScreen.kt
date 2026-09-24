package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.importer.FileKind
import com.example.data.local.ImportKind
import com.example.data.local.ImportStatus
import com.example.data.local.ImportedFileEntity
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.documentStatus
import com.example.data.local.quickVerifyProblem
import com.example.data.model.DocumentStatus
import com.example.data.model.TransactionType
import com.example.ui.components.RulesGuideContent
import com.example.ui.viewmodel.AccountantViewModel
import java.text.SimpleDateFormat
import java.util.Date
import com.example.ui.components.TemplateBuilderDialog
import com.example.ui.components.TemplateListCard
import java.util.Locale

/** MIME types offered in the file picker. */
private val PICKER_TYPES = arrayOf(
    "application/pdf",
    "image/*",
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/octet-stream"
)

@Composable
fun ImportScreen(
    viewModel: AccountantViewModel,
    onGoToScanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pending by viewModel.pendingImport.collectAsStateWithLifecycle()
    val report by viewModel.reportPreview.collectAsStateWithLifecycle()
    val busy by viewModel.isImportBusy.collectAsStateWithLifecycle()
    val message by viewModel.importMessage.collectAsStateWithLifecycle()
    val imported by viewModel.importedFiles.collectAsStateWithLifecycle()
    val settings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val batch by viewModel.batchItems.collectAsStateWithLifecycle()
    val allDocuments by viewModel.historyList.collectAsStateWithLifecycle()
    val templates by viewModel.reportTemplates.collectAsStateWithLifecycle()
    val builder by viewModel.builder.collectAsStateWithLifecycle()
    var showRules by rememberSaveable { mutableStateOf(false) }

    // Several files can be selected at once (long-press to select more in the file picker).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.onFilesPicked(uris)
    }

    if (showRules) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = { showRules = false }, modifier = Modifier.padding(start = 8.dp)) {
                Text("← กลับไปหน้านำเข้าไฟล์")
            }
            Box(Modifier.weight(1f)) { RulesGuideContent() }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("นำเข้าไฟล์จากอีเมล / เครื่อง", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "• กดปุ่มด้านล่างเพื่อเลือกไฟล์ — เลือกได้หลายไฟล์พร้อมกัน (กดค้างที่ไฟล์แรก แล้วแตะไฟล์อื่นเพิ่ม)\n" +
                        "• ใน Gmail เปิดไฟล์แนบ → แชร์ (Share) → เลือกแอปนี้\n" +
                        "รองรับ: PDF (สลิป/ใบเสร็จ/รายงาน eZee), รูปภาพ, CSV, Excel (.xlsx)\n" +
                        "เลือกหลายไฟล์: PDF/รูป = อ่านเป็นสลิป/ใบเสร็จอัตโนมัติ, CSV/Excel = บันทึกเป็นตาราง",
                    fontSize = 12.sp
                )
                Button(
                    onClick = { picker.launch(PICKER_TYPES) },
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_pick_button")
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("เลือกไฟล์ (ได้หลายไฟล์)")
                }
                Text(
                    "ยอดในแดชบอร์ดนับเฉพาะสลิป/ใบเสร็จที่กด \"ยืนยัน\" แล้ว และรายงาน eZee ที่เลือก \"นับเข้ายอด\" ตอนบันทึก — ตาราง CSV/Excel ไม่นับรวมในยอด",
                    fontSize = 11.sp,
                    color = Color(0xFFE65100)
                )
            }
        }

        if (batch.isNotEmpty()) {
            BatchResultCard(
                items = batch,
                allDocuments = allDocuments,
                busy = busy,
                noteFor = { entity -> viewModel.batchDocumentNote(entity, allDocuments) },
                onOpen = { entity ->
                    viewModel.loadFromHistory(entity)
                    onGoToScanner()
                },
                onVerifyAll = { ids -> viewModel.verifyDocuments(ids) },
                onClose = { viewModel.clearBatch() }
            )
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("กำลังทำงาน...", fontSize = 13.sp)
            }
        }
        message?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = if (it.startsWith("✕")) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
            )
        }
        if (!settings.sheetsEnabled) {
            Text(
                "ยังไม่ได้ตั้งค่า Google Sheets — ไฟล์จะถูกบันทึกในเครื่องเท่านั้น (ตั้งค่าได้ที่แท็บ \"ตั้งค่า\")",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        pending?.let { p ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.testTag("pending_import_card")
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (p.table != null) Icons.Default.TableChart else Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.file.fileName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (p.file.kind) {
                                    FileKind.PDF -> "PDF ${if (p.pageCount > 0) "${p.pageCount} หน้า" else ""}"
                                    FileKind.IMAGE -> "รูปภาพ"
                                    FileKind.CSV -> "CSV ${p.table?.rows?.size ?: 0} แถว"
                                    FileKind.XLSX -> "Excel ${p.table?.rows?.size ?: 0} แถว"
                                    FileKind.HTML_TABLE -> "ตาราง (.xls) ${p.table?.rows?.size ?: 0} แถว"
                                    FileKind.UNSUPPORTED -> "ไม่รองรับ"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { viewModel.clearPendingImport() }) { Text("ยกเลิก") }
                    }

                    p.preview?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "ตัวอย่างไฟล์",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    }

                    val table = p.table
                    if (table != null) {
                        TablePreview(table.headers, table.rows)
                        var sheetName by rememberSaveable(p.file.fileName) {
                            mutableStateOf(defaultSheetName(p.file.fileName))
                        }
                        OutlinedTextField(
                            value = sheetName,
                            onValueChange = { sheetName = it },
                            label = { Text("ชื่อแผ่นงาน (Sheet) ปลายทาง") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { viewModel.saveTable(sheetName) },
                            enabled = !busy && table.headers.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_table_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (settings.sheetsEnabled) "บันทึก & ส่งไป Google Sheets" else "บันทึกในเครื่อง")
                        }
                    } else if (report == null) {
                        Text("ไฟล์นี้คืออะไร?", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Button(
                            onClick = { if (viewModel.useImportAsAccountingDocument()) onGoToScanner() },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("import_as_document_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("สลิป / ใบเสร็จ / บิล → อ่านเข้าบัญชี") }
                        OutlinedButton(
                            onClick = { viewModel.readPendingAsReport() },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("import_as_report_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("รายงาน eZee → อ่านสรุปตัวเลข") }
                        if (p.file.kind == FileKind.PDF) {
                            OutlinedButton(
                                onClick = { viewModel.openTemplateBuilder() },
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("open_template_builder_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("รายงานแบบใหม่ → สร้างตัวอ่านเฉพาะจากไฟล์นี้") }
                        }
                    }

                    report?.let { r ->
                        HorizontalDivider()
                        Text(r.title, fontWeight = FontWeight.Bold)
                        Text(
                            "ประเภท: ${r.reportType}  •  วันที่: ${r.reportDate ?: "-"}  •  ตาราง ${r.rowCount} แถว",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (r.readLocally) {
                            Text(
                                if (r.readByTemplate) "✓ อ่านด้วยตัวอ่านที่สร้างเอง \"${r.title}\" — ไม่ใช้ AI"
                                else "✓ อ่านด้วยตัวอ่าน eZee ในเครื่อง — ตัวเลขตรงตามไฟล์ ไม่ใช้ AI",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.testTag("report_read_locally")
                            )
                        }
                        if (r.summary.isEmpty()) {
                            Text("ไม่พบตัวเลขสรุปในรายงาน", fontSize = 12.sp)
                        } else {
                            r.summary.forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(r.labelFor(k), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text(
                                        v.toDoubleOrNull()?.let { d ->
                                            val whole = d == Math.floor(d)
                                            val fmt = if (whole && AccountantViewModel.ReportPreview.isNonMoneyKey(k)) "%,.0f" else "%,.2f"
                                            String.format(Locale.US, fmt, d)
                                        } ?: v,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        val checks = r.checks
                        if (checks.isNotEmpty()) {
                            val bad = checks.count { !it.second }
                            Text(
                                if (bad == 0) "✓ ตรวจยอดรวมอัตโนมัติ ${checks.size} รายการ — ตรงทั้งหมด"
                                else "✕ ยอดรวมไม่ตรง $bad จาก ${checks.size} รายการ — ตรวจกับไฟล์ก่อนบันทึก",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (bad == 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                            checks.filter { !it.second }.forEach { (label, _) ->
                                Text("  • $label", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        r.notes?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (!r.readLocally) {
                            Text(
                                "ตรวจตัวเลขกับรายงานก่อนบันทึก — AI อาจอ่านผิดได้",
                                fontSize = 11.sp,
                                color = Color(0xFFE65100)
                            )
                        }
                        ReportCountingSection(
                            report = r,
                            reportKey = p.file.fileName,
                            allDocuments = allDocuments,
                            busy = busy,
                            sheetsEnabled = settings.sheetsEnabled,
                            onSave = { tx, amount -> viewModel.saveReport(tx, amount) }
                        )
                        if (p.file.kind == FileKind.PDF) {
                            TextButton(
                                onClick = { viewModel.openTemplateBuilder() },
                                enabled = !busy,
                                modifier = Modifier.testTag("edit_template_button")
                            ) {
                                Text(
                                    if (r.readByTemplate) "แก้ตัวอ่านเฉพาะของรายงานนี้"
                                    else "ตัวเลขไม่ครบ/ไม่ตรง? → สร้างตัวอ่านเฉพาะ",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        TemplateListCard(
            templates = templates,
            onDelete = { viewModel.deleteTemplate(it) },
            exportJson = { viewModel.exportTemplates() },
            onImport = { viewModel.importTemplates(it) }
        )
        builder?.let { b ->
            TemplateBuilderDialog(
                fileName = b.fileName,
                pages = b.pages,
                lines = b.lines,
                editing = b.editing,
                onSave = { viewModel.saveTemplate(it) },
                onClose = { viewModel.closeTemplateBuilder() }
            )
        }

        Text("ไฟล์ที่นำเข้าแล้ว (${imported.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (imported.isEmpty()) {
            Text("ยังไม่มี", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val countedReports = remember(allDocuments) {
            allDocuments.filter { it.documentNo?.startsWith(AccountantViewModel.REPORT_DOC_PREFIX) == true }
                .associateBy { it.documentNo }
        }
        imported.forEach { item ->
            ImportedFileRow(
                item = item,
                counted = countedReports[AccountantViewModel.REPORT_DOC_PREFIX + item.id],
                canResend = settings.sheetsEnabled,
                busy = busy,
                onResend = { viewModel.resendImport(item.id) },
                onDelete = { viewModel.deleteImport(item.id) }
            )
        }

        TextButton(onClick = { showRules = true }) {
            Icon(Icons.AutoMirrored.Filled.Rule, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("ดูกฎการอ่านเอกสาร & Schema")
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun defaultSheetName(fileName: String): String =
    fileName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").take(60).ifBlank { "Import" }

@Composable
private fun TablePreview(headers: List<String>, rows: List<List<String>>) {
    val shown = rows.take(5)
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Row {
            headers.forEach { h ->
                Text(
                    h,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(110.dp)
                        .padding(4.dp)
                )
            }
        }
        shown.forEach { r ->
            Row {
                r.forEach { v ->
                    Text(
                        v,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(110.dp)
                            .padding(4.dp)
                    )
                }
            }
        }
    }
    if (rows.size > shown.size) {
        Text("... และอีก ${rows.size - shown.size} แถว", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImportedFileRow(
    item: ImportedFileEntity,
    counted: ExtractedDocumentEntity?,
    canResend: Boolean,
    busy: Boolean,
    onResend: () -> Unit,
    onDelete: () -> Unit
) {
    val status = ImportStatus.fromCode(item.status)
    val kind = ImportKind.fromCode(item.kind)
    val time = remember(item.createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.createdAt))
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.fileName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${kind.titleTh} • ${item.rowCount ?: 0} แถว • $time" +
                        (item.reportDate?.let { " • วันที่รายงาน $it" } ?: ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    status.titleTh + (item.targetSheet?.let { " → $it" } ?: ""),
                    fontSize = 11.sp,
                    color = when (status) {
                        ImportStatus.SYNCED -> Color(0xFF2E7D32)
                        ImportStatus.SYNC_FAILED -> MaterialTheme.colorScheme.error
                        ImportStatus.SAVED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (kind == ImportKind.EZEE_REPORT) {
                    Text(
                        counted?.let {
                            "นับในยอด: ${TransactionType.fromCode(it.transactionType)?.titleTh ?: ""} " +
                                String.format("%,.2f ฿", it.totalAmount ?: 0.0)
                        } ?: "ไม่นับในยอด",
                        fontSize = 11.sp,
                        fontWeight = if (counted != null) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (counted != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item.errorMessage?.takeIf { status == ImportStatus.SYNC_FAILED }?.let {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (canResend && status != ImportStatus.SYNCED) {
                IconButton(onClick = onResend, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = "ส่งไป Google Sheets อีกครั้ง")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "ลบ", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Results of a multi-file upload, with per-file status and a bulk "approve" button. */
@Composable
private fun BatchResultCard(
    items: List<AccountantViewModel.BatchItem>,
    allDocuments: List<ExtractedDocumentEntity>,
    busy: Boolean,
    noteFor: (ExtractedDocumentEntity) -> String?,
    onOpen: (ExtractedDocumentEntity) -> Unit,
    onVerifyAll: (List<Long>) -> Unit,
    onClose: () -> Unit
) {
    val byId = remember(allDocuments) { allDocuments.associateBy { it.id } }
    val docs = items.mapNotNull { it.documentId?.let { id -> byId[id] } }
    // Only complete, non-duplicate PENDING documents can be approved in bulk.
    val ready = docs.filter { it.quickVerifyProblem() == null && noteFor(it) == null }
    val readyIncome = ready.filter { TransactionType.fromCode(it.transactionType) == TransactionType.INCOME }
        .sumOf { it.totalAmount ?: 0.0 }
    val readyExpense = ready.filter { TransactionType.fromCode(it.transactionType) == TransactionType.EXPENSE }
        .sumOf { it.totalAmount ?: 0.0 }
    val done = items.count { it.state != AccountantViewModel.BatchState.WAITING && it.state != AccountantViewModel.BatchState.READING }
    var confirm by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("batch_result_card")
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "อัปโหลดหลายไฟล์ ($done/${items.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose, enabled = !busy) { Text("ปิด") }
            }

            items.forEach { item ->
                val entity = item.documentId?.let { byId[it] }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.fileName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (entity != null) {
                            val status = entity.documentStatus()
                            val tx = TransactionType.fromCode(entity.transactionType)
                            Text(
                                "${tx?.titleTh ?: "? รายรับ/รายจ่าย"} • " +
                                    (entity.totalAmount?.let { String.format("%,.2f ฿", it) } ?: "ไม่มียอด") +
                                    (entity.date?.let { " • $it" } ?: "") + " • " + status.titleTh,
                                fontSize = 12.sp,
                                color = when (status) {
                                    DocumentStatus.VERIFIED -> Color(0xFF2E7D32)
                                    DocumentStatus.REJECTED -> MaterialTheme.colorScheme.error
                                    DocumentStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (status == DocumentStatus.PENDING) {
                                noteFor(entity)?.let {
                                    Text(it, fontSize = 11.sp, color = Color(0xFFE65100))
                                }
                            }
                        } else {
                            Text(
                                item.state.titleTh + (item.message?.let { " — $it" } ?: ""),
                                fontSize = 12.sp,
                                color = when (item.state) {
                                    AccountantViewModel.BatchState.FAILED -> MaterialTheme.colorScheme.error
                                    AccountantViewModel.BatchState.TABLE,
                                    AccountantViewModel.BatchState.REPORT -> Color(0xFF2E7D32)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    if (item.state == AccountantViewModel.BatchState.READING) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    if (entity != null) {
                        TextButton(onClick = { onOpen(entity) }, enabled = !busy) { Text("ตรวจ") }
                    }
                }
            }

            if (ready.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "พร้อมยืนยัน ${ready.size} ใบ — รายรับ ${String.format("%,.2f ฿", readyIncome)} • รายจ่าย ${String.format("%,.2f ฿", readyExpense)}",
                    fontSize = 12.sp
                )
                Button(
                    onClick = { confirm = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("batch_verify_all_button")
                ) { Text("ยืนยันทั้งหมด ${ready.size} ใบ → นับเข้ายอด") }
            }
            val needReview = docs.count { it.documentStatus() == DocumentStatus.PENDING && noteFor(it) != null }
            if (needReview > 0) {
                Text(
                    "อีก $needReview ใบข้อมูลไม่ครบหรืออาจซ้ำ — กด \"ตรวจ\" เพื่อดู/แก้แล้วยืนยันทีละใบ",
                    fontSize = 11.sp,
                    color = Color(0xFFE65100)
                )
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("ยืนยัน ${ready.size} เอกสาร?") },
            text = {
                Text(
                    "ยอดเหล่านี้จะถูกนับเข้าแดชบอร์ดทันที\n" +
                        "รายรับ ${String.format("%,.2f ฿", readyIncome)}\nรายจ่าย ${String.format("%,.2f ฿", readyExpense)}\n\n" +
                        "AI อาจอ่านผิดได้ — ถ้ายังไม่แน่ใจ ให้กด \"ตรวจ\" ดูทีละใบก่อน (แก้ไขภายหลังได้ที่หน้าประวัติ)"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onVerifyAll(ready.map { it.id })
                }) { Text("ยืนยันทั้งหมด") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("ยกเลิก") } }
        )
    }
}

/**
 * Lets the person choose whether an eZee report is added to the dashboard totals,
 * as income or expense, and which amount (pre-filled from the report summary).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportCountingSection(
    report: AccountantViewModel.ReportPreview,
    reportKey: String,
    allDocuments: List<ExtractedDocumentEntity>,
    busy: Boolean,
    sheetsEnabled: Boolean,
    onSave: (TransactionType?, Double?) -> Unit
) {
    val initialTx = remember(reportKey) { report.suggestedTransaction }
    var countIt by rememberSaveable(reportKey) { mutableStateOf(report.countByDefault) }
    var tx by rememberSaveable(reportKey) { mutableStateOf(initialTx.code) }
    val txType = TransactionType.fromCode(tx) ?: TransactionType.INCOME
    var amountText by rememberSaveable(reportKey) {
        mutableStateOf(report.suggestedAmount(initialTx)?.let { String.format(Locale.US, "%.2f", it) } ?: "")
    }
    val amount = amountText.replace(",", "").trim().toDoubleOrNull()
    val amountOk = amount != null && amount > 0.0

    // Another report with the same date and direction already counted -> probably counted twice.
    val sameDay = remember(allDocuments, report.reportDate, tx) {
        report.reportDate?.let { d ->
            allDocuments.filter {
                it.documentNo?.startsWith(AccountantViewModel.REPORT_DOC_PREFIX) == true &&
                    it.date == d && it.transactionType == tx && it.documentStatus() == DocumentStatus.VERIFIED
            }
        } ?: emptyList()
    }

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = countIt, onCheckedChange = { countIt = it }, modifier = Modifier.testTag("report_count_checkbox"))
        Text("นับเข้ายอดแดชบอร์ด", fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
    if (countIt) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TransactionType.entries.forEach { t ->
                FilterChip(
                    selected = tx == t.code,
                    onClick = {
                        tx = t.code
                        report.suggestedAmount(t)?.let { amountText = String.format(Locale.US, "%.2f", it) }
                    },
                    label = { Text(t.titleTh) }
                )
            }
        }
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("ยอดที่จะนับเป็น${txType.titleTh} (บาท)") },
            singleLine = true,
            isError = !amountOk,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("report_amount_field")
        )
        val picks = report.numericSummary.filter { it.second > 0 }
        if (picks.isNotEmpty()) {
            Text("แตะเพื่อใช้ตัวเลขจากรายงาน:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                picks.forEach { (k, v) ->
                    FilterChip(
                        selected = amount != null && kotlin.math.abs(amount - v) < 0.005,
                        onClick = { amountText = String.format(Locale.US, "%.2f", v) },
                        label = { Text("${report.labelFor(k)} ${String.format(Locale.US, "%,.2f", v)}", fontSize = 11.sp) }
                    )
                }
            }
        }
        if (report.reportDate == null) {
            Text("รายงานนี้ไม่มีวันที่ — ยอดจะนับรวมแต่ไม่มีวันที่กำกับ", fontSize = 11.sp, color = Color(0xFFE65100))
        }
        if (sameDay.isNotEmpty()) {
            Text(
                "มีรายงานวันที่ ${report.reportDate} ที่นับเป็น${txType.titleTh}ไว้แล้ว " +
                    "(${String.format("%,.2f ฿", sameDay.sumOf { it.totalAmount ?: 0.0 })}) — ระวังนับซ้ำ",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            "ถ้าสแกนใบเสร็จ/สลิปของรายการเดียวกันไว้แล้ว ยอดจะซ้ำ — เลือกนับจากรายงานหรือจากใบเสร็จอย่างใดอย่างหนึ่ง",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Button(
        onClick = { if (countIt) onSave(txType, amount) else onSave(null, null) },
        enabled = !busy && (!countIt || amountOk),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("save_report_button"),
        shape = RoundedCornerShape(10.dp),
        colors = if (countIt) ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)) else ButtonDefaults.buttonColors()
    ) {
        Text(
            (if (countIt && amountOk) "บันทึก & นับเป็น${txType.titleTh} ${String.format("%,.2f ฿", amount)}" else "บันทึก") +
                (if (sheetsEnabled) " + ส่ง Sheets" else "")
        )
    }
}
