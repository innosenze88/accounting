package com.example.ui.screens

import com.example.ui.components.appCardBorder
import com.example.ui.components.BackupReminder
import com.example.ui.components.FinanceOverviewCard
import com.example.ui.viewmodel.BookingViewModel
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.example.util.ReportPeriod
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.ui.viewmodel.BackupViewModel

@Composable
fun DashboardScreen(
    viewModel: AccountantViewModel,
    onNavigateToScan: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToBooking: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val allDocuments by viewModel.historyList.collectAsStateWithLifecycle()
    // Every figure on the dashboard is built ONLY from real documents a person has verified.
    // Samples and PENDING/REJECTED documents are never counted.
    var period by rememberSaveable { mutableStateOf(ReportPeriod.THIS_MONTH) }
    // Recomputed whenever the data changes, so the dashboard follows the calendar day.
    val today = remember(allDocuments) { ReportPeriod.today() }
    val historyList = remember(allDocuments, period, today) {
        allDocuments.filter { it.countsInAccounting() && period.contains(it.date, it.createdAt, today) }
    }
    val pendingCount = remember(allDocuments) {
        allDocuments.count { it.sampleId == null && it.documentStatus() == DocumentStatus.PENDING }
    }
    var selectedEntityForDetail by remember { mutableStateOf<ExtractedDocumentEntity?>(null) }
    // Figures from scanned documents only (tax, document types) are details, folded away by default
    // so the page shows one set of totals.
    var showDocDetails by rememberSaveable { mutableStateOf(false) }

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
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    border = appCardBorder()
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
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "ยังไม่ถูกนับในยอดด้านล่าง — แตะเพื่อไปตรวจและยืนยัน",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        item {
            Column {
                Text(
                    text = "ยอดเงิน",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = ReportPeriod.label(period, today),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Period filter: today / this month / last month / all
        item {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_period_filter"),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(ReportPeriod.entries) { p ->
                    FilterChip(
                        selected = period == p,
                        onClick = { period = p },
                        label = { Text(p.titleTh, fontSize = 13.sp) },
                        modifier = Modifier.testTag("period_${p.name}")
                    )
                }
            }
        }

        item {
            BackupReminder(vm = composeViewModel<BackupViewModel>(), onOpenSettings = onNavigateToSettings)
        }

        item {
            FinanceOverviewCard(
                vm = composeViewModel<BookingViewModel>(),
                documents = allDocuments,
                period = period,
                today = today,
                onOpenWallets = onNavigateToBooking
            )
        }

        item {
            OutlinedButton(
                onClick = { showDocDetails = !showDocDetails },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_doc_details_toggle"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    if (showDocDetails) "ซ่อนรายละเอียดจากเอกสารที่สแกน"
                    else "ดูรายละเอียดจากเอกสารที่สแกน (ภาษี, ประเภทเอกสาร)",
                    fontSize = 13.sp
                )
            }
        }

        if (showDocDetails) {
        item {
            Text(
                text = "ตัวเลขชุดนี้นับเฉพาะเอกสารที่สแกนและยืนยันแล้ว ไม่รวมยอดจองและรายได้ eZee จึงไม่เท่ากับยอดด้านบน",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Net Cash Flow Card
        item {
            val isSurplus = netBalance >= 0
            val netColor = if (isSurplus) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
            val netBg = if (isSurplus) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_net_balance_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = netBg),
                border = appCardBorder()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "เงินสุทธิจากเอกสารที่ยืนยัน",
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
                        text = "รายรับจากเอกสารลบรายจ่ายจากเอกสาร ไม่รวมยอดจอง, eZee และกระเป๋าเงิน",
                        fontSize = 11.sp,
                        color = netColor.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Two full-width figures are easier to scan on a phone than a compressed 3-column grid.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatMetricCard(
                    title = "รายรับรวม",
                    amount = String.format("%,.2f ฿", totalIncome),
                    subtitle = "$incomeCount เอกสาร",
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().testTag("dashboard_stat_income")
                )

                StatMetricCard(
                    title = "รายจ่ายรวม",
                    amount = String.format("%,.2f ฿", totalExpense),
                    subtitle = "$expenseCount เอกสาร",
                    icon = Icons.AutoMirrored.Filled.TrendingDown,
                    accentColor = MaterialTheme.colorScheme.error,
                    backgroundColor = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().testTag("dashboard_stat_expense")
                )
            }
        }

        // Ratio Bar (Income vs Expense)
        item {
            val incomeColor = MaterialTheme.colorScheme.secondary
            val expenseColor = MaterialTheme.colorScheme.error
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_ratio_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = appCardBorder()
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
                                    .background(incomeColor)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(expenseRatio.coerceAtLeast(0.01f))
                                    .height(12.dp)
                                    .background(expenseColor)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
                                    .background(incomeColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "รายรับ ${String.format("%.1f%%", if (totalFlow > 0) incomeRatio * 100 else 0.0)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = incomeColor
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(expenseColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "รายจ่าย ${String.format("%.1f%%", if (totalFlow > 0) expenseRatio * 100 else 0.0)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = expenseColor
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = appCardBorder()
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                border = appCardBorder()
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
                            text = "สรุปภาษีและเลขประจำตัวผู้เสียภาษี",
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
                            Text("ยอดก่อนภาษี", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format("%,.2f ฿", totalSubtotal), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("ยอดรวมทั้งสิ้น", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format("%,.2f ฿", totalOverallAmount), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ภาษี VAT รวม", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            String.format("%,.2f ฿", totalVat),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("dashboard_stat_vat")
                        )
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
                            text = "เอกสารที่มีเลขประจำตัวผู้เสียภาษี",
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    border = appCardBorder()
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = appCardBorder()
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
                fontSize = 19.sp,
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
    val colors = MaterialTheme.colorScheme
    val (icon, tint) = when (stat.type) {
        DocumentType.RECEIPT -> Icons.Default.Receipt to colors.secondary
        DocumentType.PAYMENT_VOUCHER -> Icons.Default.Payments to colors.error
        DocumentType.UTILITY_BILL -> Icons.Default.ElectricBolt to colors.primary
        DocumentType.ADVANCE_DEPOSIT -> Icons.Default.AccountBalance to colors.tertiary
        DocumentType.TRANSFER_SLIP -> Icons.Default.SwapHoriz to colors.secondary
        DocumentType.OTHER -> Icons.Default.Description to colors.onSurfaceVariant
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
    val amountColor = if (isIncome) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("dashboard_recent_item_${entity.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = appCardBorder()
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
