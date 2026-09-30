package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.documentStatus
import com.example.data.model.DocumentStatus
import com.example.ui.components.rememberFinanceOverview
import com.example.ui.viewmodel.AccountantViewModel
import com.example.ui.viewmodel.BookingViewModel
import com.example.util.ReportPeriod
import java.util.Locale

/** Where a home-menu button leads. */
enum class HomeDestination { SCAN, BOOKINGS, WALLETS, DAY_CLOSE, DOCUMENTS, MONEY, HISTORY, IMPORT }

private data class HomeAction(val destination: HomeDestination, val title: String, val hint: String, val icon: ImageVector)

private val ACTIONS = listOf(
    HomeAction(HomeDestination.SCAN, "สแกนบิล / สลิป", "ถ่ายรูปแล้วบันทึกรายรับรายจ่าย", Icons.Default.CameraAlt),
    HomeAction(HomeDestination.BOOKINGS, "การจอง", "รับจอง รับเงิน เช็คอิน เช็คเอาท์", Icons.Default.Hotel),
    HomeAction(HomeDestination.WALLETS, "จ่ายเงิน", "จ่ายค่าใช้จ่ายจากกระเป๋า", Icons.Default.Payments),
    HomeAction(HomeDestination.DAY_CLOSE, "ปิดยอดวันนี้", "รายได้ eZee และนับเงินสด", Icons.Default.EventAvailable),
    HomeAction(HomeDestination.DOCUMENTS, "ออกเอกสาร", "ใบเสร็จ ใบแจ้งหนี้ ใบกำกับภาษี", Icons.Default.Receipt),
    HomeAction(HomeDestination.MONEY, "ดูยอดเงิน", "รายรับ รายจ่าย กำไร กระเป๋า", Icons.Default.Assessment),
    HomeAction(HomeDestination.HISTORY, "ประวัติเอกสาร", "ดู ตรวจ หรือยกเลิกเอกสาร", Icons.Default.History),
    HomeAction(HomeDestination.IMPORT, "นำเข้าไฟล์", "PDF eZee, Excel, หลายรูป", Icons.Default.UploadFile)
)

private fun baht(v: Double) = String.format(Locale.US, "%,.0f", v)

/**
 * First screen: a short summary of this month, what needs doing now, and one big button per job.
 * The detailed figures stay on the money screen ([HomeDestination.MONEY]).
 */
@Composable
fun HomeScreen(
    viewModel: AccountantViewModel,
    bookingVm: BookingViewModel,
    onOpen: (HomeDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val documents by viewModel.historyList.collectAsStateWithLifecycle()
    val business by bookingVm.business.collectAsStateWithLifecycle()
    val walletViews by bookingVm.wallets.collectAsStateWithLifecycle()
    val today = remember(documents) { ReportPeriod.today() }
    val month = rememberFinanceOverview(bookingVm, documents, ReportPeriod.THIS_MONTH, today)
    val pendingDocs = remember(documents) {
        documents.count { it.sampleId == null && it.documentStatus() == DocumentStatus.PENDING }
    }
    val pendingTransfers = walletViews.count { kotlin.math.abs(it.toTransfer) >= 0.005 }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("home_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onOpen(HomeDestination.MONEY) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    business.name.ifBlank { "เดือนนี้" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (business.name.isNotBlank()) {
                    Text("เดือนนี้", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    SummaryFigure("รายรับ", month.income, Modifier.weight(1f))
                    SummaryFigure("รายจ่าย", month.expense, Modifier.weight(1f))
                    SummaryFigure(if (month.profit >= 0) "กำไร" else "ขาดทุน", month.profit, Modifier.weight(1f))
                }
            }
        }

        if (pendingDocs > 0) {
            TodoRow("มี $pendingDocs เอกสารรอตรวจ — ยังไม่นับในยอด") { onOpen(HomeDestination.HISTORY) }
        }
        if (pendingTransfers > 0) {
            TodoRow("มี $pendingTransfers กระเป๋าที่ต้องโอนใน MAKE") { onOpen(HomeDestination.WALLETS) }
        }

        Text("จะทำอะไร?", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        ACTIONS.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { a -> ActionTile(a, Modifier.weight(1f)) { onOpen(a.destination) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryFigure(label: String, amount: Double, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            baht(amount),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun TodoRow(text: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun ActionTile(action: HomeAction, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .heightIn(min = 124.dp)
            .clickable(onClick = onClick)
            .testTag("home_${action.destination.name.lowercase()}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(action.icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(action.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                action.hint,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
