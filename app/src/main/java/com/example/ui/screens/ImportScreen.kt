package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.importer.FileKind
import com.example.data.local.ImportKind
import com.example.data.local.ImportStatus
import com.example.data.local.ImportedFileEntity
import com.example.ui.components.RulesGuideContent
import com.example.ui.viewmodel.AccountantViewModel
import java.text.SimpleDateFormat
import java.util.Date
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
    var showRules by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onFilePicked(it) }
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
                    "• กดปุ่มด้านล่างเพื่อเลือกไฟล์ หรือ\n" +
                        "• ใน Gmail เปิดไฟล์แนบ → แชร์ (Share) → เลือกแอปนี้\n" +
                        "รองรับ: PDF (สลิป/ใบเสร็จ/รายงาน eZee), รูปภาพ, CSV, Excel (.xlsx)",
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
                    Text("เลือกไฟล์")
                }
            }
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
                    }

                    report?.let { r ->
                        HorizontalDivider()
                        Text(r.title, fontWeight = FontWeight.Bold)
                        Text(
                            "ประเภท: ${r.reportType}  •  วันที่: ${r.reportDate ?: "-"}  •  ตาราง ${r.rowCount} แถว",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (r.summary.isEmpty()) {
                            Text("ไม่พบตัวเลขสรุปในรายงาน", fontSize = 12.sp)
                        } else {
                            r.summary.forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(k, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(
                            "ตรวจตัวเลขกับรายงานก่อนบันทึก — AI อาจอ่านผิดได้",
                            fontSize = 11.sp,
                            color = Color(0xFFE65100)
                        )
                        Button(
                            onClick = { viewModel.saveReport() },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_report_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (settings.sheetsEnabled) "บันทึก & ส่งไป Google Sheets" else "บันทึกในเครื่อง")
                        }
                    }
                }
            }
        }

        Text("ไฟล์ที่นำเข้าแล้ว (${imported.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (imported.isEmpty()) {
            Text("ยังไม่มี", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        imported.forEach { item ->
            ImportedFileRow(
                item = item,
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
