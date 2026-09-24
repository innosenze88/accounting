package com.example.data.ezee

import kotlin.math.abs
import kotlin.math.max

/**
 * One character read from a PDF page.
 * [x] = left edge, [y] = baseline measured from the TOP of the page, [height] = font size.
 */
data class PdfGlyph(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val text: String
)

/**
 * Rebuilds readable text lines from PDF glyph positions (like `pdftotext -layout`).
 * Words are separated by one space and table columns by at least [COLUMN_GAP] (3 spaces),
 * so a parser can split columns with `\s{2,}`.
 *
 * Pure Kotlin (no Android classes) so it is unit-tested on the JVM.
 */
object PdfLineBuilder {

    const val COLUMN_GAP = "   "

    fun build(glyphs: List<PdfGlyph>): List<String> {
        val merged = attachCombiningMarks(glyphs)
        if (merged.isEmpty()) return emptyList()

        // Group glyphs whose baselines are close into one line (top to bottom).
        val lines = mutableListOf<MutableList<PdfGlyph>>()
        val baselines = mutableListOf<Float>()
        for (g in merged.sortedBy { it.y }) {
            val size = g.height.coerceAtLeast(1f)
            val idx = baselines.indices.lastOrNull { i ->
                val lineSize = lines[i].maxOf { it.height }.coerceAtLeast(1f)
                abs(baselines[i] - g.y) <= 0.45f * max(size, lineSize)
            }
            if (idx == null) {
                lines.add(mutableListOf(g))
                baselines.add(g.y)
            } else {
                lines[idx].add(g)
                // Running average keeps the line baseline stable.
                baselines[idx] = (baselines[idx] * (lines[idx].size - 1) + g.y) / lines[idx].size
            }
        }

        return lines.indices.sortedBy { baselines[it] }.map { i -> render(lines[i]) }.filter { it.isNotBlank() }
    }

    /** Thai vowels/tone marks above/below (and other combining marks) stay with the previous character. */
    private fun attachCombiningMarks(glyphs: List<PdfGlyph>): List<PdfGlyph> {
        val out = mutableListOf<PdfGlyph>()
        for (g in glyphs) {
            if (g.text.isEmpty()) continue
            val first = g.text.first()
            if (out.isNotEmpty() && !out.last().text.isBlank() &&
                Character.getType(first) == Character.NON_SPACING_MARK.toInt()
            ) {
                val prev = out.removeAt(out.size - 1)
                out.add(prev.copy(text = prev.text + g.text))
            } else {
                out.add(g)
            }
        }
        return out
    }

    private fun isThai(c: Char) = c in '\u0E00'..'\u0E7F'

    private fun render(line: List<PdfGlyph>): String {
        val sorted = line.sortedBy { it.x }
        val sb = StringBuilder()
        var prev: PdfGlyph? = null
        for (g in sorted) {
            if (g.text.isBlank()) {
                // A real space character from the PDF: always a word break.
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                continue
            }
            val p = prev
            // Some eZee reports draw bold text by printing the same letter several times on top of itself.
            if (p != null && p.text == g.text && abs(g.x - p.x) < max(0.5f, p.width * 0.5f)) continue
            if (p != null) {
                val gap = g.x - (p.x + p.width)
                val size = max(p.height, g.height).coerceAtLeast(1f)
                // Thai fonts in eZee PDFs report narrow widths, so Thai text needs a much bigger gap to split.
                val thai = isThai(p.text.last()) && isThai(g.text.first())
                when {
                    gap > size * (if (thai) 2.5f else 0.9f) -> {
                        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                        sb.append(COLUMN_GAP)
                    }
                    !thai && gap > size * 0.12f && sb.isNotEmpty() && sb.last() != ' ' -> sb.append(' ')
                }
            }
            sb.append(g.text)
            prev = g
        }
        return sb.toString().trim()
    }
}
