package com.example

import com.example.data.importer.FileImporter
import com.example.data.importer.FileKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileImporterTest {

    @Test
    fun `csv with quotes, commas, thai text and BOM`() {
        val csv = "﻿วันที่,รายการ,จำนวนเงิน\r\n2026-09-01,\"ค่าห้อง, 2 คืน\",\"1,500.00\"\r\n\r\n2026-09-02,\"He said \"\"hi\"\"\",200\r\n"
        val table = FileImporter.parseCsv(FileImporter.decodeText(csv.toByteArray(Charsets.UTF_8)))
        assertEquals(listOf("วันที่", "รายการ", "จำนวนเงิน"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("ค่าห้อง, 2 คืน", table.rows[0][1])
        assertEquals("1,500.00", table.rows[0][2])
        assertEquals("He said \"hi\"", table.rows[1][1])
    }

    @Test
    fun `windows-874 thai csv is decoded`() {
        val bytes = "ชื่อ,ยอด\nทดสอบ,10\n".toByteArray(charset("windows-874"))
        val table = FileImporter.parseCsv(FileImporter.decodeText(bytes))
        assertEquals("ชื่อ", table.headers[0])
        assertEquals("ทดสอบ", table.rows[0][0])
    }

    @Test
    fun `xlsx first sheet with shared strings, numbers and gaps`() {
        val shared = """<?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
              <si><t>Guest</t></si><si><t>Amount</t></si><si><t>สมชาย</t></si>
            </sst>""".trimIndent()
        val sheet = """<?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
              <row r="2"><c r="A2" t="s"><v>2</v></c><c r="C2"><v>1250.5</v></c></row>
              <row r="3"><c r="A3" t="inlineStr"><is><t>Inline</t></is></c><c r="C3"><v>99</v></c></row>
            </sheetData></worksheet>""".trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml")); zip.write(shared.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); zip.write(sheet.toByteArray()); zip.closeEntry()
        }
        val table = FileImporter.parseXlsx(out.toByteArray())
        assertEquals(listOf("Guest", "column_2", "Amount"), table.headers)
        assertEquals(listOf("สมชาย", "", "1250.5"), table.rows[0])
        assertEquals(listOf("Inline", "", "99"), table.rows[1])
    }

    @Test
    fun `html table saved as xls`() {
        val html = "<html><body><table><tr><th>Room</th><th>Rate</th></tr><tr><td>101</td><td>1&nbsp;200</td></tr></table></body></html>"
        val bytes = html.toByteArray()
        assertEquals(FileKind.HTML_TABLE, FileImporter.detectKind("report.xls", "application/vnd.ms-excel", bytes))
        val table = FileImporter.parseHtmlTable(html)
        assertEquals(listOf("Room", "Rate"), table.headers)
        assertEquals(listOf("101", "1 200"), table.rows[0])
    }

    @Test
    fun `kind detection`() {
        assertEquals(FileKind.PDF, FileImporter.detectKind("x.bin", null, "%PDF-1.7".toByteArray()))
        assertEquals(FileKind.CSV, FileImporter.detectKind("a.csv", "text/csv", "a,b".toByteArray()))
        assertEquals(FileKind.XLSX, FileImporter.detectKind("a.xlsx", null, "PK..".toByteArray()))
        assertEquals(FileKind.IMAGE, FileImporter.detectKind("slip.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))
    }
}
