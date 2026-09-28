package com.example

import com.example.data.importer.SafeUnzip
import com.example.data.network.ClaudeAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A small .xlsx that unpacks to something huge must be refused, not crash the app. */
class SafeUnzipTest {

    private fun zipOf(vararg entries: Pair<String, Int>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (name, size) ->
                z.putNextEntry(ZipEntry(name))
                val chunk = ByteArray(1024 * 1024) // zeros compress to almost nothing
                var left = size
                while (left > 0) { val n = minOf(left, chunk.size); z.write(chunk, 0, n); left -= n }
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun normalEntryIsRead() {
        val zip = ZipInputStream(ByteArrayInputStream(zipOf("xl/worksheets/sheet1.xml" to 5000)))
        zip.nextEntry
        assertEquals(5000, SafeUnzip.readEntry(zip, SafeUnzip.Budget()).size)
    }

    @Test
    fun hugeEntryIsRefused() {
        val bytes = zipOf("xl/worksheets/sheet1.xml" to 8 * 1024 * 1024)
        assertTrue("compressed file is small", bytes.size < 200_000)
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        zip.nextEntry
        val e = runCatching { SafeUnzip.readEntry(zip, SafeUnzip.Budget(), maxEntry = 4L * 1024 * 1024) }.exceptionOrNull()
        assertNotNull(e as? SafeUnzip.TooLargeException)
    }

    @Test
    fun manyEntriesCountTogether() {
        val zip = ZipInputStream(ByteArrayInputStream(zipOf("a" to 3 * 1024 * 1024, "b" to 3 * 1024 * 1024)))
        val budget = SafeUnzip.Budget(maxTotal = 5L * 1024 * 1024)
        zip.nextEntry
        SafeUnzip.skipEntry(zip, budget)
        zip.nextEntry
        assertNotNull(runCatching { SafeUnzip.skipEntry(zip, budget) }.exceptionOrNull())
    }

    @Test
    fun firstSheetNumber() {
        assertEquals(2, SafeUnzip.sheetNumber("xl/worksheets/sheet2.xml"))
        assertEquals(Int.MAX_VALUE, SafeUnzip.sheetNumber("xl/worksheets/sheetX.xml"))
    }

    @Test
    fun claudeKeyHeader() {
        assertFalse(ClaudeAuth.preferBearer("sk-ant-api03-abc"))
        assertTrue(ClaudeAuth.preferBearer("sk-ant-oat01-abc"))
    }
}
