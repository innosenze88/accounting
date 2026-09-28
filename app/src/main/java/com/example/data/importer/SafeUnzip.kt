package com.example.data.importer

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reading parts of a zip (.xlsx) with limits. A 20 MB .xlsx can unpack to gigabytes ("zip bomb" or just a
 * huge sheet); without a limit the phone runs out of memory and the app closes. Pure Kotlin (unit-tested).
 */
object SafeUnzip {
    /** Max size of one unpacked part (one worksheet / the shared strings). */
    const val MAX_ENTRY = 60L * 1024 * 1024
    /** Max unpacked size of everything read from one file. */
    const val MAX_TOTAL = 120L * 1024 * 1024

    class TooLargeException : IllegalArgumentException("ไฟล์ Excel ใหญ่เกินไปเมื่อแตกไฟล์ — ลองส่งออกเฉพาะช่วงวันที่ที่ต้องการ หรือบันทึกเป็น CSV")

    /** Keeps the running total of bytes unpacked from one file. */
    class Budget(private val maxTotal: Long = MAX_TOTAL) {
        var used = 0L
            private set

        fun take(n: Long) {
            used += n
            if (used > maxTotal) throw TooLargeException()
        }
    }

    /** Reads the current entry, at most [maxEntry] bytes, counting against [budget]. */
    fun readEntry(input: InputStream, budget: Budget, maxEntry: Long = MAX_ENTRY): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            size += n
            if (size > maxEntry) throw TooLargeException()
            budget.take(n.toLong())
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Reads and throws away the current entry (still counted, so many entries cannot add up without limit). */
    fun skipEntry(input: InputStream, budget: Budget, maxEntry: Long = MAX_ENTRY) {
        val buf = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            size += n
            if (size > maxEntry) throw TooLargeException()
            budget.take(n.toLong())
        }
    }

    /** "xl/worksheets/sheet2.xml" -> 2 (for picking the first sheet). */
    fun sheetNumber(name: String): Int =
        name.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE
}
