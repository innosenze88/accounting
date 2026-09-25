package com.example.ui.screens.booking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.docs.IssuedDocType
import com.example.data.docs.ThaiDate
import com.example.data.settings.BusinessSettings
import com.example.data.wallet.WalletMath
import com.example.ui.viewmodel.BookingViewModel
import com.example.util.ReportPeriod

// ================================================================================== day close

@Composable
internal fun DayCloseTab(vm: BookingViewModel) {
    val closes by vm.dayCloses.collectAsStateWithLifecycle()
    val bookings by vm.bookings.collectAsStateWithLifecycle()
    var date by remember { mutableStateOf(ReportPeriod.today()) }
    var ezee by remember { mutableStateOf("") }
    var fromReport by remember { mutableStateOf(false) }
    var cashExpected by remember { mutableStateOf("") }
    var cashCounted by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val closed = closes.firstOrNull { it.date == date }

    // Prefill from the imported eZee Manager Report and today's direct-booking cash.
    LaunchedEffect(date, bookings.size) {
        val suggested = vm.suggestedEzeeIncome(date)
        fromReport = suggested != null
        ezee = suggested?.let { baht(it) }.orEmpty()
        val cash = vm.directCashOn(date)
        cashExpected = if (cash != 0.0) baht(cash) else ""
    }

    val checkedOutToday = bookings.filter { it.booking.checkOut == date }
    val checkedOutText = checkedOutToday.joinToString { "ห้อง ${it.booking.roomNo} ${it.booking.guestName} (${it.booking.bookingStatus.titleTh})" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { DateField("วันที่ปิดยอด", date, { date = it }, Modifier.fillMaxWidth()) }
        if (closed != null) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("✓ ปิดยอดวันนี้แล้ว", color = Green, fontWeight = FontWeight.Bold)
                    Text("รายได้ eZee ${baht(closed.ezeeIncome)} บาท", fontSize = 13.sp)
                    cashLine(closed.cashExpected, closed.cashCounted)?.let { Text(it, fontSize = 13.sp) }
                    closed.note?.let { Text(it, fontSize = 12.sp) }
                }
            }
        } else {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. ห้องจองตรงที่ออกวันนี้", fontWeight = FontWeight.Bold)
                        Text(
                            if (checkedOutToday.isEmpty()) "ไม่มี" else "$checkedOutText\n→ กดเช็คเอาท์ในแท็บการจอง เพื่อย้ายมัดจำไปแบ่งเข้ากระเป๋า",
                            fontSize = 12.sp
                        )
                        HorizontalDivider()
                        Text("2. รายได้จาก eZee (walk-in / OTA)", fontWeight = FontWeight.Bold)
                        MoneyField("รับเงินจาก eZee วันนี้", ezee, { ezee = it; fromReport = false })
                        Text(
                            if (fromReport) "ดึงจาก Manager Report ที่นำเข้าแล้ว (ตรวจอีกครั้ง)"
                            else "นำเข้า Manager Report ของวันนี้ในแท็บนำเข้าไฟล์ แอปจะใส่ตัวเลขให้เอง",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        Text("3. นับเงินสด (ถ้ามี)", fontWeight = FontWeight.Bold)
                        MoneyField("เงินสดที่ควรมี", cashExpected, { cashExpected = it })
                        MoneyField("เงินสดที่นับได้", cashCounted, { cashCounted = it })
                        cashLine(parseMoney(cashExpected), parseMoney(cashCounted))?.let {
                            Text(it, fontSize = 13.sp, color = if (it.startsWith("ตรง")) Green else Orange)
                        }
                        TextInput("หมายเหตุ", note, { note = it })
                        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("ปิดยอดวันนี้") }
                    }
                }
            }
        }

        item { SectionTitle("ประวัติการปิดยอด") }
        if (closes.isEmpty()) item { Text("ยังไม่มี", fontSize = 12.sp) }
        items(closes.sortedByDescending { it.date }.take(60), key = { it.date }) { c ->
            Column {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(ThaiDate.short(c.date), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("eZee ${baht(c.ezeeIncome)}", fontSize = 13.sp)
                }
                cashLine(c.cashExpected, c.cashCounted)?.takeIf { !it.startsWith("ตรง") }?.let {
                    Text(it, fontSize = 11.sp, color = Orange)
                }
                HorizontalDivider()
            }
        }
    }

    if (confirm) {
        val income = parseMoney(ezee) ?: 0.0
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("ปิดยอด ${ThaiDate.long(date)}?") },
            text = {
                Text(
                    (if (income > 0) "รายได้ eZee ${baht(income)} บาท จะแบ่งเข้ากระเป๋าตาม %\n" else "ไม่มีรายได้ eZee\n") +
                        "ปิดแล้วแก้ไขไม่ได้ (ถ้าผิดให้บันทึกปรับยอดในกระเป๋า)",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeDay(date, income, parseMoney(cashExpected), parseMoney(cashCounted), note.ifBlank { null })
                    confirm = false
                }) { Text("ปิดยอด") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("ยังก่อน") } }
        )
    }
}

private fun cashLine(expected: Double?, counted: Double?): String? {
    if (expected == null || counted == null) return null
    val diff = counted - expected
    return when {
        kotlin.math.abs(diff) < 0.01 -> "ตรง ✓"
        diff < 0 -> "เงินสดขาด ${baht(-diff)} บาท"
        else -> "เงินสดเกิน ${baht(diff)} บาท"
    }
}

// ================================================================================== documents

@Composable
internal fun DocumentsTab(vm: BookingViewModel) {
    val docs by vm.issuedDocs.collectAsStateWithLifecycle()
    val business by vm.business.collectAsStateWithLifecycle()
    var editBusiness by remember { mutableStateOf(business.name.isBlank()) }
    var ym by remember { mutableStateOf(ReportPeriod.today().take(7)) }
    var typeFilter by remember { mutableStateOf<IssuedDocType?>(null) }
    var voidId by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ข้อมูลรีสอร์ท (หัวเอกสาร)", fontWeight = FontWeight.Bold)
                    Text(business.name.ifBlank { "ยังไม่ได้ตั้งค่า" }, fontSize = 13.sp)
                    Text(
                        "พร้อมเพย์: ${business.promptPayId.ifBlank { "-" }} • VAT: ${if (business.vatRegistered) "จดแล้ว ${trimPercent(business.vatRate)}%" else "ไม่ได้จด"} • คืนมัดจำ ${trimPercent(business.refundPercent)}%",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { editBusiness = true }) { Text("แก้ไขข้อมูลรีสอร์ท") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("ทะเบียนผู้พักรายเดือน (จองตรง)", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { ym = previousMonth(ym) }) { Text("◀") }
                        Text(ThaiDate.month(ym), modifier = Modifier.weight(1f))
                        TextButton(onClick = { ym = WalletMath.nextMonth(ym) }) { Text("▶") }
                        Button(onClick = { vm.shareRegister(ym) }) { Text("สร้าง PDF") }
                    }
                }
            }
        }
        item { SectionTitle("เอกสารที่ออกแล้ว") }
        item {
            ChoiceChips(listOf<IssuedDocType?>(null) + IssuedDocType.entries, typeFilter, { it?.titleTh ?: "ทั้งหมด" }) { typeFilter = it }
        }
        val shown = docs.filter { typeFilter == null || it.docType == typeFilter }.sortedByDescending { it.createdAt }
        if (shown.isEmpty()) item {
            Text("ยังไม่มี — ออกเอกสารได้จากหน้ารายละเอียดการจอง หรือจ่ายจากกระเป๋า (ใบสำคัญจ่าย)", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(shown, key = { it.id }) { d ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${d.number} • ${d.docType?.titleTh ?: d.type}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${ThaiDate.short(d.issueDate)} • ${d.customerName}", fontSize = 12.sp)
                        }
                        if (d.total > 0) Text(baht(d.total), fontSize = 13.sp)
                    }
                    if (!d.isActive) Text("ยกเลิกแล้ว: ${d.voidReason.orEmpty()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(onClick = { vm.shareDocument(d.id) }) { Text(if (d.copies == 0) "ส่ง / พิมพ์" else "ส่งสำเนา") }
                        if (d.isActive) TextButton(onClick = { voidId = d.id }) { Text("ยกเลิกเอกสาร", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (editBusiness) BusinessSettingsDialog(business, onSave = { vm.saveBusiness(it); editBusiness = false }) { editBusiness = false }
    voidId?.let { id ->
        ReasonDialog(
            title = "ยกเลิกเอกสารนี้?",
            warning = "เอกสารจะถูกประทับ \"ยกเลิก\" และเลขที่นี้จะไม่ถูกใช้ซ้ำ (เก็บไว้ตรวจย้อนหลัง ไม่ลบทิ้ง)",
            confirmText = "ยกเลิกเอกสาร",
            onConfirm = { vm.voidDocument(id, it); voidId = null },
            onDismiss = { voidId = null }
        )
    }
}

@Composable
private fun BusinessSettingsDialog(initial: BusinessSettings, onSave: (BusinessSettings) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var branch by remember { mutableStateOf(initial.branch) }
    var address by remember { mutableStateOf(initial.address) }
    var taxId by remember { mutableStateOf(initial.taxId) }
    var phone by remember { mutableStateOf(initial.phone) }
    var promptPay by remember { mutableStateOf(initial.promptPayId) }
    var vat by remember { mutableStateOf(initial.vatRegistered) }
    var vatRate by remember { mutableStateOf(trimPercent(initial.vatRate)) }
    var includeVat by remember { mutableStateOf(initial.pricesIncludeVat) }
    var refund by remember { mutableStateOf(trimPercent(initial.refundPercent)) }
    var rooms by remember { mutableStateOf(initial.rooms) }
    var footer by remember { mutableStateOf(initial.footer) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ข้อมูลรีสอร์ท") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("ชื่อกิจการ (หัวเอกสาร)", name, { name = it })
                TextInput("สาขา", branch, { branch = it })
                TextInput("ที่อยู่", address, { address = it })
                TextInput("เลขผู้เสียภาษี 13 หลัก", taxId, { taxId = it.filter { c -> c.isDigit() }.take(13) }, number = true)
                TextInput("โทรศัพท์", phone, { phone = it })
                TextInput("พร้อมเพย์ (เบอร์มือถือ หรือ เลข 13 หลัก)", promptPay, { promptPay = it.filter { c -> c.isDigit() } }, number = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("จดทะเบียน VAT", modifier = Modifier.weight(1f))
                    Switch(checked = vat, onCheckedChange = { vat = it })
                }
                if (vat) {
                    TextInput("อัตรา VAT %", vatRate, { vatRate = it.filter { c -> c.isDigit() || c == '.' } }, number = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ราคาห้องรวม VAT แล้ว", modifier = Modifier.weight(1f))
                        Switch(checked = includeVat, onCheckedChange = { includeVat = it })
                    }
                }
                TextInput("คืนมัดจำเมื่อยกเลิก (%)", refund, { refund = it.filter { c -> c.isDigit() || c == '.' } }, number = true)
                TextInput("เลขห้อง (คั่นด้วย ,)", rooms, { rooms = it })
                TextInput("ข้อความท้ายเอกสาร", footer, { footer = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        name = name.trim(), branch = branch.trim(), address = address.trim(), taxId = taxId,
                        phone = phone.trim(), promptPayId = promptPay, vatRegistered = vat,
                        vatRate = vatRate.toDoubleOrNull() ?: 7.0, pricesIncludeVat = includeVat,
                        refundPercent = (refund.toDoubleOrNull() ?: 50.0).coerceIn(0.0, 100.0),
                        rooms = rooms, footer = footer.trim()
                    )
                )
            }) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}
