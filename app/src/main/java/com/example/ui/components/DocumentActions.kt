package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ExtractedDocumentEntity
import com.example.data.local.documentStatus
import com.example.data.model.DocumentStatus
import java.util.Locale

/** One line that identifies a document: #id • date • amount • name. */
fun documentSummary(e: ExtractedDocumentEntity): String = listOfNotNull(
    "#${e.id}",
    e.date,
    e.totalAmount?.let { String.format(Locale.US, "%,.2f ฿", it) },
    (e.sellerName ?: e.customerName)?.takeIf { it.isNotBlank() },
    e.documentNo?.takeIf { it.isNotBlank() }?.let { "เลขที่ $it" }
).joinToString(" • ")

/** Asks before deleting a document that was never counted (PENDING / REJECTED / sample). */
@Composable
fun ConfirmDeleteDialog(entity: ExtractedDocumentEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ลบเอกสารนี้?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(documentSummary(entity), fontSize = 13.sp)
                Text(
                    "เอกสารนี้ยังไม่ถูกนับในยอด ลบแล้วรูปต้นฉบับจะหายด้วย และกู้คืนไม่ได้",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm_delete_button")) {
                Text("ลบ", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ไม่ลบ") } }
    )
}

private val QUICK_REASONS = listOf("สลิปซ้ำ", "ยอดผิด จะบันทึกใหม่", "ลูกค้ายกเลิก/คืนเงิน", "บันทึกผิดประเภท")

/**
 * Cancels a counted document. A reason is required; the document stays in the history as a record
 * and stops counting in every total.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoidDocumentDialog(entity: ExtractedDocumentEntity, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember(entity.id) { mutableStateOf("") }
    val ok = reason.trim().length >= 3
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ยกเลิกรายการนี้?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(documentSummary(entity), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "เอกสารที่ยืนยันแล้วลบไม่ได้ — ยกเลิกแล้วจะไม่นับในยอด แต่ยังเก็บไว้ในประวัติพร้อมเหตุผล " +
                        "และจะย้ายออกจาก Google Sheets (ไปไว้แผ่น \"Voided\")",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_REASONS.forEach { r ->
                        FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(r, fontSize = 12.sp) })
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("เหตุผลที่ยกเลิก (จำเป็น)") },
                    isError = reason.isNotEmpty() && !ok,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("void_reason_field")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason.trim()) },
                enabled = ok,
                modifier = Modifier.testTag("confirm_void_button")
            ) { Text("ยกเลิกรายการ", color = if (ok) MaterialTheme.colorScheme.error else Color.Unspecified) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ไม่ยกเลิก") } }
    )
}

/** Red card: this document looks like one that is already saved (maybe the same slip twice). */
@Composable
fun DuplicateWarningCard(duplicate: ExtractedDocumentEntity, modifier: Modifier = Modifier) {
    val counted = duplicate.documentStatus() == DocumentStatus.VERIFIED
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("duplicate_warning"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (counted) "⚠ อาจซ้ำกับเอกสารที่นับยอดไปแล้ว" else "⚠ อาจซ้ำกับเอกสารที่รอตรวจอยู่",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color(0xFFB71C1C)
            )
            Text(documentSummary(duplicate) + " (${duplicate.documentStatus().titleTh})", fontSize = 12.sp, color = Color(0xFFB71C1C))
            Text(
                "ถ้าเป็นสลิป/ใบเสร็จใบเดียวกัน ให้กด \"ปฏิเสธ\" — ถ้ายืนยัน ยอดจะถูกนับซ้ำ",
                fontSize = 11.sp,
                color = Color(0xFF6D1B1B)
            )
        }
    }
}

/** Grey card on a voided document: when and why it was cancelled. */
@Composable
fun VoidedInfoCard(entity: ExtractedDocumentEntity, modifier: Modifier = Modifier) {
    val at = entity.voidedAt?.let {
        java.text.SimpleDateFormat("d MMM yyyy HH:mm", Locale("th", "TH")).format(java.util.Date(it))
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECEFF1))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("ยกเลิกรายการแล้ว — ไม่นับในยอด", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF37474F))
            Text("เหตุผล: ${entity.voidReason ?: "-"}", fontSize = 12.sp, color = Color(0xFF37474F))
            at?.let { Text("เมื่อ: $it", fontSize = 11.sp, color = Color(0xFF546E7A)) }
        }
    }
}
