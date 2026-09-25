package com.example.data.booking

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.wallet.WalletMath
import kotlinx.coroutines.flow.Flow

/**
 * Saves direct bookings, payments and wallet movements. Every money change runs in one database
 * transaction, so a booking can never be half checked-out.
 */
class BookingRepository(private val db: AppDatabase) {

    private val bookings = db.bookingDao()
    private val wallets = db.walletDao()

    val bookingsFlow: Flow<List<BookingEntity>> = bookings.observeBookings()
    val paymentsFlow: Flow<List<BookingPaymentEntity>> = bookings.observePayments()
    val walletsFlow: Flow<List<WalletEntity>> = wallets.observeWallets()
    val txnsFlow: Flow<List<WalletTxnEntity>> = wallets.observeTxns()
    val dayClosesFlow: Flow<List<DayCloseEntity>> = wallets.observeDayCloses()
    val percentChangesFlow: Flow<List<WalletPercentChangeEntity>> = wallets.observePercentChanges()

    /** Creates the advance wallet + 8 wallets the first time (names and % can be changed later). */
    suspend fun ensureDefaultWallets() {
        if (wallets.getWallets().isNotEmpty()) return
        db.withTransaction {
            if (wallets.getWallets().isNotEmpty()) return@withTransaction
            BookingRules.DEFAULT_WALLETS.forEachIndexed { i, (name, role, pct) ->
                wallets.insertWallet(WalletEntity(name = name, role = role.code, percent = pct, sortOrder = i))
            }
        }
    }

    private suspend fun setup(): WalletSetup =
        requireNotNull(WalletSetup.from(wallets.getWallets())) { "ยังไม่มีกระเป๋าเงินจองล่วงหน้า" }

    // ------------------------------------------------------------------ bookings

    /** Checks and saves in one transaction, so two quick taps can never book the same room twice. */
    suspend fun saveBooking(b: BookingEntity): Long = db.withTransaction {
        BookingRules.bookingProblem(b.guestName, b.roomNo, b.checkIn, b.checkOut, b.totalAmount)?.let { error(it) }
        BookingRules.overlapping(b, bookings.getAllBookings())?.let {
            error("ห้อง ${b.roomNo} มีการจองของ ${it.guestName} (${it.checkIn} ถึง ${it.checkOut}) ทับวันกันอยู่")
        }
        if (b.id == 0L) {
            bookings.insertBooking(b)
        } else {
            val current = requireNotNull(bookings.getBooking(b.id)) { "ไม่พบการจอง" }
            check(current.settledAt == null) { "การจองที่เช็คเอาท์/ยกเลิกแล้วแก้ไขไม่ได้" }
            bookings.updateBooking(b.copy(status = current.status, createdAt = current.createdAt, updatedAt = System.currentTimeMillis()))
            b.id
        }
    }

    suspend fun checkIn(bookingId: Long) {
        val b = requireNotNull(bookings.getBooking(bookingId)) { "ไม่พบการจอง" }
        check(b.bookingStatus == BookingStatus.BOOKED) { "สถานะตอนนี้คือ ${b.bookingStatus.titleTh}" }
        bookings.updateBooking(b.copy(status = BookingStatus.CHECKED_IN.code, updatedAt = System.currentTimeMillis()))
    }

    /** Records a payment and its wallet movements. Returns the payment id. */
    suspend fun addPayment(
        bookingId: Long,
        kind: PaymentKind,
        method: PaymentMethod,
        amount: Double,
        date: String,
        note: String?,
        slipDocumentId: Long? = null
    ): Long = db.withTransaction {
        require(kind != PaymentKind.REFUND) { "คืนเงินผ่านการยกเลิกการจอง" }
        require(amount > 0) { "ยอดเงินต้องมากกว่า 0" }
        require(WalletMath.isIsoDate(date)) { "วันที่ไม่ถูกต้อง" }
        val b = requireNotNull(bookings.getBooking(bookingId)) { "ไม่พบการจอง" }
        check(b.bookingStatus != BookingStatus.CANCELLED) { "การจองนี้ยกเลิกแล้ว" }
        // After check-out there is no advance any more: late money is a normal payment split at once.
        val realKind = if (kind == PaymentKind.DEPOSIT && b.settledAt != null) PaymentKind.BALANCE else kind
        if (slipDocumentId != null) {
            val doc = requireNotNull(db.documentDao().getDocumentById(slipDocumentId)) { "ไม่พบสลิปที่เลือก" }
            check(doc.status != "VOIDED" && doc.status != "REJECTED") { "สลิปนี้ถูกยกเลิก/ปฏิเสธไปแล้ว" }
            val other = bookings.getAllPayments().firstOrNull { it.isActive && it.slipDocumentId == slipDocumentId }
            check(other == null) { "สลิปนี้ผูกกับการรับเงินอื่นไปแล้ว (รายการ #${other?.id})" }
        }
        val payment = BookingPaymentEntity(
            bookingId = bookingId, kind = realKind.code, method = method.code, amount = amount, date = date,
            note = note, slipDocumentId = slipDocumentId
        )
        val id = bookings.insertPayment(payment)
        wallets.insertTxns(BookingRules.onPayment(setup(), payment.copy(id = id), b))
        id
    }

    /** Cancels a payment entered by mistake (before the booking is settled). */
    suspend fun voidPayment(paymentId: Long, reason: String) = db.withTransaction {
        require(reason.isNotBlank()) { "ต้องใส่เหตุผล" }
        val p = requireNotNull(bookings.getPayment(paymentId)) { "ไม่พบรายการ" }
        check(p.isActive) { "รายการนี้ยกเลิกไปแล้ว" }
        val b = requireNotNull(bookings.getBooking(p.bookingId))
        check(b.settledAt == null || p.paymentKind == PaymentKind.BALANCE) {
            "มัดจำของการจองที่เช็คเอาท์แล้วยกเลิกไม่ได้ (ถูกแบ่งเข้ากระเป๋าแล้ว)"
        }
        val now = System.currentTimeMillis()
        bookings.updatePayment(p.copy(voidedAt = now, voidReason = reason.trim()))
        reverseSource(TxnSource.PAYMENT, paymentId, today(), now)
    }

    private suspend fun reverseSource(source: TxnSource, id: Long, date: String, now: Long) {
        val (toVoid, opposite) = BookingRules.reverse(wallets.getTxnsForSource(source.code, id), date, now)
        if (toVoid.isNotEmpty()) wallets.updateTxns(toVoid)
        if (opposite.isNotEmpty()) wallets.insertTxns(opposite)
    }

    /** Check-out: deposits move out of the advance wallet and are split. Returns what the guest still owes. */
    suspend fun checkOut(bookingId: Long, date: String): Double = db.withTransaction {
        val b = requireNotNull(bookings.getBooking(bookingId)) { "ไม่พบการจอง" }
        check(b.bookingStatus == BookingStatus.BOOKED || b.bookingStatus == BookingStatus.CHECKED_IN) {
            "สถานะตอนนี้คือ ${b.bookingStatus.titleTh}"
        }
        val payments = bookings.getPayments(bookingId)
        wallets.insertTxns(BookingRules.checkOut(setup(), b, payments, date))
        val now = System.currentTimeMillis()
        bookings.updateBooking(b.copy(status = BookingStatus.CHECKED_OUT.code, settledAt = now, updatedAt = now))
        BookingRules.money(b, payments).due
    }

    /** Cancels a booking: refund [refundPercent] % of the deposit, the rest is income. Returns the refund. */
    suspend fun cancel(bookingId: Long, date: String, refundPercent: Double, method: PaymentMethod, reason: String): Double =
        db.withTransaction {
            val b = requireNotNull(bookings.getBooking(bookingId)) { "ไม่พบการจอง" }
            check(b.settledAt == null) { "การจองนี้เช็คเอาท์/ยกเลิกไปแล้ว" }
            val payments = bookings.getPayments(bookingId)
            val c = BookingRules.cancel(setup(), b, payments, date, refundPercent)
            if (c.refund > 0) {
                bookings.insertPayment(
                    BookingPaymentEntity(
                        bookingId = bookingId, kind = PaymentKind.REFUND.code, method = method.code,
                        amount = c.refund, date = date, note = "คืนเงิน ${refundPercent.toInt()}% ของที่จ่ายมา ${String.format(java.util.Locale.US, "%,.2f", c.paid)} — $reason"
                    )
                )
            }
            wallets.insertTxns(c.txns)
            val now = System.currentTimeMillis()
            bookings.updateBooking(
                b.copy(
                    status = BookingStatus.CANCELLED.code, settledAt = now, updatedAt = now,
                    note = listOfNotNull(b.note, "ยกเลิก: $reason").joinToString(" • ")
                )
            )
            c.refund
        }

    // ------------------------------------------------------------------ wallets

    /**
     * Saves names / % / targets. The budget + savings % must add up to 100.
     * When a % changes, [reason] is required and the change is kept in the history (wallet_percent_changes).
     * New % apply to income split from now on; money already split is not moved.
     * Returns the saved change, or null when no % changed.
     */
    suspend fun saveWallets(list: List<WalletEntity>, reason: String = ""): WalletPercentChangeEntity? = db.withTransaction {
        val splitting = list.filter { it.active && it.walletRole != WalletRole.ADVANCE }
        WalletMath.percentProblem(splitting.map { it.percent })?.let { error(it) }
        check(list.count { it.walletRole == WalletRole.SAVINGS && it.active } == 1) { "ต้องมีกระเป๋าเงินเก็บ 1 ใบ" }
        check(list.count { it.walletRole == WalletRole.ADVANCE } == 1) { "ต้องมีกระเป๋าเงินจองล่วงหน้า 1 ใบ" }
        val before = wallets.getWallets()
        // A wallet that still holds money cannot be switched off (the money would disappear from the screen).
        val balances = BookingRules.balances(wallets.getAllTxns())
        list.filter { !it.active && it.id != 0L }.forEach { w ->
            val wasActive = before.firstOrNull { it.id == w.id }?.active == true
            val bal = balances[w.id] ?: 0.0
            check(!wasActive || kotlin.math.abs(bal) < 0.005) {
                "ปิดกระเป๋า \"${w.name}\" ไม่ได้ ยังมีเงิน ${String.format(java.util.Locale.US, "%,.2f", bal)} บาท — ย้าย/จ่ายออกก่อน"
            }
        }
        val diffs = WalletPercentRules.diff(before, list)
        WalletPercentRules.changeProblem(diffs, reason)?.let { error(it) }
        list.forEach { w -> if (w.id == 0L) wallets.insertWallet(w) else wallets.updateWallet(w) }
        if (diffs.isEmpty()) {
            null
        } else {
            val saved = wallets.getWallets()
            val change = WalletPercentChangeEntity(
                reason = reason.trim(),
                summary = WalletPercentRules.describe(WalletPercentRules.diff(before, saved)),
                beforePercents = WalletPercentRules.encode(before),
                afterPercents = WalletPercentRules.encode(saved)
            )
            change.copy(id = wallets.insertPercentChange(change))
        }
    }

    /** Pays an expense from a wallet (electricity bill, salaries...). */
    suspend fun payExpense(walletId: Long, amount: Double, date: String, note: String, documentId: Long?) = db.withTransaction {
        if (documentId != null) {
            val already = wallets.getTxnsForSource(TxnSource.DOCUMENT.code, documentId).filter { it.isActive }
            check(already.isEmpty()) { "เอกสารนี้ตัดจากกระเป๋าไปแล้ว" }
        }
        wallets.insertTxns(listOf(BookingRules.expense(walletId, amount, date, note, documentId)))
    }

    suspend fun undoExpense(txnId: Long) = db.withTransaction {
        val t = requireNotNull(wallets.getAllTxns().firstOrNull { it.id == txnId }) { "ไม่พบรายการ" }
        check(t.txnKind == WalletTxnKind.EXPENSE && t.isActive) { "ยกเลิกได้เฉพาะรายการจ่าย" }
        wallets.updateTxns(listOf(t.copy(voidedAt = System.currentTimeMillis())))
    }

    /** Marks everything pending for [walletId] as moved in MAKE. */
    suspend fun markTransferred(walletId: Long) = wallets.markTransferred(walletId, System.currentTimeMillis())

    /** Month end: leftovers of every budget wallet move to savings. Only once per month. */
    suspend fun closeMonth(yearMonth: String, date: String): Double = db.withTransaction {
        val sourceId = yearMonth.replace("-", "").toLong()
        check(wallets.getTxnsForSource(TxnSource.MONTH_END.code, sourceId).none { it.isActive }) { "ปิดเดือน $yearMonth ไปแล้ว" }
        val txns = BookingRules.monthEnd(setup(), BookingRules.balances(wallets.getAllTxns()), yearMonth, date)
        wallets.insertTxns(txns)
        txns.filter { it.amount > 0 }.sumOf { it.amount }
    }

    // ------------------------------------------------------------------ day close

    /** Closes a day: splits the eZee money received today and saves the cash count. Once per day. */
    suspend fun closeDay(
        date: String,
        ezeeIncome: Double,
        cashExpected: Double?,
        cashCounted: Double?,
        note: String?
    ) = db.withTransaction {
        check(wallets.getDayClose(date) == null) { "ปิดยอดวันที่ $date ไปแล้ว" }
        require(ezeeIncome >= 0) { "ยอด eZee ต้องไม่ติดลบ" }
        if (ezeeIncome > 0) {
            wallets.insertTxns(
                BookingRules.split(setup(), ezeeIncome, date, TxnSource.EZEE, date.replace("-", "").toLong(), "รายได้ eZee วันที่ $date")
            )
        }
        wallets.insertDayClose(
            DayCloseEntity(
                date = date, closedAt = System.currentTimeMillis(), ezeeIncome = ezeeIncome,
                cashExpected = cashExpected, cashCounted = cashCounted, note = note
            )
        )
    }

    suspend fun getBooking(id: Long) = bookings.getBooking(id)
    suspend fun getPayments(bookingId: Long) = bookings.getPayments(bookingId)
    suspend fun getPayment(id: Long) = bookings.getPayment(id)

    companion object {
        fun today(): String = com.example.util.ReportPeriod.today()
    }
}
