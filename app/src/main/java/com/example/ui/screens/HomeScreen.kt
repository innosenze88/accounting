package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.documentStatus
import com.example.data.model.DocumentStatus
import com.example.ui.components.AppCard
import com.example.ui.components.SectionTitle
import com.example.ui.components.rememberFinanceOverview
import com.example.ui.theme.AmberDot
import com.example.ui.theme.TileNavy
import com.example.ui.theme.TileNavyText
import com.example.ui.theme.ToneAmber
import com.example.ui.theme.ToneAmberFill
import com.example.ui.theme.ToneBlue
import com.example.ui.theme.ToneBlueFill
import com.example.ui.theme.ToneSlate
import com.example.ui.theme.ToneSlateFill
import com.example.ui.viewmodel.AccountantViewModel
import com.example.ui.viewmodel.BookingViewModel
import com.example.util.ReportPeriod
import java.util.Locale

/** Where a home-menu button leads. */
enum class HomeDestination { SCAN, BOOKINGS, WALLETS, DAY_CLOSE, DOCUMENTS, MONEY, HISTORY, IMPORT }

private data class HomeTool(
    val destination: HomeDestination,
    val title: String,
    val hint: String,
    val icon: ImageVector,
    val fill: Color,
    val tint: Color
)

private val TOOLS = listOf(
    HomeTool(HomeDestination.WALLETS, "จ่ายเงิน", "จ่ายค่าใช้จ่ายจากกระเป๋า หรือโอนเงิน", Icons.Default.AccountBalanceWallet, ToneAmberFill, ToneAmber),
    HomeTool(HomeDestination.DOCUMENTS, "ออกเอกสาร", "ใบเสร็จ ใบแจ้งหนี้ ใบกำกับภาษี", Icons.Default.Description, ToneBlueFill, ToneBlue),
    HomeTool(HomeDestination.MONEY, "ดูยอดเงิน", "รายรับ รายจ่าย กำไร และกระเป๋า", Icons.Default.Assessment, Color(0xFFE7F3ED), Color(0xFF287354)),
    HomeTool(HomeDestination.HISTORY, "ประวัติเอกสาร", "ตรวจ ยืนยัน หรือยกเลิกเอกสาร", Icons.Default.History, ToneSlateFill, ToneSlate),
    HomeTool(HomeDestination.IMPORT, "นำเข้าไฟล์", "PDF eZee, Excel, CSV หรือหลายรูป", Icons.Default.UploadFile, Color(0xFFE7F3ED), Color(0xFF287354))
)

private fun baht(v: Double) = String.format(Locale.US, "%,.0f", v)

/**
 * First screen, laid out like the web app: this month's summary, what needs checking,
 * the jobs done most often, then the other tools. Detailed figures stay on the money screen.
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
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .testTag("home_screen")
    ) {
        SectionTitle(
            title = "สรุปประจำเดือน",
            subtitle = listOfNotNull(business.name.ifBlank { null }, "ยอดที่ยืนยันแล้ว · เดือนนี้").joinToString(" · "),
            trailing = {
                Text(
                    "บาท (฿)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        )
        Spacer(Modifier.height(10.dp))
        AppCard(onClick = { onOpen(HomeDestination.MONEY) }, modifier = Modifier.testTag("home_summary")) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = 4.dp, vertical = 15.dp)
            ) {
                SummaryFigure("รายรับ", month.income, Color(0xFF488968), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                SummaryFigure("รายจ่าย", month.expense, AmberDot, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                SummaryFigure(
                    if (month.profit >= 0) "กำไร" else "ขาดทุน",
                    month.profit,
                    if (month.profit >= 0) Color(0xFF4C8E6A) else MaterialTheme.colorScheme.error,
                    if (month.profit >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF498568), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "คำนวณจากรายการที่ยืนยันแล้ว · แตะเพื่อดูรายละเอียด",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (pendingDocs > 0 || pendingTransfers > 0) {
            Spacer(Modifier.height(17.dp))
            SectionTitle(
                title = "รายการที่ควรตรวจ",
                trailing = {
                    Text(
                        "${pendingDocs + pendingTransfers} รายการ",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ToneAmber,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(ToneAmberFill)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            )
            Spacer(Modifier.height(8.dp))
            AppCard(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                borderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
            ) {
                if (pendingDocs > 0) {
                    NoticeRow(
                        Icons.Default.ErrorOutline,
                        "เอกสารรอตรวจ $pendingDocs รายการ",
                        "ยังไม่นับรวมในยอด",
                        Modifier.testTag("home_pending_docs")
                    ) { onOpen(HomeDestination.HISTORY) }
                }
                if (pendingDocs > 0 && pendingTransfers > 0) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                    )
                }
                if (pendingTransfers > 0) {
                    NoticeRow(
                        Icons.Default.AccountBalanceWallet,
                        "กระเป๋ารอโอนใน MAKE $pendingTransfers รายการ",
                        "แตะเพื่อดูว่าต้องโอนเท่าไร",
                        Modifier.testTag("home_pending_transfers")
                    ) { onOpen(HomeDestination.WALLETS) }
                }
            }
        }

        Spacer(Modifier.height(19.dp))
        SectionTitle(
            title = "งานที่ทำบ่อย",
            trailing = { Text("ทางลัด", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        )
        Spacer(Modifier.height(10.dp))
        AppCard(
            onClick = { onOpen(HomeDestination.SCAN) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.testTag("home_scan")
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "สแกนบิล / สลิป",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "ถ่ายรูปหรือเลือกรูป แล้วบันทึกรายรับรายจ่าย",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ShortcutTile(
                icon = Icons.Default.EventAvailable,
                title = "ปิดยอดวันนี้",
                hint = "รายได้ eZee และนับเงินสด",
                container = TileNavy,
                border = Color(0xFF203C59),
                iconFill = Color.White.copy(alpha = 0.10f),
                iconTint = Color(0xFFD9E8DC),
                titleColor = Color.White,
                hintColor = TileNavyText,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("home_day_close")
            ) { onOpen(HomeDestination.DAY_CLOSE) }
            ShortcutTile(
                icon = Icons.Default.CalendarMonth,
                title = "การจอง",
                hint = "รับจอง รับเงิน เช็คอิน เช็คเอาท์",
                container = MaterialTheme.colorScheme.surface,
                border = MaterialTheme.colorScheme.outlineVariant,
                iconFill = ToneBlueFill,
                iconTint = ToneBlue,
                titleColor = MaterialTheme.colorScheme.onSurface,
                hintColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("home_bookings")
            ) { onOpen(HomeDestination.BOOKINGS) }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(
            title = "เครื่องมืออื่น ๆ",
            trailing = {
                Text("ทั้งหมด ${TOOLS.size} รายการ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        )
        Spacer(Modifier.height(10.dp))
        AppCard {
            TOOLS.forEachIndexed { index, tool ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToolRow(tool) { onOpen(tool.destination) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryFigure(label: String, amount: Double, dot: Color, amountColor: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            baht(amount),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = (-0.5).sp,
            color = amountColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NoticeRow(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(hint, fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ShortcutTile(
    icon: ImageVector,
    title: String,
    hint: String,
    container: Color,
    border: Color,
    iconFill: Color,
    iconTint: Color,
    titleColor: Color,
    hintColor: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    AppCard(onClick = onClick, containerColor = container, borderColor = border, modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .heightIn(min = 91.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(iconFill),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.padding(top = 8.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = titleColor)
                Text(hint, fontSize = 10.sp, lineHeight = 15.sp, color = hintColor)
            }
        }
    }
}

@Composable
private fun ToolRow(tool: HomeTool, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag("home_${tool.destination.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tool.fill),
            contentAlignment = Alignment.Center
        ) {
            Icon(tool.icon, contentDescription = null, tint = tool.tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tool.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(tool.hint, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}
