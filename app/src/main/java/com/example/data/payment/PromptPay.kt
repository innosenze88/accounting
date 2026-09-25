package com.example.data.payment

import java.util.Locale

/**
 * Thai QR / PromptPay payload (EMVCo merchant-presented QR), made on the phone — no internet, no fee.
 * Any Thai banking app can scan it; with an amount, the amount is filled in for the payer.
 */
object PromptPay {

    private const val AID = "A000000677010111"

    enum class IdType { PHONE, TAX_ID, EWALLET }

    /** Recognises a phone number (10 digits), a tax / citizen ID (13) or an e-wallet ID (15). */
    fun idType(id: String): IdType? {
        val d = id.filter { it.isDigit() }
        return when (d.length) {
            10, 9 -> IdType.PHONE
            11 -> if (d.startsWith("66")) IdType.PHONE else null
            13 -> if (d.startsWith("0066")) IdType.PHONE else IdType.TAX_ID
            15 -> IdType.EWALLET
            else -> null
        }
    }

    /** Payload text for a QR code. [amount] = null for a QR where the payer types the amount. */
    fun payload(id: String, amount: Double? = null): String {
        val digits = id.filter { it.isDigit() }
        val type = requireNotNull(idType(digits)) { "เลขพร้อมเพย์ไม่ถูกต้อง (ต้องเป็นเบอร์มือถือ 10 หลัก หรือเลขประจำตัว 13 หลัก)" }
        val account = when (type) {
            IdType.PHONE -> "01" + field(phoneTarget(digits))
            IdType.TAX_ID -> "02" + field(digits)
            IdType.EWALLET -> "03" + field(digits)
        }
        val sb = StringBuilder()
        sb.append(tag("00", "01"))
        sb.append(tag("01", if (amount != null) "12" else "11")) // 12 = one-time (with amount), 11 = reusable
        sb.append(tag("29", tag("00", AID) + account))
        sb.append(tag("58", "TH"))
        sb.append(tag("53", "764")) // THB
        if (amount != null) {
            require(amount > 0) { "ยอดเงินต้องมากกว่า 0" }
            sb.append(tag("54", String.format(Locale.US, "%.2f", amount)))
        }
        sb.append("6304")
        sb.append(crc16(sb.toString()))
        return sb.toString()
    }

    /** 0812345678 -> 0066812345678 */
    private fun phoneTarget(digits: String): String {
        val local = when {
            digits.startsWith("0066") -> digits.substring(4)
            digits.startsWith("66") && digits.length == 11 -> digits.substring(2)
            digits.startsWith("0") -> digits.substring(1)
            else -> digits
        }
        return ("0066" + local).padStart(13, '0')
    }

    private fun field(value: String) = String.format(Locale.US, "%02d", value.length) + value

    private fun tag(id: String, value: String) = id + field(value)

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), 4 upper-case hex digits. */
    fun crc16(data: String): String {
        var crc = 0xFFFF
        for (b in data.toByteArray(Charsets.US_ASCII)) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return String.format(Locale.US, "%04X", crc)
    }
}
