package com.example.ui.screens.booking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.booking.WalletEntity
import com.example.data.booking.WalletPercentRules
import com.example.data.booking.WalletRole
import com.example.data.booking.WalletTxnKind
import com.example.data.dashboard.FinanceOverviewCalc
import com.example.data.docs.ThaiDate
import com.example.ui.viewmodel.BookingViewModel
import com.example.ui.viewmodel.WalletView
import com.example.util.ReportPeriod

@Composable
internal fun WalletsTab(vm: BookingViewModel) {
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val allWallets by vm.allWallets.collectAsStateWithLifecycle()
    val txns by vm.txns.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    var expense by remember { mutableStateOf(false) }
    var monthEnd by remember { mutableStateOf(false) }
    var undoTxnId by remember { mutableStateOf<Long?>(null) }

    val advance = wallets.firstOrNull { it.wallet.walletRole == WalletRole.ADVANCE }
    val budget = wallets.filter { it.wallet.walletRole != WalletRole.ADVANCE }
    val pending = wallets.filter { kotlin.math.abs(it.toTransfer) >= 0.005 }
    val names = allWallets.associate { it.id to it.name }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        advance?.let { a ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(a.wallet.name, fontSize = 13.sp)
                        Text("${baht(a.balance)} บาท", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text("มัดจำของลูกค้าที่ยังไม่เช็คเอาท์ (ยังไม่ใช่รายได้)", fontSize = 11.sp)
                    }
                }
            }
        }

        if (pending.isNotEmpty()) {
            item { SectionTitle("ต้องโอนใน MAKE (${pending.size} กระเป๋า)") }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFF3E0))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("โอนตามนี้ในแอป MAKE แล้วกด \"โอนแล้ว\" ทีละกระเป๋า", fontSize = 12.sp)
                        pending.forEach { w ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(w.wallet.name, fontSize = 14.sp)
                                    Text(
                                        if (w.toTransfer > 0) "โอนเข้า ${baht(w.toTransfer)} บาท" else "โอนออก ${baht(-w.toTransfer)} บาท",
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = if (w.toTransfer > 0) Green else MaterialTheme.colorScheme.error
                                    )
                                }
                                OutlinedButton(onClick = { vm.markTransferred(w.wallet.id) }) { Text("โอนแล้ว") }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { expense = true }, modifier = Modifier.weight(1f)) { Text("จ่ายจากกระเป๋า") }
                OutlinedButton(onClick = { settings = true }, modifier = Modifier.weight(1f)) { Text("ตั้งค่า %") }
            }
        }
        item { SectionTitle("กระเป๋าทั้งหมด") }
        items(budget, key = { it.wallet.id }) { w -> WalletRow(w) }
        item {
            OutlinedButton(onClick = { monthEnd = true }, modifier = Modifier.fillMaxWidth()) { Text("ปิดเดือน → ย้ายเงินเหลือเข้าเงินเก็บ") }
        }

        item { SectionTitle("ความเคลื่อนไหวล่าสุด") }
        val recent = txns.sortedByDescending { it.createdAt }.take(40)
        if (recent.isEmpty()) item { Text("ยังไม่มี", fontSize = 12.sp) }
        items(recent, key = { "t${it.id}" }) { t ->
          Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${names[t.walletId] ?: "?"} • ${t.txnKind.titleTh}", fontSize = 13.sp,
                        color = if (t.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${ThaiDate.short(t.date)} ${t.note.orEmpty()}${if (!t.isActive) " (ยกเลิก)" else ""}${if (t.transferredAt != null) " ✓โอนแล้ว" else ""}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    (if (t.amount >= 0) "+" else "") + baht(t.amount), fontSize = 13.sp,
                    color = if (!t.isActive) MaterialTheme.colorScheme.onSurfaceVariant else if (t.amount >= 0) Green else MaterialTheme.colorScheme.error
                )
                if (t.isActive && t.txnKind == WalletTxnKind.EXPENSE)
                    TextButton(onClick = { undoTxnId = t.id }) { Text("ยกเลิก", fontSize = 12.sp) }
            }
            HorizontalDivider()
          }
        }
    }

    if (settings) WalletSettingsDialog(allWallets, vm) { settings = false }
    if (expense) ExpenseDialog(budget, vm) { expense = false }
    if (monthEnd) MonthEndDialog(vm) { monthEnd = false }
    undoTxnId?.let { id ->
        AlertDialog(
            onDismissRequest = { undoTxnId = null },
            title = { Text("ยกเลิกรายการจ่ายนี้?") },
            text = { Text("เงินจะกลับเข้ากระเป๋าเดิม", fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { vm.undoExpense(id); undoTxnId = null }) { Text("ยกเลิกรายการ") } },
            dismissButton = { TextButton(onClick = { undoTxnId = null }) { Text("ไม่ใช่") } }
        )
    }
}

@Composable
private fun WalletRow(w: WalletView) {
    val target = w.wallet.monthlyTarget?.takeIf { it > 0 }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.wallet.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${trimPercent(w.wallet.percent)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${baht(w.balance)} บาท", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = if (w.balance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (target != null) {
                LinearProgressIndicator(progress = { (w.thisMonth / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("เดือนนี้ได้ ${baht(w.thisMonth)} / เป้า ${baht(target)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("เดือนนี้ได้ ${baht(w.thisMonth)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun trimPercent(p: Double): String = if (p % 1.0 == 0.0) p.toLong().toString() else String.format(java.util.Locale.US, "%.2f", p)

// ---------------------------------------------------------------------------------- settings

@Composable
private fun WalletSettingsDialog(current: List<WalletEntity>, vm: BookingViewModel, onDismiss: () -> Unit) {
    val rows = remember { mutableStateListOf<WalletEntity>().apply { addAll(current.sortedBy { it.sortOrder }) } }
    val percentText = remember { mutableStateListOf<String>().apply { addAll(rows.map { trimPercent(it.percent) }) } }
    val targetText = remember { mutableStateListOf<String>().apply { addAll(rows.map { r -> r.monthlyTarget?.let { baht(it) }.orEmpty() }) } }
    val profit = remember {
        mutableStateListOf<Long>().apply {
            addAll(vm.business.value.profitWalletIds ?: current.filter { FinanceOverviewCalc.defaultIsProfit(it) }.map { it.id })
        }
    }
    var reason by remember { mutableStateOf("") }
    val avgIncome by vm.avgMonthlyIncome.collectAsStateWithLifecycle()
    val history by vm.percentChanges.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    /** What the rows look like with the typed values. */
    fun edited(): List<WalletEntity> = rows.mapIndexed { i, w ->
        w.copy(
            name = w.name.trim(),
            percent = if (w.walletRole == WalletRole.ADVANCE) 0.0 else percentText[i].toDoubleOrNull() ?: 0.0,
            monthlyTarget = parseMoney(targetText[i])?.takeIf { it > 0 },
            sortOrder = i
        )
    }
    val now = edited()
    val splitting = now.filter { it.active && it.walletRole != WalletRole.ADVANCE }
    val sum = splitting.sumOf { it.percent }
    val ok = kotlin.math.abs(sum - 100.0) < 0.001
    val diffs = WalletPercentRules.diff(current, now)
    val savingsIndex = rows.indexOfFirst { it.walletRole == WalletRole.SAVINGS && it.active }
    val balanced = if (!ok && savingsIndex >= 0) {
        WalletPercentRules.balancePercent(splitting.associate { (if (it.id == 0L) -it.sortOrder - 1L else it.id) to it.percent }, rows[savingsIndex].id)
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ตั้งค่ากระเป๋า / ปรับ %") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ตั้งชื่อให้ตรงกับกระเป๋าใน MAKE และใส่ % ที่แบ่งจากรายได้ (รวมกันต้องได้ 100%)", fontSize = 12.sp)
                Text(
                    "เดือนไหนค่าใช้จ่ายสูง (เช่น ค่าไฟ) เพิ่ม % ของกระเป๋านั้น แล้วกดให้กระเป๋าเงินเก็บรับส่วนต่าง " +
                        "— % ใหม่ใช้กับรายได้ที่เข้ามาหลังบันทึก เงินที่แบ่งไปแล้วไม่ย้าย",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                avgIncome?.let {
                    Text("รายได้เฉลี่ย 3 เดือนล่าสุด ≈ ${baht(it)} บาท/เดือน", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "รวม ${trimPercent(sum)}%" + if (ok) " ✓" else if (sum > 100) " — เกิน ${trimPercent(sum - 100)}%" else " — ขาด ${trimPercent(100 - sum)}%",
                    fontWeight = FontWeight.Bold, color = if (ok) Green else MaterialTheme.colorScheme.error
                )
                if (!ok && savingsIndex >= 0) {
                    if (balanced != null) {
                        OutlinedButton(onClick = { percentText[savingsIndex] = trimPercent(balanced) }, modifier = Modifier.fillMaxWidth()) {
                            Text("ให้ \"${rows[savingsIndex].name}\" รับส่วนต่าง → ${trimPercent(balanced)}%", fontSize = 12.sp)
                        }
                    } else {
                        Text("กระเป๋าเงินเก็บรับส่วนต่างไม่พอ ต้องลด % กระเป๋าอื่นด้วย", fontSize = 12.sp, color = Orange)
                    }
                }
                rows.forEachIndexed { i, w ->
                    val pctNow = percentText[i].toDoubleOrNull() ?: 0.0
                    val target = parseMoney(targetText[i])?.takeIf { it > 0 }
                    val suggested = if (w.walletRole == WalletRole.BUDGET) WalletPercentRules.suggestedPercent(target, avgIncome) else null
                    val before = current.firstOrNull { it.id == w.id && w.id != 0L }?.percent
                    Card(shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                when (w.walletRole) {
                                    WalletRole.ADVANCE -> "กระเป๋ามัดจำ (ไม่รับ %)"
                                    WalletRole.SAVINGS -> "กระเป๋าเงินเก็บ (รับเศษและเงินเหลือสิ้นเดือน)"
                                    WalletRole.BUDGET -> "กระเป๋าค่าใช้จ่าย" + if (!w.active) " (ปิดอยู่)" else ""
                                },
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(value = w.name, onValueChange = { rows[i] = w.copy(name = it) }, label = { Text("ชื่อ") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (w.walletRole != WalletRole.ADVANCE && w.active) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = percentText[i],
                                    onValueChange = { t -> percentText[i] = cleanPercent(t) },
                                    label = { Text("%") }, singleLine = true, modifier = Modifier.width(90.dp)
                                )
                                OutlinedTextField(
                                    value = targetText[i],
                                    onValueChange = { t -> targetText[i] = t.filter { it.isDigit() || it == '.' || it == ',' } },
                                    label = { Text("เป้าต่อเดือน") }, singleLine = true, modifier = Modifier.weight(1f)
                                )
                            }
                            if (before != null && kotlin.math.abs(before - pctNow) >= 0.0001 && w.active) {
                                Text("เดิม ${trimPercent(before)}% → ใหม่ ${trimPercent(pctNow)}%", fontSize = 11.sp, color = Orange)
                            }
                            if (suggested != null && w.active && kotlin.math.abs(suggested - pctNow) >= 0.0001) {
                                TextButton(onClick = { percentText[i] = trimPercent(suggested) }) {
                                    Text(
                                        (if (suggested > pctNow) "⚠ ไม่พอเป้า — " else "") +
                                            "แนะนำ ${trimPercent(suggested)}% (เป้า ${baht(target!!)} ÷ รายได้เฉลี่ย)",
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (w.walletRole == WalletRole.BUDGET) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("ใช้งานกระเป๋านี้", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Switch(checked = w.active, onCheckedChange = { on ->
                                    rows[i] = w.copy(active = on)
                                    if (!on) percentText[i] = "0"
                                })
                            }
                            if (w.walletRole != WalletRole.ADVANCE && w.id != 0L && w.active) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("นับเป็นกำไร / เงินเก็บ (ไม่ใช่ค่าใช้จ่าย)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Switch(checked = w.id in profit, onCheckedChange = { on -> if (on) profit.add(w.id) else profit.remove(w.id) })
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    rows.add(vm.newWallet(rows.size))
                    percentText.add("0")
                    targetText.add("")
                }) { Text("+ เพิ่มกระเป๋า") }

                if (diffs.isNotEmpty()) {
                    HorizontalDivider()
                    Text("สิ่งที่จะเปลี่ยน: ${WalletPercentRules.describe(diffs)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it },
                        label = { Text("เหตุผลที่ปรับ % (จำเป็น)") },
                        placeholder = { Text("เช่น ค่าไฟหน้าร้อนสูง") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }

                if (history.isNotEmpty()) {
                    HorizontalDivider()
                    Text("ประวัติการปรับ %", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    history.take(5).forEach { h ->
                        Column(Modifier.fillMaxWidth()) {
                            Text("${ThaiDate.short(ReportPeriod.isoDay(h.changedAt))} • ${h.reason}", fontSize = 12.sp)
                            Text(h.summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            vm.walletsFromHistory(h.beforePercents)?.let { old ->
                                TextButton(onClick = {
                                    old.forEach { o ->
                                        val idx = rows.indexOfFirst { it.id == o.id }
                                        if (idx >= 0 && o.walletRole != WalletRole.ADVANCE && rows[idx].active) percentText[idx] = trimPercent(o.percent)
                                    }
                                    reason = "กลับไปใช้ % ก่อนวันที่ ${ThaiDate.short(ReportPeriod.isoDay(h.changedAt))}"
                                }) { Text("กลับไปใช้ % ก่อนการปรับนี้", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !busy && (diffs.isEmpty() || reason.isNotBlank()), onClick = {
                vm.saveProfitWallets(profit.toSet())
                vm.saveWallets(edited(), reason, onSaved = onDismiss)
            }) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

/** Keeps digits and at most one dot, max 2 decimals ("12.345" -> "12.34"). */
internal fun cleanPercent(t: String): String {
    val digits = t.filter { it.isDigit() || it == '.' }
    val dot = digits.indexOf('.')
    if (dot < 0) return digits.take(3)
    return digits.substring(0, dot).take(3) + "." + digits.substring(dot + 1).replace(".", "").take(2)
}

// ---------------------------------------------------------------------------------- expense

@Composable
private fun ExpenseDialog(wallets: List<WalletView>, vm: BookingViewModel, onDismiss: () -> Unit) {
    var wallet by remember { mutableStateOf(wallets.firstOrNull()) }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(ReportPeriod.today()) }
    var note by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    val value = parseMoney(amount) ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("จ่ายค่าใช้จ่ายจากกระเป๋า") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(wallets, wallet, { "${it.wallet.name} (${baht(it.balance)})" }) { wallet = it }
                MoneyField("จำนวนเงิน", amount, { amount = it })
                DateField("วันที่จ่าย", date, { date = it }, Modifier.fillMaxWidth())
                TextInput("จ่ายค่าอะไร", note, { note = it })
                TextInput("ผู้รับเงิน (ใส่เพื่อออกใบสำคัญจ่าย)", payee, { payee = it })
                wallet?.let {
                    if (value > it.balance) Text("⚠ เกินยอดในกระเป๋า ${baht(it.balance)} บาท", fontSize = 12.sp, color = Orange)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = wallet != null && value > 0 && note.isNotBlank(),
                onClick = {
                    wallet?.let { vm.payExpense(it.wallet.id, value, date, note.trim(), null, payee.ifBlank { null }) }
                    onDismiss()
                }
            ) { Text("บันทึกจ่าย") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

// ---------------------------------------------------------------------------------- month end

@Composable
private fun MonthEndDialog(vm: BookingViewModel, onDismiss: () -> Unit) {
    var ym by remember { mutableStateOf(previousMonth(ReportPeriod.today().take(7))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ปิดเดือน") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ym = previousMonth(ym) }) { Text("◀") }
                    Text(ThaiDate.month(ym), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ym = com.example.data.wallet.WalletMath.nextMonth(ym) }) { Text("▶") }
                }
                Text("เงินที่เหลือในทุกกระเป๋าค่าใช้จ่ายจะย้ายเข้ากระเป๋าเงินเก็บ แล้วแอปจะบอกยอดที่ต้องโอนใน MAKE", fontSize = 13.sp)
                Text("ทำหลังจ่ายค่าใช้จ่ายของเดือนนั้นครบแล้ว", fontSize = 12.sp, color = Orange)
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeMonth(ym); onDismiss() }) { Text("ปิดเดือน") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}
