package com.example.ui.screens.booking

import android.app.DatePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.docs.ThaiDate
import com.example.data.payment.QrImage
import com.example.data.wallet.WalletMath
import java.util.Locale

/** 1,234.50 */
internal fun baht(v: Double): String = String.format(Locale.US, "%,.2f", v)

/** "1,234.5" / "1234" -> 1234.5 ; blank or bad -> null */
internal fun parseMoney(s: String): Double? = s.replace(",", "").trim().toDoubleOrNull()

internal fun previousMonth(ym: String): String = WalletMath.addDays("$ym-01", -1).take(7)

internal val Green = Color(0xFF2E7D32)
internal val Orange = Color(0xFFE65100)

/** Date field that opens the Android date picker. Value is yyyy-MM-dd. */
@Composable
internal fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun open() {
        val parts = value.split("-").mapNotNull { it.toIntOrNull() }
        val c = java.util.Calendar.getInstance()
        val y = parts.getOrNull(0) ?: c.get(java.util.Calendar.YEAR)
        val m = (parts.getOrNull(1) ?: (c.get(java.util.Calendar.MONTH) + 1)) - 1
        val d = parts.getOrNull(2) ?: c.get(java.util.Calendar.DAY_OF_MONTH)
        DatePickerDialog(context, { _, yy, mm, dd ->
            onChange(String.format(Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd))
        }, y, m, d).show()
    }
    Card(
        modifier = modifier.clickable { open() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (WalletMath.isIsoDate(value)) ThaiDate.long(value) else "แตะเพื่อเลือกวันที่", fontSize = 14.sp)
            }
            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun MoneyField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onChange(t.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = { Text(label) },
        singleLine = true,
        suffix = { Text("บาท") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
internal fun TextInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier
    )
}

/** A row of chips to pick one option (scrolls sideways when long). */
@Composable
internal fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

/** Result message from the view model (✓ success / ✕ error / ⚠ warning). */
@Composable
internal fun MessageBanner(message: String?, onClose: () -> Unit) {
    if (message.isNullOrBlank()) return
    val error = message.startsWith("✕")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else Color(0xFFE8F5E9)
        )
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF1B5E20))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "ปิด") }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
}

/** PromptPay QR for the guest to scan. */
@Composable
internal fun QrDialog(payload: String, amount: Double?, onDismiss: () -> Unit) {
    val image = remember(payload) { QrImage.bitmap(payload, 640).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("สแกนจ่ายด้วยพร้อมเพย์") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(bitmap = image, contentDescription = "QR พร้อมเพย์", modifier = Modifier.size(260.dp))
                if (amount != null && amount > 0) Text("${baht(amount)} บาท", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("ให้ลูกค้าสแกนด้วยแอปธนาคาร แล้วเช็คว่าเงินเข้าก่อนกดบันทึก", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } }
    )
}

/** Ask for a reason (void a payment / document, cancel a booking). */
@Composable
internal fun ReasonDialog(title: String, warning: String, confirmText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(warning, fontSize = 13.sp)
                TextInput("เหตุผล", reason, { reason = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }, enabled = reason.isNotBlank()) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ไม่ใช่") } }
    )
}
