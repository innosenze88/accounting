package com.example.ui.screens.booking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.booking.BookingEntity
import com.example.data.booking.BookingRules
import com.example.data.booking.BookingStatus
import com.example.data.booking.PaymentKind
import com.example.data.booking.PaymentMethod
import com.example.data.docs.ThaiDate
import com.example.data.settings.BusinessSettings
import com.example.data.wallet.WalletMath
import com.example.ui.viewmodel.BookingView
import com.example.ui.viewmodel.BookingViewModel
import com.example.util.ReportPeriod

private enum class BookingFilter(val title: String) { ACTIVE("ที่กำลังจะมา / พักอยู่"), DONE("เช็คเอาท์แล้ว"), CANCELLED("ยกเลิก"), ALL("ทั้งหมด") }

@Composable
internal fun BookingsTab(vm: BookingViewModel) {
    val bookings by vm.bookings.collectAsStateWithLifecycle()
    val business by vm.business.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(BookingFilter.ACTIVE) }
    var editing by remember { mutableStateOf<BookingEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }

    val shown = bookings.filter {
        when (filter) {
            BookingFilter.ACTIVE -> it.booking.bookingStatus == BookingStatus.BOOKED || it.booking.bookingStatus == BookingStatus.CHECKED_IN
            BookingFilter.DONE -> it.booking.bookingStatus == BookingStatus.CHECKED_OUT
            BookingFilter.CANCELLED -> it.booking.bookingStatus == BookingStatus.CANCELLED
            BookingFilter.ALL -> true
        }
    }.let { list -> if (filter == BookingFilter.ACTIVE) list.sortedBy { it.booking.checkIn } else list.sortedByDescending { it.booking.checkIn } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().testTag("new_booking_button")) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("รับจองใหม่ (จองตรงกับรีสอร์ท)")
            }
        }
        item { ChoiceChips(BookingFilter.entries, filter, { it.title }) { filter = it } }
        if (shown.isEmpty()) item {
            Text(
                "ยังไม่มีการจองในกลุ่มนี้\n(ลูกค้า walk-in และ OTA ยังบันทึกใน eZee ตามเดิม)",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)
            )
        }
        items(shown, key = { it.booking.id }) { v -> BookingRow(v) { openId = v.booking.id } }
    }

    if (creating || editing != null) {
        BookingFormDialog(
            initial = editing,
            business = business,
            others = bookings.map { it.booking },
            onDismiss = { creating = false; editing = null },
            onSave = { b ->
                vm.saveBooking(b) { id -> openId = id }
                creating = false; editing = null
            }
        )
    }

    val openView = openId?.let { id -> bookings.firstOrNull { it.booking.id == id } }
    if (openView != null) {
        BookingDetailDialog(
            v = openView, vm = vm, business = business,
            onEdit = { editing = openView.booking; openId = null },
            onDismiss = { openId = null }
        )
    }
}

@Composable
private fun BookingRow(v: BookingView, onClick: () -> Unit) {
    val b = v.booking
    val status = b.bookingStatus
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ห้อง ${b.roomNo}  •  ${b.guestName}", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    status.titleTh, fontSize = 12.sp,
                    color = when (status) {
                        BookingStatus.CHECKED_IN -> Green
                        BookingStatus.CANCELLED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            Text("${ThaiDate.short(b.checkIn)} – ${ThaiDate.short(b.checkOut)} (${WalletMath.nights(b.checkIn, b.checkOut) ?: 0} คืน)", fontSize = 13.sp)
            Row {
                Text("ยอด ${baht(v.money.total)}  •  มัดจำ ${baht(v.money.deposits)}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (status != BookingStatus.CANCELLED && v.money.due > 0)
                    Text("ค้าง ${baht(v.money.due)}", fontSize = 12.sp, color = Orange, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------------------- new / edit booking

@Composable
private fun BookingFormDialog(
    initial: BookingEntity?,
    business: BusinessSettings,
    others: List<BookingEntity>,
    onDismiss: () -> Unit,
    onSave: (BookingEntity) -> Unit
) {
    val today = ReportPeriod.today()
    var name by remember { mutableStateOf(initial?.guestName.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var room by remember { mutableStateOf(initial?.roomNo ?: "") }
    var checkIn by remember { mutableStateOf(initial?.checkIn ?: today) }
    var checkOut by remember { mutableStateOf(initial?.checkOut ?: WalletMath.addDays(today, 1)) }
    var rate by remember { mutableStateOf(initial?.nightlyRate?.let { baht(it) }.orEmpty()) }
    var total by remember { mutableStateOf(initial?.totalAmount?.let { baht(it) }.orEmpty()) }
    var totalEdited by remember { mutableStateOf(initial != null) }
    var guests by remember { mutableStateOf((initial?.guests ?: 2).toString()) }
    var idNo by remember { mutableStateOf(initial?.idNumber.orEmpty()) }
    var nationality by remember { mutableStateOf(initial?.nationality ?: "ไทย") }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var comeFrom by remember { mutableStateOf(initial?.comeFrom.orEmpty()) }
    var goTo by remember { mutableStateOf(initial?.goTo.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var more by remember { mutableStateOf(false) }

    val nights = WalletMath.nights(checkIn, checkOut) ?: 0
    // Total follows nights × rate until the person types their own total (e.g. after a discount).
    LaunchedEffect(nights, rate) {
        if (!totalEdited) parseMoney(rate)?.let { total = baht(it * nights.coerceAtLeast(0)) }
    }

    val draft = BookingEntity(
        id = initial?.id ?: 0,
        guestName = name.trim(),
        phone = phone.trim().ifBlank { null },
        idNumber = idNo.trim().ifBlank { null },
        nationality = nationality.trim().ifBlank { null },
        address = address.trim().ifBlank { null },
        comeFrom = comeFrom.trim().ifBlank { null },
        goTo = goTo.trim().ifBlank { null },
        guests = guests.toIntOrNull() ?: 1,
        roomNo = room,
        checkIn = checkIn,
        checkOut = checkOut,
        nightlyRate = parseMoney(rate) ?: 0.0,
        totalAmount = parseMoney(total) ?: 0.0,
        status = initial?.status ?: BookingStatus.BOOKED.code,
        note = note.trim().ifBlank { null },
        createdAt = initial?.createdAt ?: System.currentTimeMillis(),
        updatedAt = if (initial != null) System.currentTimeMillis() else null,
        settledAt = initial?.settledAt
    )
    val problem = BookingRules.bookingProblem(draft.guestName, draft.roomNo, draft.checkIn, draft.checkOut, draft.totalAmount)
    val clash = BookingRules.overlapping(draft, others)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "รับจองใหม่" else "แก้ไขการจอง") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("ชื่อลูกค้า", name, { name = it })
                TextInput("เบอร์โทร", phone, { phone = it }, number = true)
                Text("ห้อง", fontSize = 12.sp)
                ChoiceChips(business.roomList, room.ifBlank { null }, { it }) { room = it }
                DateField("วันเข้าพัก", checkIn, { checkIn = it; if (checkOut <= it) checkOut = WalletMath.addDays(it, 1) }, Modifier.fillMaxWidth())
                DateField("วันออก", checkOut, { checkOut = it }, Modifier.fillMaxWidth())
                Text("$nights คืน", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyField("ราคาต่อคืน", rate, { rate = it })
                MoneyField("ยอดรวมที่ตกลง", total, { total = it; totalEdited = true })
                TextInput("จำนวนผู้เข้าพัก", guests, { guests = it.filter { c -> c.isDigit() } }, number = true)
                TextButton(onClick = { more = !more }) { Text(if (more) "ซ่อนข้อมูลทะเบียนผู้พัก" else "ข้อมูลทะเบียนผู้พัก (บัตร / ที่อยู่) ▾") }
                if (more) {
                    TextInput("เลขบัตรประชาชน / พาสปอร์ต", idNo, { idNo = it })
                    TextInput("สัญชาติ", nationality, { nationality = it })
                    TextInput("ที่อยู่", address, { address = it })
                    TextInput("มาจาก", comeFrom, { comeFrom = it })
                    TextInput("จะไปที่", goTo, { goTo = it })
                }
                TextInput("หมายเหตุ", note, { note = it })
                problem?.let { Text("✕ $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                clash?.let {
                    Text("✕ ห้อง ${it.roomNo} ถูกจองแล้ว: ${it.guestName} ${ThaiDate.short(it.checkIn)}–${ThaiDate.short(it.checkOut)}",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = problem == null && clash == null) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

// ---------------------------------------------------------------------------------- booking detail

@Composable
private fun BookingDetailDialog(
    v: BookingView,
    vm: BookingViewModel,
    business: BusinessSettings,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val b = v.booking
    val status = b.bookingStatus
    val open = status == BookingStatus.BOOKED || status == BookingStatus.CHECKED_IN
    var pay by remember { mutableStateOf<PaymentKind?>(null) }
    var voidPaymentId by remember { mutableStateOf<Long?>(null) }
    var confirmCheckOut by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var taxInvoice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ห้อง ${b.roomNo} • ${b.guestName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${status.titleTh} • ${ThaiDate.long(b.checkIn)} – ${ThaiDate.long(b.checkOut)}", fontSize = 13.sp)
                b.phone?.let { Text("โทร $it", fontSize = 13.sp) }
                b.note?.let { Text("หมายเหตุ: $it", fontSize = 12.sp) }
                HorizontalDivider()
                MoneyLine("ยอดรวม", v.money.total)
                MoneyLine("มัดจำที่รับ", v.money.deposits)
                MoneyLine("ชำระส่วนที่เหลือ", v.money.balancePaid)
                if (v.money.refunded > 0) MoneyLine("คืนเงินแล้ว", v.money.refunded)
                if (status != BookingStatus.CANCELLED) MoneyLine("ค้างชำระ", v.money.due, bold = true)

                SectionTitle("รายการรับเงิน")
                if (v.payments.isEmpty()) Text("ยังไม่มี", fontSize = 12.sp)
                v.payments.sortedBy { it.createdAt }.forEach { p ->
                    Card(shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                "${p.paymentKind.titleTh} ${baht(p.amount)} • ${p.paymentMethod.titleTh} • ${ThaiDate.short(p.date)}",
                                fontSize = 13.sp,
                                color = if (p.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                            )
                            if (!p.isActive) Text("ยกเลิกแล้ว: ${p.voidReason.orEmpty()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            if (p.isActive) Row {
                                if (p.paymentKind != PaymentKind.REFUND)
                                    TextButton(onClick = { vm.issueReceipt(p.id) }) { Text(if (p.paymentKind == PaymentKind.DEPOSIT) "ใบรับมัดจำ" else "ใบเสร็จ") }
                                if (open) TextButton(onClick = { voidPaymentId = p.id }) { Text("ยกเลิกรายการ", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }

                if (open) {
                    SectionTitle("รับเงิน")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { pay = PaymentKind.DEPOSIT }) { Text("รับมัดจำ") }
                        OutlinedButton(onClick = { pay = PaymentKind.BALANCE }) { Text("รับส่วนที่เหลือ") }
                    }
                    SectionTitle("สถานะ")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (status == BookingStatus.BOOKED) Button(onClick = { vm.checkIn(b.id) }) { Text("เช็คอิน") }
                        Button(onClick = { confirmCheckOut = true }) { Text("เช็คเอาท์") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onEdit) { Text("แก้ไข") }
                        OutlinedButton(onClick = { cancelling = true }) { Text("ยกเลิกการจอง", color = MaterialTheme.colorScheme.error) }
                    }
                }

                SectionTitle("ออกเอกสาร")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.issueInvoice(b.id, quotation = true) }) { Text("ใบเสนอราคา") }
                    OutlinedButton(onClick = { vm.issueInvoice(b.id, quotation = false) }) { Text("ใบแจ้งหนี้+QR") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.issueGuestCard(b.id) }) { Text("บัตรทะเบียนผู้พัก") }
                    if (business.canIssueTaxInvoice) OutlinedButton(onClick = { taxInvoice = true }) { Text("ใบกำกับภาษี") }
                }
                if (!business.canIssueTaxInvoice)
                    Text("ใบกำกับภาษีจะเปิดใช้เมื่อตั้งค่ารีสอร์ทว่าจด VAT และใส่เลขผู้เสียภาษีแล้ว", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } }
    )

    pay?.let { kind ->
        PaymentDialog(v, kind, vm, onDismiss = { pay = null })
    }
    voidPaymentId?.let { id ->
        ReasonDialog(
            title = "ยกเลิกรายการรับเงินนี้?",
            warning = "เงินที่แบ่งเข้ากระเป๋าจะถูกดึงกลับ ถ้าโอนใน MAKE ไปแล้ว แอปจะบอกยอดที่ต้องโอนกลับในแท็บกระเป๋า",
            confirmText = "ยกเลิกรายการ",
            onConfirm = { vm.voidPayment(id, it); voidPaymentId = null },
            onDismiss = { voidPaymentId = null }
        )
    }
    if (confirmCheckOut) {
        var date by remember { mutableStateOf(ReportPeriod.today()) }
        AlertDialog(
            onDismissRequest = { confirmCheckOut = false },
            title = { Text("เช็คเอาท์ ${b.guestName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField("วันที่เช็คเอาท์", date, { date = it }, Modifier.fillMaxWidth())
                    Text("มัดจำ ${baht(v.money.deposits)} บาท จะย้ายออกจากกระเป๋าเงินจองล่วงหน้า แล้วแบ่งเข้ากระเป๋าตาม %", fontSize = 13.sp)
                    if (v.money.due > 0)
                        Text("⚠ ลูกค้ายังค้าง ${baht(v.money.due)} บาท — กด \"รับส่วนที่เหลือ\" ก่อนถ้าได้รับเงินแล้ว", fontSize = 13.sp, color = Orange)
                }
            },
            confirmButton = { TextButton(onClick = { vm.checkOut(b.id, date); confirmCheckOut = false }) { Text("เช็คเอาท์") } },
            dismissButton = { TextButton(onClick = { confirmCheckOut = false }) { Text("ยังก่อน") } }
        )
    }
    if (cancelling) CancelBookingDialog(v, business.refundPercent, vm) { cancelling = false }
    if (taxInvoice) TaxInvoiceDialog(b, vm) { taxInvoice = false }
}

@Composable
private fun MoneyLine(label: String, amount: Double, bold: Boolean = false) {
    Row {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("${baht(amount)} บาท", fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (bold && amount > 0) Orange else MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------------------------- payment

@Composable
private fun PaymentDialog(v: BookingView, initialKind: PaymentKind, vm: BookingViewModel, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(initialKind) }
    var method by remember { mutableStateOf(PaymentMethod.TRANSFER) }
    var amount by remember { mutableStateOf(if (v.money.due > 0) baht(v.money.due) else "") }
    var date by remember { mutableStateOf(ReportPeriod.today()) }
    var note by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    val value = parseMoney(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("รับเงิน • ${v.booking.guestName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(listOf(PaymentKind.DEPOSIT, PaymentKind.BALANCE), kind, { it.titleTh }) { kind = it }
                Text(
                    if (kind == PaymentKind.DEPOSIT) "มัดจำ → เข้ากระเป๋าเงินจองล่วงหน้า (ยังไม่นับเป็นรายได้จนเช็คเอาท์)"
                    else "ชำระส่วนที่เหลือ → แบ่งเข้ากระเป๋าตาม % ทันที",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChoiceChips(PaymentMethod.entries, method, { it.titleTh }) { method = it }
                MoneyField("จำนวนเงิน", amount, { amount = it })
                DateField("วันที่รับเงิน", date, { date = it }, Modifier.fillMaxWidth())
                TextInput("หมายเหตุ (เช่น ธนาคาร/เลขสลิป)", note, { note = it })
                if (method == PaymentMethod.PROMPTPAY) {
                    val payload = vm.promptPayPayload(value)
                    if (payload == null) Text("ใส่เลขพร้อมเพย์ในแท็บเอกสาร → ข้อมูลรีสอร์ท ก่อน", fontSize = 12.sp, color = Orange)
                    else OutlinedButton(onClick = { showQr = true }) { Text("แสดง QR ให้ลูกค้าสแกน") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.addPayment(v.booking.id, kind, method, value ?: 0.0, date, note.ifBlank { null }); onDismiss() },
                enabled = (value ?: 0.0) > 0
            ) { Text("บันทึกรับเงิน") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
    if (showQr) vm.promptPayPayload(value)?.let { QrDialog(it, value) { showQr = false } }
}

// ---------------------------------------------------------------------------------- cancel

@Composable
private fun CancelBookingDialog(v: BookingView, refundPercent: Double, vm: BookingViewModel, onDismiss: () -> Unit) {
    var method by remember { mutableStateOf(PaymentMethod.TRANSFER) }
    var reason by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(ReportPeriod.today()) }
    val deposit = v.money.deposits
    val (refund, kept) = WalletMath.cancellationSplit(deposit, refundPercent)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ยกเลิกการจอง ${v.booking.guestName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("มัดจำที่รับไว้ ${baht(deposit)} บาท", fontSize = 13.sp)
                Text("คืนลูกค้า ${refundPercent.toInt()}% = ${baht(refund)} บาท", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("รีสอร์ทเก็บไว้ ${baht(kept)} บาท → แบ่งเข้ากระเป๋าเป็นรายได้", fontSize = 13.sp)
                if (refund > 0) {
                    Text("คืนเงินโดย", fontSize = 12.sp)
                    ChoiceChips(PaymentMethod.entries, method, { it.titleTh }) { method = it }
                }
                DateField("วันที่ยกเลิก", date, { date = it }, Modifier.fillMaxWidth())
                TextInput("เหตุผล", reason, { reason = it })
                Text("(เปลี่ยน % ที่คืนได้ในข้อมูลรีสอร์ท)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.cancelBooking(v.booking.id, date, method, reason); onDismiss() }, enabled = reason.isNotBlank()) {
                Text("ยืนยันยกเลิก", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ไม่ยกเลิก") } }
    )
}

// ---------------------------------------------------------------------------------- tax invoice

@Composable
private fun TaxInvoiceDialog(b: BookingEntity, vm: BookingViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(b.guestName) }
    var address by remember { mutableStateOf(b.address.orEmpty()) }
    var taxId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ออกใบกำกับภาษีเต็มรูป") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("ชื่อผู้ซื้อ / บริษัท", name, { name = it })
                TextInput("ที่อยู่ผู้ซื้อ", address, { address = it })
                TextInput("เลขผู้เสียภาษีผู้ซื้อ (ถ้ามี)", taxId, { taxId = it.filter { c -> c.isDigit() }.take(13) }, number = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.issueTaxInvoice(b.id, name, address, taxId); onDismiss() }, enabled = name.isNotBlank()) { Text("ออกเอกสาร") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}
