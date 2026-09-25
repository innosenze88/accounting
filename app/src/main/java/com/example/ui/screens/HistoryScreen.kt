package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.countsInAccounting
import com.example.data.local.documentStatus
import com.example.data.model.DocumentStatus
import com.example.ui.components.DocumentStatusBadge
import com.example.ui.components.DocumentTypeBadge
import com.example.ui.components.ExtractedDocumentCard
import com.example.ui.components.JsonViewer
import com.example.ui.components.TransactionTypeBadge
import androidx.compose.material.icons.filled.Block
import androidx.compose.ui.text.style.TextDecoration
import com.example.data.local.canDelete
import com.example.data.local.canVoid
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.VoidDocumentDialog
import com.example.ui.components.VoidedInfoCard
import com.example.ui.viewmodel.AccountantViewModel

@Composable
fun HistoryScreen(
    viewModel: AccountantViewModel,
    onNavigateToScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    var selectedFilterType by remember { mutableStateOf<String?>(null) }
    var selectedEntityForDetail by remember { mutableStateOf<ExtractedDocumentEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<ExtractedDocumentEntity?>(null) }
    var confirmVoid by remember { mutableStateOf<ExtractedDocumentEntity?>(null) }
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()

    val filteredList = remember(historyList, selectedFilterType) {
        when (selectedFilterType) {
            null -> historyList
            FILTER_PENDING -> historyList.filter { it.sampleId == null && it.documentStatus() == DocumentStatus.PENDING }
            else -> historyList.filter { it.documentType.equals(selectedFilterType, ignoreCase = true) }
        }
    }

    // Totals only include real documents that a person has verified.
    val verifiedList = remember(historyList) { historyList.filter { it.countsInAccounting() } }
    val pendingCount = remember(historyList) {
        historyList.count { it.sampleId == null && it.documentStatus() == DocumentStatus.PENDING }
    }

    val totalIncome = remember(verifiedList) {
        verifiedList.filter { it.transactionType.equals("INCOME", ignoreCase = true) }
            .sumOf { it.totalAmount ?: 0.0 }
    }

    val totalExpense = remember(verifiedList) {
        verifiedList.filter { it.transactionType.equals("EXPENSE", ignoreCase = true) }
            .sumOf { it.totalAmount ?: 0.0 }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Stats Overview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "สรุปยอดเฉพาะเอกสารที่ตรวจแล้ว (${verifiedList.size} จาก ${historyList.size} เอกสาร)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Income
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE8F5E9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("รายรับรวม", fontSize = 11.sp, color = Color(0xFF2E7D32))
                        }
                        Text(
                            text = String.format("%,.2f ฿", totalIncome),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }

                    // Expense
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFEBEE)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    tint = Color(0xFFC62828),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("รายจ่ายรวม", fontSize = 11.sp, color = Color(0xFFC62828))
                        }
                        Text(
                            text = String.format("%,.2f ฿", totalExpense),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Filter chips row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedFilterType == null,
                    onClick = { selectedFilterType = null },
                    label = { Text("ทั้งหมด (${historyList.size})") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterType == FILTER_PENDING,
                    onClick = { selectedFilterType = if (selectedFilterType == FILTER_PENDING) null else FILTER_PENDING },
                    label = { Text("รอตรวจสอบ ($pendingCount)") },
                    modifier = Modifier.testTag("filter_pending")
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterType == "RECEIPT",
                    onClick = { selectedFilterType = if (selectedFilterType == "RECEIPT") null else "RECEIPT" },
                    label = { Text("ใบเสร็จ (RECEIPT)") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterType == "PAYMENT_VOUCHER",
                    onClick = { selectedFilterType = if (selectedFilterType == "PAYMENT_VOUCHER") null else "PAYMENT_VOUCHER" },
                    label = { Text("ใบสำคัญจ่าย (VOUCHER)") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterType == "UTILITY_BILL",
                    onClick = { selectedFilterType = if (selectedFilterType == "UTILITY_BILL") null else "UTILITY_BILL" },
                    label = { Text("บิลสาธารณูปโภค (UTILITY)") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterType == "ADVANCE_DEPOSIT",
                    onClick = { selectedFilterType = if (selectedFilterType == "ADVANCE_DEPOSIT") null else "ADVANCE_DEPOSIT" },
                    label = { Text("มัดจำล่วงหน้า (DEPOSIT)") }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "ยังไม่มีประวัติเอกสารในหมวดหมู่นี้",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onNavigateToScan) {
                        Text("ไปที่หน้าสแกนเอกสาร")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredList, key = { it.id }) { entity ->
                    HistoryItemCard(
                        entity = entity,
                        onClick = { selectedEntityForDetail = entity },
                        onDelete = { confirmDelete = entity },
                        onVoid = { confirmVoid = entity }
                    )
                }
            }
        }
    }

    confirmDelete?.let { e ->
        ConfirmDeleteDialog(
            entity = e,
            onConfirm = {
                viewModel.deleteHistory(e.id)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null }
        )
    }
    confirmVoid?.let { e ->
        VoidDocumentDialog(
            entity = e,
            onConfirm = { reason ->
                viewModel.voidDocument(e.id, reason)
                confirmVoid = null
                selectedEntityForDetail = null
            },
            onDismiss = { confirmVoid = null }
        )
    }
    actionMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearActionMessage() },
            confirmButton = { TextButton(onClick = { viewModel.clearActionMessage() }) { Text("ตกลง") } },
            text = { Text(msg, fontSize = 14.sp) }
        )
    }

    // Detail Dialog when item is clicked
    if (selectedEntityForDetail != null) {
        val entity = selectedEntityForDetail!!
        val doc = remember(entity) { entity.toAccountingDocument() }

        AlertDialog(
            onDismissRequest = { selectedEntityForDetail = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.loadFromHistory(entity)
                        selectedEntityForDetail = null
                        onNavigateToScan()
                    }
                ) {
                    Text(if (entity.sampleId == null && entity.documentStatus() == DocumentStatus.PENDING) "ตรวจสอบ & ยืนยัน" else "เปิดในหน้าสแกน")
                }
            },
            dismissButton = {
                Row {
                    if (entity.canVoid()) {
                        TextButton(onClick = { confirmVoid = entity }, modifier = Modifier.testTag("detail_void_button")) {
                            Text("ยกเลิกรายการ", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { selectedEntityForDetail = null }) {
                        Text("ปิด")
                    }
                }
            },
            title = {
                Text(
                    text = "รายละเอียดเอกสาร ${entity.documentNo ?: ""}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    DocumentStatusBadge(statusCode = entity.status, isSample = entity.sampleId != null)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (entity.documentStatus() == DocumentStatus.VOIDED) {
                        VoidedInfoCard(entity)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (entity.imagePath != null) {
                        AsyncImage(
                            model = File(entity.imagePath),
                            contentDescription = "รูปเอกสารต้นฉบับ",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    ExtractedDocumentCard(doc = doc)
                    Spacer(modifier = Modifier.height(12.dp))
                    JsonViewer(jsonString = entity.rawJson)
                }
            }
        )
    }
}

@Composable
private fun HistoryItemCard(
    entity: ExtractedDocumentEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onVoid: () -> Unit
) {
    val isIncome = entity.transactionType.equals("INCOME", ignoreCase = true)
    val voided = entity.documentStatus() == DocumentStatus.VOIDED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("history_item_${entity.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocumentTypeBadge(docTypeCode = entity.documentType)
                    TransactionTypeBadge(typeCode = entity.transactionType)
                }
                when {
                    // Never counted: may be deleted (after a confirmation).
                    entity.canDelete() -> IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("delete_button_${entity.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "ลบ",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    // Counted: can only be cancelled with a reason.
                    entity.canVoid() -> TextButton(
                        onClick = onVoid,
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("void_button_${entity.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ยกเลิกรายการ", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            DocumentStatusBadge(statusCode = entity.status, isSample = entity.sampleId != null)
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.sellerName ?: entity.customerName ?: "ไม่ระบุชื่อคู่ค้า",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "เลขที่: ${entity.documentNo ?: "-"}  •  วันที่: ${entity.date ?: "-"}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = entity.totalAmount?.let { String.format("%,.2f ฿", it) } ?: "-",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textDecoration = if (voided) TextDecoration.LineThrough else null,
                    color = when {
                        voided -> MaterialTheme.colorScheme.onSurfaceVariant
                        isIncome -> Color(0xFF2E7D32)
                        else -> Color(0xFFC62828)
                    }
                )
            }
            if (voided) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "ยกเลิกแล้ว: ${entity.voidReason ?: "-"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val FILTER_PENDING = "__PENDING__"
