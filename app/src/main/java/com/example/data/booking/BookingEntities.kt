package com.example.data.booking

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Status of a direct booking (walk-in and OTA guests stay in eZee). */
enum class BookingStatus(val code: String, val titleTh: String) {
    BOOKED("BOOKED", "จองแล้ว"),
    CHECKED_IN("CHECKED_IN", "เข้าพักอยู่"),
    CHECKED_OUT("CHECKED_OUT", "เช็คเอาท์แล้ว"),
    CANCELLED("CANCELLED", "ยกเลิก");

    companion object {
        fun fromCode(code: String?): BookingStatus = entries.find { it.code == code } ?: BOOKED
    }
}

/** A direct booking made with the resort (not through eZee). */
@Entity(tableName = "bookings")
data class BookingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val guestName: String,
    val phone: String? = null,
    /** Thai ID card or passport number (for the guest register). */
    val idNumber: String? = null,
    val nationality: String? = null,
    val address: String? = null,
    val comeFrom: String? = null,
    val goTo: String? = null,
    val guests: Int = 1,
    val roomNo: String,
    val checkIn: String,
    val checkOut: String,
    val nightlyRate: Double,
    /** Agreed total for the stay (may differ from nights × rate after a discount). */
    val totalAmount: Double,
    val status: String = BookingStatus.BOOKED.code,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long? = null,
    /** When check-out / cancellation money was moved out of the advance wallet. */
    val settledAt: Long? = null
) {
    val bookingStatus: BookingStatus get() = BookingStatus.fromCode(status)
}

enum class PaymentKind(val code: String, val titleTh: String) {
    DEPOSIT("DEPOSIT", "มัดจำ"),
    BALANCE("BALANCE", "ชำระส่วนที่เหลือ"),
    REFUND("REFUND", "คืนเงิน");

    companion object {
        fun fromCode(code: String?): PaymentKind = entries.find { it.code == code } ?: DEPOSIT
    }
}

enum class PaymentMethod(val code: String, val titleTh: String) {
    TRANSFER("TRANSFER", "โอน"),
    PROMPTPAY("PROMPTPAY", "QR พร้อมเพย์"),
    CASH("CASH", "เงินสด"),
    CARD("CARD", "บัตร");

    companion object {
        fun fromCode(code: String?): PaymentMethod = entries.find { it.code == code } ?: TRANSFER
    }
}

/** Money received from (or refunded to) a direct-booking guest. Amount is always positive. */
@Entity(tableName = "booking_payments")
data class BookingPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookingId: Long,
    val kind: String,
    val method: String,
    val amount: Double,
    val date: String,
    val note: String? = null,
    /** Slip / receipt document scanned for this payment (evidence), if any. */
    val slipDocumentId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val voidedAt: Long? = null,
    val voidReason: String? = null
) {
    val paymentKind: PaymentKind get() = PaymentKind.fromCode(kind)
    val paymentMethod: PaymentMethod get() = PaymentMethod.fromCode(method)
    val isActive: Boolean get() = voidedAt == null
}

enum class WalletRole(val code: String) {
    /** Deposits of guests who have not checked out yet (not income yet). */
    ADVANCE("ADVANCE"),
    /** Normal budget wallet with a % of income (salary, electricity, ...). */
    BUDGET("BUDGET"),
    /** Savings wallet: gets its % plus rounding leftovers and month-end leftovers. */
    SAVINGS("SAVINGS");

    companion object {
        fun fromCode(code: String?): WalletRole = entries.find { it.code == code } ?: BUDGET
    }
}

/** One wallet ("กระเป๋า"), matching a pocket in MAKE by KBank. */
@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: String,
    /** Share of income in % (0 for the advance wallet). All BUDGET + SAVINGS wallets add up to 100. */
    val percent: Double,
    /** Money needed per month (e.g. salaries 22,400), to show if the wallet is on track. Optional. */
    val monthlyTarget: Double? = null,
    val sortOrder: Int = 0,
    val active: Boolean = true
) {
    val walletRole: WalletRole get() = WalletRole.fromCode(role)
}

enum class WalletTxnKind(val code: String, val titleTh: String) {
    ADVANCE_IN("ADVANCE_IN", "รับมัดจำ"),
    ADVANCE_OUT("ADVANCE_OUT", "ย้ายออกเมื่อเช็คเอาท์/ยกเลิก"),
    REFUND("REFUND", "คืนเงินลูกค้า"),
    ALLOCATION("ALLOCATION", "แบ่งรายได้"),
    EXPENSE("EXPENSE", "จ่ายค่าใช้จ่าย"),
    MONTH_END_OUT("MONTH_END_OUT", "ปิดเดือน ย้ายไปเงินเก็บ"),
    MONTH_END_IN("MONTH_END_IN", "ปิดเดือน รับจากกระเป๋าอื่น"),
    ADJUST("ADJUST", "ปรับยอด");

    companion object {
        fun fromCode(code: String?): WalletTxnKind = entries.find { it.code == code } ?: ADJUST
    }
}

/** Where a wallet movement came from. */
enum class TxnSource(val code: String) {
    PAYMENT("PAYMENT"), BOOKING("BOOKING"), EZEE("EZEE"), DOCUMENT("DOCUMENT"), MONTH_END("MONTH_END"), MANUAL("MANUAL")
}

/** Money moving into (+) or out of (−) a wallet. Never deleted; cancelled movements get [voidedAt]. */
@Entity(tableName = "wallet_txns")
data class WalletTxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walletId: Long,
    val amount: Double,
    val kind: String,
    val date: String,
    val sourceType: String,
    val sourceId: Long? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** When the person moved this money in MAKE (null = still to transfer). */
    val transferredAt: Long? = null,
    val voidedAt: Long? = null
) {
    val txnKind: WalletTxnKind get() = WalletTxnKind.fromCode(kind)
    val isActive: Boolean get() = voidedAt == null
}

/** One closed day (ปิดยอดประจำวัน). */
@Entity(tableName = "day_closes")
data class DayCloseEntity(
    @PrimaryKey val date: String,
    val closedAt: Long,
    /** Money received today from eZee (walk-in / OTA) that was split into the wallets. */
    val ezeeIncome: Double = 0.0,
    val cashExpected: Double? = null,
    val cashCounted: Double? = null,
    val note: String? = null
)
