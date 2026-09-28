package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.dashboard.CountedDoc
import com.example.data.dashboard.FinanceOverview
import com.example.data.dashboard.FinanceOverviewCalc
import com.example.data.dashboard.YearPlan
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.countsInAccounting
import com.example.ui.viewmodel.AccountantViewModel
import com.example.ui.viewmodel.BookingViewModel
import com.example.util.ReportPeriod
import java.util.Locale

private val IncomeGreen = Color(0xFF2E7D32)
private val ExpenseRed = Color(0xFFC62828)
private val Warn = Color(0xFFE65100)

private fun baht(v: Double) = String.format(Locale.US, "%,.0f", v)
private fun pct(p: Double) = if (p % 1.0 == 0.0) p.toLong().toString() else String.format(Locale.US, "%.1f", p)

/** Verified documents only (samples, pending, rejected and voided never count). */
fun toCountedDocs(all: List<ExtractedDocumentEntity>): List<CountedDoc> = all.filter { it.countsInAccounting() }.map {
    CountedDoc(
        id = it.id,
        isIncome = it.transactionType.equals("INCOME", ignoreCase = true),
        isExpense = it.transactionType.equals("EXPENSE", ignoreCase = true),
        amount = it.totalAmount ?: 0.0,
        date = it.date,
        createdAt = it.createdAt,
        isEzeeReport = it.documentNo?.startsWith(AccountantViewModel.REPORT_DOC_PREFIX) == true
    )
}

/**
 * Dashboard money overview: (1) income (2) expenses (3) deposits held (4) still to collect at check-out
 * (5) the wallets with their % (must add up to 100) and (6) what the whole year should look like.
 */
@Composable
fun FinanceOverviewCard(
    vm: BookingViewModel,
    documents: List<ExtractedDocumentEntity>,
    period: ReportPeriod,
    today: String,
    onOpenWallets: () -> Unit
) {
    val bookings by vm.bookings.collectAsStateWithLifecycle()
    val wallets by vm.allWallets.collectAsStateWithLifecycle()
    val walletViews by vm.wallets.collectAsStateWithLifecycle()
    val txns by vm.txns.collectAsStateWithLifecycle()
    val closes by vm.dayCloses.collectAsStateWithLifecycle()
    val business by vm.business.collectAsStateWithLifecycle()

    val o: FinanceOverview = remember(documents, bookings, wallets, txns, closes, business, period, today) {
        FinanceOverviewCalc.build(
            docs = toCountedDocs(documents),
            txns = txns,
            wallets = wallets,
            bookings = bookings.map { it.booking to it.money },
            closedDays = closes.map { it.date }.toSet(),
            period = period,
            today = today,
            profitWalletIds = business.profitWalletIds,
            // Slips attached to a booking payment are already counted by the booking.
            linkedSlipIds = com.example.data.booking.BookingRules.linkedSlipIds(bookings.flatMap { it.payments })
        )
    }
    val pendingTransfers = walletViews.count { kotlin.math.abs(it.toTransfer) >= 0.005 }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("finance_overview")) {
        // (1) (2) income, expenses, profit of the period
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BigTile("รายรับ", o.income, IncomeGreen, Color(0xFFE8F5E9), Modifier.weight(1f))
            BigTile("รายจ่าย", o.expense, ExpenseRed, Color(0xFFFFEBEE), Modifier.weight(1f))
            BigTile(if (o.profit >= 0) "กำไร" else "ขาดทุน", o.profit, if (o.profit >= 0) IncomeGreen else ExpenseRed, Color(0xFFE3F2FD), Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("ที่มาของรายรับ", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Line("จองตรง (เช็คเอาท์ / ชำระ / มัดจำที่ไม่คืน)", o.incomeBookings)
                Line("eZee (walk-in / OTA จากการปิดยอด)", o.incomeEzee)
                Line("เอกสารรายรับที่ยืนยันแล้ว", o.incomeDocuments)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("ที่ไปของรายจ่าย", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Line("จ่ายจากกระเป๋า", o.expenseWallets)
                Line("เอกสารรายจ่ายที่ยืนยันแล้ว", o.expenseDocuments)
                Text(
                    "ช่วงเวลา: ${ReportPeriod.label(period, today)} • ไม่นับซ้ำ: รายงาน eZee ของวันที่ปิดยอดแล้ว และการจ่ายจากกระเป๋าที่ผูกกับเอกสาร",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // (3) (4) money held and money still to collect (right now, not by period)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SmallTile("เงินมัดจำล่วงหน้า", o.advance, "ยังไม่ใช่รายได้ • ตอนนี้", Color(0xFF1565C0), Modifier.weight(1f))
            SmallTile(
                "ต้องเก็บเพิ่มตอนเช็คเอาท์", o.toCollect,
                if (o.toCollectBookings > 0) "${o.toCollectBookings} การจอง • ตอนนี้" else "ไม่มีค้าง", Warn, Modifier.weight(1f)
            )
        }

        // (5) wallets
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenWallets), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("กระเป๋าเงิน ${o.wallets.size} ใบ (MAKE)", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("จัดการ ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                if (pendingTransfers > 0) Text("⚠ มี $pendingTransfers กระเป๋าที่ต้องโอนใน MAKE", fontSize = 12.sp, color = Warn, fontWeight = FontWeight.Bold)
                o.wallets.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${pct(w.wallet.percent)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                        Text(w.wallet.name + if (w.isProfit) " (กำไร)" else "", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${baht(w.balance)} ฿", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (w.balance < 0) ExpenseRed else MaterialTheme.colorScheme.onSurface)
                    }
                }
                HorizontalDivider()
                Row {
                    Text("รวม %", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        "${pct(o.percentTotal)}% " + if (o.percentOk) "✓" else "— ต้องได้ 100%",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (o.percentOk) IncomeGreen else ExpenseRed
                    )
                }
                Row {
                    Text("รวมเงินในกระเป๋า", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${baht(o.wallets.sumOf { it.balance })} ฿", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // (6) yearly plan
        YearPlanCard(o.plan)
    }
}

@Composable
private fun YearPlanCard(plan: YearPlan?) {
    var detail by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().testTag("year_plan_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (plan == null) {
                Text("ประมาณการทั้งปี", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("ยังไม่มีรายรับของปีนี้ในแอป จึงยังประมาณการไม่ได้ — เริ่มปิดยอดรายวัน/เช็คเอาท์ แล้วตัวเลขจะขึ้นเอง", fontSize = 12.sp)
                return@Column
            }
            Text("ประมาณการทั้งปี ${plan.year}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("รายรับเฉลี่ย ${baht(plan.avgMonthlyIncome)} ฿/เดือน • ${plan.basis}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Line("รายรับทั้งปี (×12)", plan.income, bold = true)
            Line("ค่าใช้จ่ายตามงบ (${pct(plan.expensePercent)}%)", plan.expenseBudget, color = ExpenseRed)
            Line("กำไร / เงินเก็บ (${pct(plan.profitPercent)}%)", plan.profit, color = IncomeGreen, bold = true)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("จริงตั้งแต่ต้นปีถึงวันนี้", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Line("รายรับ", plan.incomeSoFar)
            Line("รายจ่าย", plan.expenseSoFar)
            Line(if (plan.profitSoFar >= 0) "กำไร" else "ขาดทุน", plan.profitSoFar, color = if (plan.profitSoFar >= 0) IncomeGreen else ExpenseRed, bold = true)
            if (plan.income > 0) {
                val progress = (plan.incomeSoFar / plan.income).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("ได้รายรับแล้ว ${pct(progress * 100.0)}% ของประมาณการทั้งปี", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val short = plan.lines.filter { it.shortBy > 0 }
            if (short.isNotEmpty()) {
                Text(
                    "⚠ % ไม่พอเป้า: " + short.joinToString { "${it.wallet.name} ขาด ${baht(it.shortBy)} ฿/ปี" },
                    fontSize = 12.sp, color = Warn
                )
            }

            TextButton(onClick = { detail = !detail }) { Text(if (detail) "ซ่อนรายกระเป๋า" else "ดูรายกระเป๋า (ต่อเดือน / ต่อปี) ▾") }
            if (detail) {
                Row {
                    Text("กระเป๋า", fontSize = 11.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("ต่อเดือน", fontSize = 11.sp, modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("ต่อปี", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                plan.lines.forEach { l ->
                    Column {
                        Row {
                            Text("${l.wallet.name} ${pct(l.wallet.percent)}%", fontSize = 12.sp, modifier = Modifier.weight(1f),
                                color = if (l.isProfit) IncomeGreen else MaterialTheme.colorScheme.onSurface)
                            Text(baht(l.month), fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                            Text(baht(l.year), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        l.targetYear?.let {
                            Text(
                                "เป้า ${baht(it / 12)}/เดือน" + if (l.shortBy > 0) " → ขาด ${baht(l.shortBy)}/ปี" else " ✓ พอ",
                                fontSize = 10.sp, color = if (l.shortBy > 0) Warn else IncomeGreen
                            )
                        }
                    }
                }
                Text(
                    "ตัวเลขนี้คิดจากรายรับที่มีในแอปเท่านั้น รีสอร์ทมีช่วง high/low season จึงควรดูเป็นแนวทาง ยิ่งใช้นาน ตัวเลขยิ่งแม่น",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BigTile(title: String, amount: Double, accent: Color, bg: Color, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold)
            Text(baht(amount), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = accent, maxLines = 1)
            Text("บาท", fontSize = 10.sp, color = accent)
        }
    }
}

@Composable
private fun SmallTile(title: String, amount: Double, sub: String, accent: Color, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold)
            Text("${baht(amount)} ฿", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Line(label: String, amount: Double, color: Color? = null, bold: Boolean = false) {
    Row {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            "${baht(amount)} ฿", fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
