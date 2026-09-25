package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AccountingDocumentJson
import com.example.data.model.DocumentStatus
import com.example.data.model.DocumentType
import com.example.data.model.TransactionType
import java.util.Locale
import kotlin.math.abs

private val DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")

/** Parses "1,250.00" / " 1250 " -> 1250.0; blank -> null; invalid -> NaN (flagged as error). */
private fun parseAmount(text: String): Double? {
    val t = text.replace(",", "").trim()
    if (t.isEmpty()) return null
    return t.toDoubleOrNull() ?: Double.NaN
}

private fun Double?.toInput(): String = this?.let { String.format(Locale.US, "%.2f", it) } ?: ""

/**
 * Editable review form for an AI-extracted document.
 * The person must check the values and press "ยืนยัน" before the document counts in totals.
 *
 * @param formKey change this to reset the form (e.g. record id)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentReviewForm(
    initial: AccountingDocumentJson,
    formKey: Any,
    onVerify: (AccountingDocumentJson) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var docType by rememberSaveable(formKey) { mutableStateOf(DocumentType.fromCode(initial.documentType).code) }
    var txType by rememberSaveable(formKey) {
        mutableStateOf(TransactionType.fromCode(initial.transactionType)?.code ?: "")
    }
    var documentNo by rememberSaveable(formKey) { mutableStateOf(initial.documentNo ?: "") }
    var date by rememberSaveable(formKey) { mutableStateOf(initial.date ?: "") }
    var sellerName by rememberSaveable(formKey) { mutableStateOf(initial.sellerName ?: "") }
    var sellerTaxId by rememberSaveable(formKey) { mutableStateOf(initial.sellerTaxId ?: "") }
    var customerName by rememberSaveable(formKey) { mutableStateOf(initial.customerName ?: "") }
    var customerTaxId by rememberSaveable(formKey) { mutableStateOf(initial.customerTaxId ?: "") }
    var subtotal by rememberSaveable(formKey) { mutableStateOf(initial.subtotal.toInput()) }
    var vat by rememberSaveable(formKey) { mutableStateOf(initial.vatAmount.toInput()) }
    var total by rememberSaveable(formKey) { mutableStateOf(initial.totalAmount.toInput()) }
    var deposit by rememberSaveable(formKey) { mutableStateOf(initial.depositAmount.toInput()) }
    var paymentMethod by rememberSaveable(formKey) { mutableStateOf(initial.paymentMethod ?: "") }

    val subtotalVal = parseAmount(subtotal)
    val vatVal = parseAmount(vat)
    val totalVal = parseAmount(total)
    val depositVal = parseAmount(deposit)

    // ---- Validation (blocking errors) ----
    val errors = buildList {
        if (txType.isBlank()) add("เลือกว่าเป็นรายรับหรือรายจ่าย")
        if (totalVal == null) add("ต้องระบุยอดรวมทั้งสิ้น")
        listOf("มูลค่าก่อน VAT" to subtotalVal, "VAT" to vatVal, "ยอดรวม" to totalVal, "มัดจำ" to depositVal)
            .forEach { (label, v) -> if (v != null && v.isNaN()) add("$label ไม่ใช่ตัวเลข") }
        if (totalVal != null && !totalVal.isNaN() && totalVal < 0) add("ยอดรวมต้องไม่ติดลบ")
        if (date.isNotBlank() && !DATE_REGEX.matches(date.trim())) add("วันที่ต้องเป็นรูปแบบ YYYY-MM-DD (ค.ศ.)")
    }

    // ---- Warnings (do not block, but ask the person to double-check) ----
    val warnings = buildList {
        if (subtotalVal != null && vatVal != null && totalVal != null &&
            !subtotalVal.isNaN() && !vatVal.isNaN() && !totalVal.isNaN() &&
            abs(subtotalVal + vatVal - totalVal) > 0.01
        ) {
            add(String.format("มูลค่าก่อน VAT + VAT = %,.2f ไม่ตรงกับยอดรวม %,.2f", subtotalVal + vatVal, totalVal))
        }
        if (date.trim().take(4).toIntOrNull()?.let { it > 2400 } == true) add("ปีดูเหมือนเป็น พ.ศ. — ควรเป็น ค.ศ.")
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("review_form"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF57F17), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "รอตรวจสอบ — กรุณาเทียบกับรูปเอกสาร แก้ไขให้ถูกต้อง แล้วกดยืนยัน",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6D4C00)
                )
            }
            Text(
                "เอกสารที่ยังไม่ยืนยันจะไม่ถูกนับในยอดบัญชี",
                fontSize = 11.sp,
                color = Color(0xFF6D4C00)
            )

            SectionLabel("ประเภทรายการ")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TransactionType.entries.forEach { t ->
                    FilterChip(
                        selected = txType == t.code,
                        onClick = { txType = t.code },
                        label = { Text(t.titleTh, fontSize = 12.sp) },
                        modifier = Modifier.testTag("review_tx_${t.code}")
                    )
                }
            }

            SectionLabel("ประเภทเอกสาร")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DocumentType.entries.forEach { t ->
                    FilterChip(
                        selected = docType == t.code,
                        onClick = { docType = t.code },
                        label = { Text(t.titleTh, fontSize = 12.sp) }
                    )
                }
            }

            SectionLabel("ข้อมูลเอกสาร")
            Field("เลขที่เอกสาร", documentNo) { documentNo = it }
            Field("วันที่ (YYYY-MM-DD)", date, testTag = "review_date") { date = it }
            Field("ผู้ขาย / ผู้ออกบิล", sellerName) { sellerName = it }
            Field("เลขผู้เสียภาษีผู้ขาย", sellerTaxId, KeyboardType.Number) { sellerTaxId = it }
            Field("ลูกค้า", customerName) { customerName = it }
            Field("เลขผู้เสียภาษีลูกค้า", customerTaxId, KeyboardType.Number) { customerTaxId = it }
            Field("วิธีชำระเงิน", paymentMethod) { paymentMethod = it }

            SectionLabel("ยอดเงิน (บาท)")
            Field("มูลค่าก่อน VAT", subtotal, KeyboardType.Decimal) { subtotal = it }
            Field("VAT", vat, KeyboardType.Decimal) { vat = it }
            Field("ยอดรวมทั้งสิ้น *", total, KeyboardType.Decimal, testTag = "review_total") { total = it }
            Field("เงินมัดจำ", deposit, KeyboardType.Decimal) { deposit = it }

            errors.forEach { MessageLine(it, isError = true) }
            warnings.forEach { MessageLine(it, isError = false) }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("review_reject_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ปฏิเสธ", fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        onVerify(
                            initial.copy(
                                documentType = docType,
                                transactionType = txType,
                                documentNo = documentNo.trim().ifEmpty { null },
                                date = date.trim().ifEmpty { null },
                                sellerName = sellerName.trim().ifEmpty { null },
                                sellerTaxId = sellerTaxId.trim().ifEmpty { null },
                                customerName = customerName.trim().ifEmpty { null },
                                customerTaxId = customerTaxId.trim().ifEmpty { null },
                                subtotal = subtotalVal,
                                vatAmount = vatVal,
                                totalAmount = totalVal,
                                depositAmount = depositVal,
                                paymentMethod = paymentMethod.trim().ifEmpty { null }
                            )
                        )
                    },
                    enabled = errors.isEmpty(),
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("review_verify_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ยืนยันถูกต้อง", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    testTag: String? = null,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (testTag != null) it.testTag(testTag) else it }
    )
}

@Composable
private fun MessageLine(text: String, isError: Boolean) {
    Text(
        text = (if (isError) "✕ " else "⚠ ") + text,
        fontSize = 12.sp,
        color = if (isError) MaterialTheme.colorScheme.error else Color(0xFFE65100)
    )
}

/** Small colored label showing the review status of a saved document. */
@Composable
fun DocumentStatusBadge(statusCode: String?, isSample: Boolean = false, modifier: Modifier = Modifier) {
    val status = DocumentStatus.fromCode(statusCode)
    val (bg, fg, label) = when {
        isSample -> Triple(Color(0xFFECEFF1), Color(0xFF455A64), "ตัวอย่าง (ไม่นับยอด)")
        status == DocumentStatus.VERIFIED -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), status.titleTh)
        status == DocumentStatus.REJECTED -> Triple(Color(0xFFFFEBEE), Color(0xFFB71C1C), status.titleTh)
        status == DocumentStatus.VOIDED -> Triple(Color(0xFFECEFF1), Color(0xFF37474F), "${status.titleTh} (ไม่นับยอด)")
        else -> Triple(Color(0xFFFFF8E1), Color(0xFFE65100), status.titleTh)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("status_badge_${if (isSample) "SAMPLE" else status.code}")
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}
