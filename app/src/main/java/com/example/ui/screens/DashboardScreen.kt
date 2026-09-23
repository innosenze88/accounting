package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.countsInAccounting
import com.example.data.local.documentStatus
import com.example.data.model.DocumentStatus
import com.example.data.model.DocumentType
import com.example.ui.components.DocumentTypeBadge
import com.example.ui.components.ExtractedDocumentCard
import com.example.ui.components.JsonViewer
import com.example.ui.components.TransactionTypeBadge
import com.example.ui.viewmodel.AccountantViewModel

@Composable
fun DashboardScreen(
    viewModel: AccountantViewModel,
    onNavigateToScan: () -> Unit,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allDocuments by viewModel.historyList.collectAsStateWithLifecycle()
    // Every figure on the dashboard is built ONLY from real documents a person has verified.
    // Samples and PENDING/REJECTED documents are never counted.
    val historyList = remember(allDocuments) { allDocuments.filter { it.countsInAccounting() } }
    val pendingCount = remember(allDocuments) {
        allDocuments.count { it.sampleId == null && it.documentStatus() == DocumentStatus.PENDING }
    }
    var selectedEntityForDetail by remember { mutableStateOf<ExtractedDocumentEntity?>(null) }

    // Statistical Calculations
    val totalIncome = remember(historyList) {
        historyList.filter { it.transactionType.equals("INCOME", ignoreCase = true) }
            .sumOf { it.totalAmount ?: 0.0 }
    }
    val incomeCount = remember(historyList) {
        historyList.count { it.transactionType.equals("INCOME", ignoreCase = true) }
    }

    val totalExpense = remember(historyList) {
        historyList.filter { it.transactionType.equals("EXPENSE", ignoreCase = true) }
            .sumOf { it.totalAmount ?: 0.0 }
    }
    val expenseCount = remember(historyList) {
        historyList.count { it.transactionType.equals("EXPENSE", ignoreCase = true) }
    }

    val netBalance = remember(totalIncome, totalExpense) {
        totalIncome - totalExpense
    }

    val totalVat = remember(historyList) {
        historyList.sumOf { it.vatAmount ?: 0.0 }
    }

    val totalSubtotal = remember(historyList) {
        historyList.sumOf { it.subtotal ?: 0.0 }
    }

    val totalOverallAmount = remember(historyList) {
        historyList.sumOf { it.totalAmount ?: 0.0 }
    }

    val docsWithTaxIdCount = remember(historyList) {
        historyList.count { !it.sellerTaxId.isNullOrBlank() || !it.customerTaxId.isNullOrBlank() }
    }

    val recentDocuments = remember(historyList) {
        historyList.take(4)
    }

    // Breakdown by Document Type
    val typeBreakdown = remember(historyList) {
        DocumentType.entries.map { type ->
            val matching = historyList.filter { it.documentType.equals(type.code, ignoreCase = true) }
            val count = matching.size
            val sumAmount = matching.sumOf { it.totalAmount ?: 0.0 }
            TypeStat(type = type, count = count, totalAmount = sumAmount)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (pendingCount > 0) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToHistory)
                        .testTag("dashboard_pending_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "มี $pendingCount เอกสารรอตรวจสอบ",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6D4C00)
                            )
                            Text(
                                text = "ยังไม่ถูกนับในยอดด้านล่าง — แตะเพื่อไปตรวจและยืนยัน",
                                fontSize = 12.sp,
                                color = Color(0xFF6D4C00)
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFF6D4C00)
                        )
                    }
                }
            }
        }

        item {
            // Dashboard Title Banner
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "แดชบอร์ดสรุปผลบัญชี",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "สรุปยอดรายรับ-รายจ่าย ภาษี และสถิติเอกสาร",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.testTag("dashboard_total_docs_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dashboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${historyList.size} เอกสาร",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // Quick Action Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onNavigateToScan,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_btn_quick_scan"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("สแกนเอกสารใหม่", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onNavigateToHistory,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_btn_all_history"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ประวัติทั้งหมด", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Net Cash Flow Card
        item {
            val isSurplus = netBalance >= 0
            val netColor = if (isSurplus) Color(0xFF1B5E20) else Color(0xFFC62828)
            val netBg = if (isSurplus) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_net_balance_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = netBg)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ยอดเงินคงเหลือสุทธิ (Net Cash Flow)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = netColor
                        )
                        Surface(
                            shape = CircleShape,
                            color = netColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isSurplus) "กระแสเงินสดเป็นบวก" else "รายจ่ายสูงกว่ารายรับ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = netColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = String.format("%s%,.2f ฿", if (isSurplus) "+ " else "- ", kotlin.math.abs(netBalance)),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = netColor,
                        modifier = Modifier.testTag("dashboard_net_balance_value")
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "คำนวณจากรายรับ (${String.format("%,.2f ฿", totalIncome)}) ลบด้วยรายจ่าย (${String.format("%,.2f ฿", totalExpense)})",
                        fontSize = 11.sp,
                        color = netColor.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Primary Stats 3-Grid Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Income Card
                StatMetricCard(
                    title = "รายรับรวม",
                    amount = String.format("%,.2f ฿", totalIncome),
                    subtitle = "$incomeCount เอกสาร",
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    accentColor = Color(0xFF2E7D32),
                    backgroundColor = Color(0xFFE8F5E9),
                    modifier = Modifier.weight(1f).testTag("dashboard_stat_income")
                )

                // Expense Card
                StatMetricCard(
                    title = "รายจ่ายรวม",
                    amount = String.format("%,.2f ฿", totalExpense),
                    subtitle = "$expenseCount เอกสาร",
                    icon = Icons.AutoMirrored.Filled.TrendingDown,
                    accentColor = Color(0xFFC62828),
                    backgroundColor = Color(0xFFFFEBEE),
                    modifier = Modifier.weight(1f).testTag("dashboard_stat_expense")
                )

                // VAT Card
                StatMetricCard(
                    title = "ภาษี VAT 7%",
                    amount = String.format("%,.2f ฿", totalVat),
                    subtitle = "รวมภาษีมูลค่าเพิ่ม",
                    icon = Icons.Default.Percent,
                    accentColor = Color(0xFF512DA8),
                    backgroundColor = Color(0xFFEDE7F6),
                    modifier = Modifier.weight(1f).testTag("dashboard_stat_vat")
                )
            }
        }

        // Ratio Bar (Income vs Expense)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_ratio_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "สัดส่วนรายรับเทียบรายจ่าย",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val totalFlow = totalIncome + totalExpense
                    val incomeRatio = if (totalFlow > 0) (totalIncome / totalFlow).toFloat() else 0.5f
                    val expenseRatio = if (totalFlow > 0) (totalExpense / totalFlow).toFloat() else 0.5f

                    // Two-tone bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        if (totalFlow > 0) {
                            Box(
                                modifier = Modifier
                                    .weight(incomeRatio.coerceAtLeast(0.01f))
                                    .height(12.dp)
                                    .background(Color(0xFF2E7D32))
                            )
                            Box(
                                modifier = Modifier
                                    .weight(expenseRatio.coerceAtLeast(0.01f))
                                    .height(12.dp)
                                    .background(Color(0xFFC62828))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.LightGray.copy(alpha = 0.5f))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2E7D32))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "รายรับ ${String.format("%.1f%%", if (totalFlow > 0) incomeRatio * 100 else 0.0)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF2E7D32)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFC62828))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "รายจ่าย ${String.format("%.1f%%", if (totalFlow > 0) expenseRatio * 100 else 0.0)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFC62828)
                            )
                        }
                    }
                }
            }
        }

        // Breakdown by Document Type
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_type_breakdown_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "การจำแนกประเภทเอกสารบัญชี (5 ประเภท)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "ตามมาตรฐาน",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val maxCount = typeBreakdown.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1

                    typeBreakdown.forEachIndexed { index, stat ->
                        TypeBreakdownRow(
                            stat = stat,
                            maxCount = maxCount
                        )
                        if (index < typeBreakdown.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }

        // Tax & Compliance Summary Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_tax_compliance_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "สรุปข้อมูลภาษีและการตรวจสอบ Tax ID",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("ยอดรวมก่อนภาษี (Subtotal)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format("%,.2f ฿", totalSubtotal), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("ยอดรวมทั้งสิ้น (Grand Total)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format("%,.2f ฿", totalOverallAmount), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "เอกสารที่มีเลขประจำตัวผู้เสียภาษี 13 หลัก",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "$docsWithTaxIdCount / ${historyList.size} ฉบับ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Recent Scanned Documents Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "เอกสารล่าสุด (${recentDocuments.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (historyList.size > 4) {
                    TextButton(onClick = onNavigateToHistory) {
                        Text("ดูทั้งหมด (${historyList.size})", fontSize = 12.sp)
                    }
                }
            }
        }

        if (recentDocuments.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dashboard_empty_state_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "ยังไม่มีเอกสารในระบบ",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "แดชบอร์ดแสดงเฉพาะเอกสารที่ตรวจสอบและยืนยันแล้ว เริ่มต้นด้วยการสแกนบิลจริง แล้วกดยืนยันหลังตรวจข้อมูล",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onNavigateToScan,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("สแกนเอกสาร", fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            items(recentDocuments) { entity ->
                RecentDocumentCard(
                    entity = entity,
                    onClick = { selectedEntityForDetail = entity },
                    onLoadToScanner = {
                        viewModel.loadFromHistory(entity)
                        onNavigateToScan()
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Detail dialog when user taps a recent document
    selectedEntityForDetail?.let { entity ->
        val doc = remember(entity) { entity.toAccountingDocument() }
        AlertDialog(
            onDismissRequest = { selectedEntityForDetail = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "รายละเอียดเอกสาร",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    DocumentTypeBadge(docTypeCode = entity.documentType)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ExtractedDocumentCard(doc = doc)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "JSON ที่บันทึกไว้ใน Room Database:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    JsonViewer(
                        jsonString = entity.rawJson,
                        modifier = Modifier.height(160.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.loadFromHistory(entity)
                        selectedEntityForDetail = null
                        onNavigateToScan()
                    }
                ) {
                    Text("เปิดดูในหน้าสแกน")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEntityForDetail = null }) {
                    Text("ปิด")
                }
            }
        )
    }
}

data class TypeStat(
    val type: DocumentType,
    val count: Int,
    val totalAmount: Double
)

@Composable
private fun StatMetricCard(
    title: String,
    amount: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = amount,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = accentColor.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun TypeBreakdownRow(
    stat: TypeStat,
    maxCount: Int
) {
    val (icon, tint) = when (stat.type) {
        DocumentType.RECEIPT -> Icons.Default.Receipt to Color(0xFF1B5E20)
        DocumentType.PAYMENT_VOUCHER -> Icons.Default.Payments to Color(0xFFC62828)
        DocumentType.UTILITY_BILL -> Icons.Default.ElectricBolt to Color(0xFF1565C0)
        DocumentType.ADVANCE_DEPOSIT -> Icons.Default.AccountBalance to Color(0xFF6A1B9A)
        DocumentType.OTHER -> Icons.Default.Description to Color(0xFF455A64)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stat.type.titleTh,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stat.type.code,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = String.format("%,.2f ฿", stat.totalAmount),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${stat.count} ฉบับ",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        val progress = if (maxCount > 0) stat.count.toFloat() / maxCount.toFloat() else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = tint,
            trackColor = tint.copy(alpha = 0.12f)
        )
    }
}

@Composable
private fun RecentDocumentCard(
    entity: ExtractedDocumentEntity,
    onClick: () -> Unit,
    onLoadToScanner: () -> Unit
) {
    val isIncome = entity.transactionType.equals("INCOME", ignoreCase = true)
    val amountColor = if (isIncome) Color(0xFF2E7D32) else Color(0xFFC62828)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("dashboard_recent_item_${entity.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocumentTypeBadge(docTypeCode = entity.documentType)
                    TransactionTypeBadge(typeCode = entity.transactionType)
                }
                Text(
                    text = String.format("%,.2f ฿", entity.totalAmount ?: 0.0),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val partyName = if (isIncome) entity.customerName ?: entity.sellerName else entity.sellerName
            Text(
                text = partyName ?: "ไม่ระบุชื่อคู่ค้า / ผู้ขาย",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "เลขที่: ${entity.documentNo ?: "-"} • วันที่: ${entity.date ?: "-"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onLoadToScanner,
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("ตรวจดู", fontSize = 11.sp)
                }
            }
        }
    }
}
