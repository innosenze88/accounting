package com.example.data.booking

import com.example.data.wallet.WalletMath
import java.util.Locale

/** Money summary of one booking. */
data class BookingMoney(
    val total: Double,
    val deposits: Double,
    val balancePaid: Double,
    val refunded: Double
) {
    /** What the guest still owes (never negative). */
    val due: Double get() = WalletMath.toBaht(
        (WalletMath.toSatang(total) - WalletMath.toSatang(deposits) - WalletMath.toSatang(balancePaid)).coerceAtLeast(0)
    )
    val paid: Double get() = deposits + balancePaid
}

/** The wallets money is split into, with the savings wallet that takes rounding leftovers. */
data class WalletSetup(val advance: WalletEntity, val budget: List<WalletEntity>, val savings: WalletEntity?) {
    val splitTargets: List<Pair<Long, Double>>
        get() = (budget + listOfNotNull(savings)).filter { it.active && it.percent > 0 }.map { it.id to it.percent }

    companion object {
        fun from(wallets: List<WalletEntity>): WalletSetup? {
            val advance = wallets.firstOrNull { it.walletRole == WalletRole.ADVANCE } ?: return null
            return WalletSetup(
                advance = advance,
                budget = wallets.filter { it.walletRole == WalletRole.BUDGET && it.active },
                savings = wallets.firstOrNull { it.walletRole == WalletRole.SAVINGS && it.active }
            )
        }
    }
}

/**
 * Every rule for direct bookings and wallets, as pure functions that return the wallet movements to save.
 * Rules agreed with the resort:
 *  - A deposit goes to the ADVANCE wallet (not income yet).
 *  - At check-out, the deposits leave the ADVANCE wallet in one go and are split into the budget wallets by %.
 *  - Money paid at check-out (the balance) is split into the budget wallets immediately.
 *  - Cancelled booking: the guest gets [refundPercent] % of the deposit back, the rest is split as income.
 *  - Month end: what is left in each budget wallet moves to the savings wallet.
 */
object BookingRules {

    val DEFAULT_WALLETS = listOf(
        Triple("เงินจองล่วงหน้า", WalletRole.ADVANCE, 0.0),
        Triple("เงินเดือนพนักงาน", WalletRole.BUDGET, 30.0),
        Triple("ค่าน้ำ-ค่าไฟ", WalletRole.BUDGET, 10.0),
        Triple("ค่าใช้จ่ายประจำ (เน็ต/เคเบิล/ผ่อน)", WalletRole.BUDGET, 10.0),
        Triple("ของใช้-ดำเนินงาน", WalletRole.BUDGET, 10.0),
        Triple("ซ่อมบำรุง", WalletRole.BUDGET, 10.0),
        Triple("ภาษี", WalletRole.BUDGET, 5.0),
        Triple("ส่วนของเจ้าของ", WalletRole.BUDGET, 15.0),
        Triple("เงินเก็บ", WalletRole.SAVINGS, 10.0)
    )

    fun money(booking: BookingEntity, payments: List<BookingPaymentEntity>): BookingMoney {
        val active = payments.filter { it.bookingId == booking.id && it.isActive }
        return BookingMoney(
            total = booking.totalAmount,
            deposits = active.filter { it.paymentKind == PaymentKind.DEPOSIT }.sumOf { it.amount },
            balancePaid = active.filter { it.paymentKind == PaymentKind.BALANCE }.sumOf { it.amount },
            refunded = active.filter { it.paymentKind == PaymentKind.REFUND }.sumOf { it.amount }
        )
    }

    /** Scanned slips attached to an active payment: their money is counted by the booking, not as a document. */
    fun linkedSlipIds(payments: List<BookingPaymentEntity>): Set<Long> =
        payments.filter { it.isActive }.mapNotNull { it.slipDocumentId }.toSet()

    /** A scanned income document that can be attached to a payment as its slip. */
    data class SlipOption(val documentId: Long, val amount: Double?, val date: String?, val label: String)

    /**
     * Slips to offer when recording a payment: not attached to another payment yet, best match first
     * (same amount, then the closest date), at most [limit].
     */
    fun slipChoices(options: List<SlipOption>, linked: Set<Long>, amount: Double?, date: String, limit: Int = 8): List<SlipOption> {
        val free = options.filter { it.documentId !in linked }
        fun sameAmount(o: SlipOption) = amount != null && o.amount != null &&
            WalletMath.toSatang(o.amount) == WalletMath.toSatang(amount)
        fun dayGap(o: SlipOption): Int = o.date?.let { d -> WalletMath.nights(d, date)?.let { kotlin.math.abs(it) } } ?: Int.MAX_VALUE
        return free.sortedWith(compareBy<SlipOption>({ !sameAmount(it) }, { dayGap(it) }, { -it.documentId })).take(limit)
    }

    /** true when [o] looks like the slip of this payment (same amount, within 3 days). */
    fun isLikelySlip(o: SlipOption, amount: Double?, date: String): Boolean =
        amount != null && o.amount != null && WalletMath.toSatang(o.amount) == WalletMath.toSatang(amount) &&
            (o.date?.let { d -> WalletMath.nights(d, date)?.let { kotlin.math.abs(it) <= 3 } } ?: false)

    /** Current balance of each wallet (active movements only). */
    fun balances(txns: List<WalletTxnEntity>): Map<Long, Double> =
        txns.filter { it.isActive }.groupBy { it.walletId }
            .mapValues { (_, list) -> WalletMath.toBaht(list.sumOf { WalletMath.toSatang(it.amount) }) }

    /** Net amount still to move in MAKE per wallet (+ into the pocket, − out of it). */
    fun pendingTransfers(txns: List<WalletTxnEntity>): Map<Long, Double> =
        WalletMath.pendingTransfers(
            txns.filter { it.isActive && it.transferredAt == null && it.txnKind != WalletTxnKind.EXPENSE }
                .map { it.walletId to it.amount }
        )

    /** Money into each wallet this month (allocations only) — compared with the wallet's monthly target. */
    fun allocatedInMonth(txns: List<WalletTxnEntity>, yearMonth: String): Map<Long, Double> =
        txns.filter { it.isActive && it.txnKind == WalletTxnKind.ALLOCATION && it.date.startsWith(yearMonth) }
            .groupBy { it.walletId }
            .mapValues { (_, l) -> WalletMath.toBaht(l.sumOf { WalletMath.toSatang(it.amount) }) }

    fun split(
        setup: WalletSetup,
        amount: Double,
        date: String,
        source: TxnSource,
        sourceId: Long?,
        note: String
    ): List<WalletTxnEntity> {
        require(WalletMath.percentProblem(setup.splitTargets.map { it.second }) == null) {
            "ตั้งค่า % ของกระเป๋ายังไม่ครบ 100 — ไปที่ กระเป๋า → ตั้งค่า"
        }
        return WalletMath.allocate(amount, setup.splitTargets, setup.savings?.id).map { s ->
            WalletTxnEntity(
                walletId = s.walletId, amount = s.amount, kind = WalletTxnKind.ALLOCATION.code, date = date,
                sourceType = source.code, sourceId = sourceId, note = note
            )
        }
    }

    /** A payment was received. DEPOSIT -> advance wallet; BALANCE -> split now; REFUND is handled by [cancel]. */
    fun onPayment(setup: WalletSetup, payment: BookingPaymentEntity, booking: BookingEntity): List<WalletTxnEntity> =
        when (payment.paymentKind) {
            PaymentKind.DEPOSIT -> listOf(
                WalletTxnEntity(
                    walletId = setup.advance.id, amount = payment.amount, kind = WalletTxnKind.ADVANCE_IN.code,
                    date = payment.date, sourceType = TxnSource.PAYMENT.code, sourceId = payment.id,
                    note = "มัดจำ ${booking.guestName} ห้อง ${booking.roomNo}"
                )
            )
            PaymentKind.BALANCE -> split(
                setup, payment.amount, payment.date, TxnSource.PAYMENT, payment.id,
                "ชำระ ${booking.guestName} ห้อง ${booking.roomNo}"
            )
            PaymentKind.REFUND -> emptyList()
        }

    /** Check-out: the deposits leave the advance wallet in one go and are split into the budget wallets. */
    fun checkOut(setup: WalletSetup, booking: BookingEntity, payments: List<BookingPaymentEntity>, date: String): List<WalletTxnEntity> {
        require(booking.settledAt == null) { "การจองนี้เช็คเอาท์/ยกเลิกไปแล้ว" }
        val deposits = money(booking, payments).deposits
        if (deposits <= 0.0) return emptyList()
        val note = "เช็คเอาท์ ${booking.guestName} ห้อง ${booking.roomNo}"
        return listOf(
            WalletTxnEntity(
                walletId = setup.advance.id, amount = -deposits, kind = WalletTxnKind.ADVANCE_OUT.code, date = date,
                sourceType = TxnSource.BOOKING.code, sourceId = booking.id, note = note
            )
        ) + split(setup, deposits, date, TxnSource.BOOKING, booking.id, "$note (จากมัดจำ)")
    }

    /**
     * Result of a cancellation: [paid] = everything the guest paid (deposits + balance payments),
     * [refund] goes back to the guest, [kept] stays as income. [refund] + [kept] = [paid].
     */
    data class Cancellation(val paid: Double, val refund: Double, val kept: Double, val txns: List<WalletTxnEntity>)

    /** What a cancellation would do, without saving anything (shown in the confirm dialog). */
    fun cancellationPreview(money: BookingMoney, refundPercent: Double): Triple<Double, Double, Double> {
        val paid = WalletMath.toBaht(WalletMath.toSatang(money.deposits) + WalletMath.toSatang(money.balancePaid))
        val (refund, kept) = WalletMath.cancellationSplit(paid, refundPercent)
        return Triple(paid, refund, kept)
    }

    /**
     * Cancels a booking. The refund % applies to ALL money the guest paid, not only the deposit:
     *  - deposits leave the advance wallet in one go;
     *  - balance payments were already split into the wallets as income, so only the difference
     *    (kept − balance already split) is split now. When the refund is bigger than what is still in the
     *    advance wallet, the difference is taken back out of the wallets with the same % (negative split).
     */
    fun cancel(
        setup: WalletSetup,
        booking: BookingEntity,
        payments: List<BookingPaymentEntity>,
        date: String,
        refundPercent: Double
    ): Cancellation {
        require(booking.settledAt == null) { "การจองนี้เช็คเอาท์/ยกเลิกไปแล้ว" }
        val m = money(booking, payments)
        val (paid, refund, kept) = cancellationPreview(m, refundPercent)
        if (paid <= 0.0) return Cancellation(0.0, 0.0, 0.0, emptyList())
        val note = "ยกเลิก ${booking.guestName} ห้อง ${booking.roomNo}"
        val txns = mutableListOf<WalletTxnEntity>()
        if (m.deposits > 0) {
            txns += WalletTxnEntity(
                walletId = setup.advance.id, amount = -m.deposits, kind = WalletTxnKind.ADVANCE_OUT.code, date = date,
                sourceType = TxnSource.BOOKING.code, sourceId = booking.id,
                note = "$note (จ่ายมา ${fmt(paid)}: คืนลูกค้า ${fmt(refund)}, เป็นรายได้ ${fmt(kept)})"
            )
        }
        // Income already in the wallets = the balance payments. Adjust it to what is really kept.
        val change = WalletMath.toSatang(kept) - WalletMath.toSatang(m.balancePaid)
        when {
            change > 0 -> txns += split(setup, WalletMath.toBaht(change), date, TxnSource.BOOKING, booking.id, "$note (เงินที่ไม่คืน)")
            change < 0 -> txns += split(setup, WalletMath.toBaht(-change), date, TxnSource.BOOKING, booking.id, "$note (คืนจากเงินที่แบ่งเข้ากระเป๋าแล้ว)")
                .map { it.copy(amount = -it.amount) }
        }
        return Cancellation(paid, refund, kept, txns)
    }

    /**
     * Undoes movements (e.g. a payment entered by mistake). Movements not yet transferred in MAKE are voided;
     * movements already transferred get an opposite movement so the transfer list shows the money to move back.
     */
    fun reverse(txns: List<WalletTxnEntity>, date: String, now: Long): Pair<List<WalletTxnEntity>, List<WalletTxnEntity>> {
        val active = txns.filter { it.isActive }
        val toVoid = active.filter { it.transferredAt == null }.map { it.copy(voidedAt = now) }
        val opposite = active.filter { it.transferredAt != null }.map {
            WalletTxnEntity(
                walletId = it.walletId, amount = -it.amount, kind = WalletTxnKind.ADJUST.code, date = date,
                sourceType = it.sourceType, sourceId = it.sourceId, note = "กลับรายการ: ${it.note ?: it.txnKind.titleTh}"
            )
        }
        return toVoid to opposite
    }

    /** Month end: every budget wallet with money left moves it to the savings wallet. */
    fun monthEnd(setup: WalletSetup, balances: Map<Long, Double>, yearMonth: String, date: String): List<WalletTxnEntity> {
        val savings = requireNotNull(setup.savings) { "ยังไม่มีกระเป๋าเงินเก็บ" }
        val sourceId = yearMonth.replace("-", "").toLong()
        val outs = setup.budget.mapNotNull { w ->
            val bal = balances[w.id] ?: 0.0
            if (bal <= 0.0) null else WalletTxnEntity(
                walletId = w.id, amount = -bal, kind = WalletTxnKind.MONTH_END_OUT.code, date = date,
                sourceType = TxnSource.MONTH_END.code, sourceId = sourceId, note = "ปิดเดือน $yearMonth → ${savings.name}"
            )
        }
        if (outs.isEmpty()) return emptyList()
        val total = WalletMath.toBaht(-outs.sumOf { WalletMath.toSatang(it.amount) })
        return outs + WalletTxnEntity(
            walletId = savings.id, amount = total, kind = WalletTxnKind.MONTH_END_IN.code, date = date,
            sourceType = TxnSource.MONTH_END.code, sourceId = sourceId, note = "ปิดเดือน $yearMonth (เงินที่เหลือจากทุกกระเป๋า)"
        )
    }

    /** Money paid from a wallet (electricity bill, salaries...). */
    fun expense(walletId: Long, amount: Double, date: String, note: String, documentId: Long?): WalletTxnEntity {
        require(amount > 0) { "ยอดต้องมากกว่า 0" }
        return WalletTxnEntity(
            walletId = walletId, amount = -amount, kind = WalletTxnKind.EXPENSE.code, date = date,
            sourceType = (if (documentId != null) TxnSource.DOCUMENT else TxnSource.MANUAL).code,
            sourceId = documentId, note = note
        )
    }

    /** Problems that block saving a booking (null = OK). */
    fun bookingProblem(guestName: String, roomNo: String, checkIn: String, checkOut: String, total: Double): String? = when {
        guestName.isBlank() -> "ใส่ชื่อลูกค้า"
        roomNo.isBlank() -> "เลือกห้อง"
        !WalletMath.isIsoDate(checkIn) || !WalletMath.isIsoDate(checkOut) -> "วันที่ต้องเป็นรูปแบบ ปปปป-ดด-วว"
        (WalletMath.nights(checkIn, checkOut) ?: 0) <= 0 -> "วันเช็คเอาท์ต้องหลังวันเช็คอิน"
        total <= 0 -> "ยอดรวมต้องมากกว่า 0"
        else -> null
    }

    /** Another active booking in the same room on overlapping nights (null = the room is free). */
    fun overlapping(candidate: BookingEntity, all: List<BookingEntity>): BookingEntity? = all.firstOrNull { b ->
        b.id != candidate.id && b.roomNo == candidate.roomNo &&
            b.bookingStatus != BookingStatus.CANCELLED && b.bookingStatus != BookingStatus.CHECKED_OUT &&
            b.checkIn < candidate.checkOut && candidate.checkIn < b.checkOut
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%,.2f", v)
}
