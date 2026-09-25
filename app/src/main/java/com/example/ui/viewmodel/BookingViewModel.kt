package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.booking.BookingEntity
import com.example.data.booking.BookingMoney
import com.example.data.booking.BookingPaymentEntity
import com.example.data.booking.BookingRepository
import com.example.data.booking.BookingRules
import com.example.data.booking.BookingStatus
import com.example.data.booking.DayCloseEntity
import com.example.data.booking.PaymentKind
import com.example.data.booking.PaymentMethod
import com.example.data.booking.WalletEntity
import com.example.data.booking.WalletPercentChangeEntity
import com.example.data.booking.WalletPercentRules
import com.example.data.booking.WalletRole
import com.example.data.booking.WalletTxnEntity
import com.example.data.docs.DocLine
import com.example.data.docs.DocRequest
import com.example.data.docs.IssuedDocRepository
import com.example.data.docs.IssuedDocType
import com.example.data.docs.IssuedDocumentEntity
import com.example.data.docs.ThaiDate
import com.example.data.local.AppDatabase
import com.example.data.payment.PromptPay
import com.example.data.settings.BusinessSettings
import com.example.data.settings.BusinessSettingsRepository
import com.example.data.wallet.WalletMath
import com.example.util.ReportPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A wallet with its numbers for the screen. */
data class WalletView(
    val wallet: WalletEntity,
    val balance: Double,
    /** Net amount still to move in MAKE (+ into the pocket, − out of it). */
    val toTransfer: Double,
    /** Money split into it this month (compared with [WalletEntity.monthlyTarget]). */
    val thisMonth: Double
)

/** A booking with its payments and money summary. */
data class BookingView(val booking: BookingEntity, val payments: List<BookingPaymentEntity>, val money: BookingMoney)

/** Direct bookings, wallets (กระเป๋า), day closing and the documents the resort issues. */
class BookingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repo = BookingRepository(db)
    private val docs = IssuedDocRepository(application, db)
    private val businessRepo = BusinessSettingsRepository(application)

    val business: StateFlow<BusinessSettings> = businessRepo.settings

    init {
        viewModelScope.launch {
            try {
                repo.ensureDefaultWallets()
            } catch (e: Exception) {
                Log.e(TAG, "Default wallets failed", e)
            }
        }
    }

    val bookings: StateFlow<List<BookingView>> = combine(repo.bookingsFlow, repo.paymentsFlow) { list, payments ->
        val byBooking = payments.groupBy { it.bookingId }
        list.map { b -> (byBooking[b.id] ?: emptyList()).let { BookingView(b, it, BookingRules.money(b, it)) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wallets: StateFlow<List<WalletView>> = combine(repo.walletsFlow, repo.txnsFlow) { ws, txns ->
        val bal = BookingRules.balances(txns)
        val pending = BookingRules.pendingTransfers(txns)
        val month = BookingRules.allocatedInMonth(txns, ReportPeriod.today().take(7))
        ws.filter { it.active }.map { w -> WalletView(w, bal[w.id] ?: 0.0, pending[w.id] ?: 0.0, month[w.id] ?: 0.0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allWallets: StateFlow<List<WalletEntity>> =
        repo.walletsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val txns: StateFlow<List<WalletTxnEntity>> =
        repo.txnsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** History of % changes, newest first. */
    val percentChanges: StateFlow<List<WalletPercentChangeEntity>> =
        repo.percentChangesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Average income split into the wallets per month (last 3 finished months) — used to suggest a %. */
    val avgMonthlyIncome: StateFlow<Double?> = combine(repo.walletsFlow, repo.txnsFlow) { ws, txns ->
        WalletPercentRules.avgMonthlyIncome(txns, ws.firstOrNull { it.walletRole == WalletRole.ADVANCE }?.id, ReportPeriod.today())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val dayCloses: StateFlow<List<DayCloseEntity>> =
        repo.dayClosesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Scanned income documents that can be attached to a payment as its slip (not rejected / voided / samples).
     * An attached slip is not counted again as a document on the dashboard.
     */
    val slipOptions: StateFlow<List<BookingRules.SlipOption>> = db.documentDao().getAllDocuments()
        .map { docs ->
            docs.filter {
                it.sampleId == null && it.status != "REJECTED" && it.status != "VOIDED" &&
                    !it.transactionType.equals("EXPENSE", ignoreCase = true)
            }.map { d ->
                val who = d.sellerName ?: d.customerName ?: d.documentType
                BookingRules.SlipOption(
                    documentId = d.id, amount = d.totalAmount, date = d.date,
                    label = "#${d.id} ${d.date?.let { ThaiDate.short(it) } ?: "?"} • ${d.totalAmount?.let { money(it) } ?: "?"} ฿ • $who" +
                        if (d.status == "PENDING") " (รอตรวจ)" else ""
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Slips already attached to an active payment. */
    val linkedSlipIds: StateFlow<Set<Long>> = repo.paymentsFlow.map { BookingRules.linkedSlipIds(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val issuedDocs: StateFlow<List<IssuedDocumentEntity>> =
        docs.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun act(block: suspend () -> String?) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Action failed", e)
                "✕ ${e.localizedMessage ?: "ไม่สำเร็จ"}"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun money(v: Double) = String.format(Locale.US, "%,.2f", v)

    // ------------------------------------------------------------------ bookings

    fun saveBooking(b: BookingEntity, onSaved: (Long) -> Unit = {}) = act {
        val id = repo.saveBooking(b)
        onSaved(id)
        "✓ บันทึกการจองของ ${b.guestName} แล้ว"
    }

    fun checkIn(bookingId: Long) = act {
        repo.checkIn(bookingId)
        "✓ เช็คอินแล้ว"
    }

    fun addPayment(
        bookingId: Long,
        kind: PaymentKind,
        method: PaymentMethod,
        amount: Double,
        date: String,
        note: String?,
        slipDocumentId: Long? = null
    ) = act {
        repo.addPayment(bookingId, kind, method, amount, date, note, slipDocumentId)
        val slip = if (slipDocumentId != null) " (แนบสลิป #$slipDocumentId — ไม่นับซ้ำเป็นรายได้เอกสาร)" else ""
        if (kind == PaymentKind.DEPOSIT) "✓ รับมัดจำ ${money(amount)} บาท → กระเป๋าเงินจองล่วงหน้า$slip"
        else "✓ รับชำระ ${money(amount)} บาท → แบ่งเข้ากระเป๋าแล้ว$slip"
    }

    fun voidPayment(paymentId: Long, reason: String) = act {
        repo.voidPayment(paymentId, reason)
        "✓ ยกเลิกรายการรับเงินแล้ว (ถ้าโอนใน MAKE ไปแล้ว ดูรายการที่ต้องโอนกลับในแท็บกระเป๋า)"
    }

    fun checkOut(bookingId: Long, date: String) = act {
        val due = repo.checkOut(bookingId, date)
        "✓ เช็คเอาท์แล้ว — ย้ายมัดจำออกจากกระเป๋าจองล่วงหน้าและแบ่งเข้ากระเป๋าแล้ว" +
            if (due > 0) "\n⚠ ลูกค้ายังค้างชำระ ${money(due)} บาท" else ""
    }

    fun cancelBooking(bookingId: Long, date: String, method: PaymentMethod, reason: String) = act {
        val refund = repo.cancel(bookingId, date, business.value.refundPercent, method, reason)
        "✓ ยกเลิกการจองแล้ว" + if (refund > 0) " — คืนเงินลูกค้า ${money(refund)} บาท (อย่าลืมโอนคืน)" else ""
    }

    // ------------------------------------------------------------------ wallets

    /** Which wallets count as profit (savings, owner) in the dashboard's yearly plan. */
    fun saveProfitWallets(ids: Set<Long>) {
        businessRepo.save(business.value.copy(profitWallets = ids.sorted().joinToString(",")))
    }

    fun saveWallets(list: List<WalletEntity>, reason: String = "", onSaved: () -> Unit = {}) = act {
        val change = repo.saveWallets(list, reason)
        onSaved()
        if (change == null) "✓ บันทึกการตั้งค่ากระเป๋าแล้ว"
        else "✓ ปรับ % แล้ว: ${change.summary}\nใช้กับรายได้ที่เข้ามาหลังจากนี้ (เงินที่แบ่งไปแล้วไม่ย้าย)"
    }

    /** An earlier % setting on today's wallets (null when it no longer adds up to 100). */
    fun walletsFromHistory(saved: String): List<WalletEntity>? = WalletPercentRules.restore(allWallets.value, saved)

    fun payExpense(walletId: Long, amount: Double, date: String, note: String, documentId: Long?, voucherPayee: String?) = act {
        repo.payExpense(walletId, amount, date, note, documentId)
        val pv = voucherPayee?.takeIf { it.isNotBlank() }?.let { payee ->
            val d = docs.issue(
                DocRequest(
                    type = IssuedDocType.PAYMENT_VOUCHER, issueDate = date, customerName = payee,
                    lines = listOf(DocLine(note, 1.0, amount, amount)),
                    paymentText = "จ่ายจากกระเป๋า: ${wallets.value.firstOrNull { it.wallet.id == walletId }?.wallet?.name.orEmpty()}"
                ),
                business.value
            )
            " • ออกใบสำคัญจ่าย ${d.number}"
        }.orEmpty()
        "✓ ตัดจากกระเป๋าแล้ว ${money(amount)} บาท$pv"
    }

    fun undoExpense(txnId: Long) = act {
        repo.undoExpense(txnId)
        "✓ ยกเลิกรายการจ่ายแล้ว"
    }

    fun markTransferred(walletId: Long) = act {
        repo.markTransferred(walletId)
        "✓ บันทึกว่าโอนใน MAKE แล้ว"
    }

    fun closeMonth(yearMonth: String) = act {
        val lastDay = WalletMath.addDays(WalletMath.nextMonth(yearMonth) + "-01", -1)
        val moved = repo.closeMonth(yearMonth, lastDay)
        if (moved > 0) "✓ ปิดเดือน ${ThaiDate.month(yearMonth)} — ย้าย ${money(moved)} บาทเข้ากระเป๋าเงินเก็บ (อย่าลืมโอนใน MAKE)"
        else "ปิดเดือนแล้ว — ไม่มีเงินเหลือในกระเป๋าให้ย้าย"
    }

    // ------------------------------------------------------------------ day close

    /** Money received today from eZee (Manager Report "Total Payment"), if that report was imported. */
    suspend fun suggestedEzeeIncome(date: String): Double? = db.importedFileDao().observeAll().first()
        .filter { it.reportType == "manager_report" && it.reportDate == date }
        .firstNotNullOfOrNull { r ->
            runCatching { JSONObject(r.extractedJson ?: "{}").optJSONObject("summary")?.optDouble("total_payment") }
                .getOrNull()?.takeIf { !it.isNaN() }
        }

    /** Cash received today from direct bookings (for the cash count). */
    fun directCashOn(date: String): Double = bookings.value.flatMap { it.payments }
        .filter { it.isActive && it.date == date && it.paymentMethod == PaymentMethod.CASH }
        .sumOf { if (it.paymentKind == PaymentKind.REFUND) -it.amount else it.amount }

    fun closeDay(date: String, ezeeIncome: Double, cashExpected: Double?, cashCounted: Double?, note: String?) = act {
        repo.closeDay(date, ezeeIncome, cashExpected, cashCounted, note)
        val diff = if (cashExpected != null && cashCounted != null) cashCounted - cashExpected else 0.0
        "✓ ปิดยอดวันที่ ${ThaiDate.long(date)} แล้ว" +
            (if (ezeeIncome > 0) " — แบ่งรายได้ eZee ${money(ezeeIncome)} บาทเข้ากระเป๋า" else "") +
            (if (kotlin.math.abs(diff) >= 0.01) "\n⚠ เงินสด${if (diff < 0) "ขาด" else "เกิน"} ${money(kotlin.math.abs(diff))} บาท" else "") +
            "\nดูรายการที่ต้องโอนใน MAKE ที่แท็บกระเป๋า"
    }

    // ------------------------------------------------------------------ resort settings / PromptPay

    fun saveBusiness(s: BusinessSettings) {
        businessRepo.save(s)
        _message.value = "✓ บันทึกข้อมูลรีสอร์ทแล้ว"
    }

    /** PromptPay payload for [amount], or null when no PromptPay ID is set. */
    fun promptPayPayload(amount: Double?): String? = business.value.promptPayId.takeIf { it.isNotBlank() }?.let {
        runCatching { PromptPay.payload(it, amount) }.getOrNull()
    }

    // ------------------------------------------------------------------ documents

    private fun stayText(b: BookingEntity): String {
        val n = WalletMath.nights(b.checkIn, b.checkOut) ?: 0
        return "ห้อง ${b.roomNo} • ${ThaiDate.short(b.checkIn)} – ${ThaiDate.short(b.checkOut)} ($n คืน)"
    }

    private fun stayLine(b: BookingEntity): DocLine {
        val n = (WalletMath.nights(b.checkIn, b.checkOut) ?: 1).coerceAtLeast(1)
        return DocLine("ค่าห้องพัก ${stayText(b)}", n.toDouble(), b.totalAmount / n, b.totalAmount)
    }

    /** Receipt for one payment (deposit -> "ใบรับเงินมัดจำ", balance -> "ใบเสร็จรับเงิน"). */
    fun issueReceipt(paymentId: Long) = act {
        val p = requireNotNull(repo.getPayment(paymentId)) { "ไม่พบรายการ" }
        check(p.isActive && p.paymentKind != PaymentKind.REFUND) { "ออกใบเสร็จไม่ได้สำหรับรายการนี้" }
        val b = requireNotNull(repo.getBooking(p.bookingId))
        val type = if (p.paymentKind == PaymentKind.DEPOSIT) IssuedDocType.DEPOSIT_RECEIPT else IssuedDocType.RECEIPT
        val d = docs.issue(
            DocRequest(
                type = type, issueDate = p.date, customerName = b.guestName, customerAddress = b.address,
                lines = listOf(DocLine("${p.paymentKind.titleTh} ${stayText(b)}", 1.0, p.amount, p.amount)),
                bookingId = b.id, paymentId = p.id,
                paymentText = "ชำระโดย: ${p.paymentMethod.titleTh} วันที่ ${ThaiDate.long(p.date)}"
            ),
            business.value
        )
        share(d.id)
        "✓ ออก${type.titleTh} ${d.number} แล้ว"
    }

    /** Invoice for what is still due (with a PromptPay QR), or a quotation for the whole stay. */
    fun issueInvoice(bookingId: Long, quotation: Boolean) = act {
        val v = requireNotNull(bookings.value.firstOrNull { it.booking.id == bookingId }) { "ไม่พบการจอง" }
        val b = v.booking
        val amount = if (quotation) b.totalAmount else v.money.due.takeIf { it > 0 } ?: b.totalAmount
        val lines = if (quotation || v.money.paid <= 0) listOf(stayLine(b)) else listOf(
            DocLine("ค่าห้องพัก ${stayText(b)} (หักที่ชำระแล้ว ${money(v.money.paid)} บาท)", 1.0, amount, amount)
        )
        val type = if (quotation) IssuedDocType.QUOTATION else IssuedDocType.INVOICE
        val d = docs.issue(
            DocRequest(
                type = type, issueDate = ReportPeriod.today(), customerName = b.guestName, customerAddress = b.address,
                lines = lines, bookingId = b.id, qrPayload = promptPayPayload(amount),
                note = if (quotation) "ราคานี้ยืนยันถึงวันที่ ${ThaiDate.long(WalletMath.addDays(ReportPeriod.today(), 7))}" else null
            ),
            business.value
        )
        share(d.id)
        "✓ ออก${type.titleTh} ${d.number} แล้ว"
    }

    /** Full tax invoice for the whole stay (only when VAT registered). */
    fun issueTaxInvoice(bookingId: Long, buyerName: String, buyerAddress: String, buyerTaxId: String) = act {
        val b = requireNotNull(repo.getBooking(bookingId)) { "ไม่พบการจอง" }
        val d = docs.issue(
            DocRequest(
                type = IssuedDocType.TAX_INVOICE, issueDate = ReportPeriod.today(), customerName = buyerName,
                customerAddress = buyerAddress, customerTaxId = buyerTaxId, lines = listOf(stayLine(b)), bookingId = b.id
            ),
            business.value
        )
        share(d.id)
        "✓ ออกใบกำกับภาษี ${d.number} แล้ว"
    }

    /** Guest registration card for one booking. */
    fun issueGuestCard(bookingId: Long) = act {
        val b = requireNotNull(repo.getBooking(bookingId)) { "ไม่พบการจอง" }
        val d = docs.issue(
            DocRequest(
                type = IssuedDocType.GUEST_CARD, issueDate = b.checkIn, customerName = b.guestName, lines = emptyList(),
                bookingId = b.id,
                extraRows = listOf(
                    "ชื่อ-สกุล" to b.guestName,
                    "สัญชาติ" to b.nationality.orEmpty(),
                    "เลขบัตรประชาชน/พาสปอร์ต" to b.idNumber.orEmpty(),
                    "ที่อยู่" to b.address.orEmpty(),
                    "โทรศัพท์" to b.phone.orEmpty(),
                    "มาจาก" to b.comeFrom.orEmpty(),
                    "จะไปที่" to b.goTo.orEmpty(),
                    "ห้องพัก" to b.roomNo,
                    "วันเข้าพัก" to ThaiDate.long(b.checkIn),
                    "วันออก" to ThaiDate.long(b.checkOut),
                    "จำนวนผู้พัก" to "${b.guests} คน"
                )
            ),
            business.value
        )
        share(d.id)
        "✓ ออกบัตรทะเบียนผู้พัก ${d.number} แล้ว"
    }

    /** Guest register for a month (direct bookings that stayed that month). */
    fun shareRegister(yearMonth: String) = act {
        val list = bookings.value.map { it.booking }
            .filter { it.bookingStatus != BookingStatus.CANCELLED && it.checkIn.take(7) <= yearMonth && it.checkOut.take(7) >= yearMonth }
            .sortedBy { it.checkIn }
        shareFile(docs.registerPdf(business.value, yearMonth, list), "ทะเบียนผู้พัก ${ThaiDate.month(yearMonth)}")
        "✓ สร้างทะเบียนผู้พัก ${ThaiDate.month(yearMonth)} (${list.size} ราย)"
    }

    fun shareDocument(id: Long) = act {
        share(id)
        null
    }

    fun voidDocument(id: Long, reason: String) = act {
        val d = docs.void(id, reason)
        "✓ ยกเลิกเอกสาร ${d.number} แล้ว (เลขที่นี้จะไม่ถูกใช้ซ้ำ)"
    }

    private suspend fun share(docId: Long) {
        val file = docs.fileForSharing(docId)
        val d = docs.get(docId)
        shareFile(file, d?.let { "${it.docType?.titleTh ?: "เอกสาร"} ${it.number}" } ?: "เอกสาร")
    }

    private fun shareFile(file: File, title: String) {
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, "ส่ง / พิมพ์ $title").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** New wallet row for the settings dialog. */
    fun newWallet(sortOrder: Int) = WalletEntity(name = "กระเป๋าใหม่", role = WalletRole.BUDGET.code, percent = 0.0, sortOrder = sortOrder)

    companion object {
        private const val TAG = "BookingViewModel"
    }
}
