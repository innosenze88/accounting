package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.backup.BackupManager
import com.example.ui.viewmodel.BackupViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun whenText(t: Long): String =
    if (t <= 0) "ยังไม่เคย" else SimpleDateFormat("d MMM yyyy HH:mm", Locale("th", "TH")).format(Date(t))

/** "สำรองข้อมูล / กู้คืน" in the Settings tab. */
@Composable
fun BackupCard(vm: BackupViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickRestore(uri)
    }
    var editPassword by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().testTag("backup_card"), shape = RoundedCornerShape(16.dp), border = appCardBorder()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("สำรองข้อมูล / กู้คืน", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("สำรองล่าสุด: ${whenText(s.lastBackupAt)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                "รวมเอกสาร รูปต้นฉบับ รายงาน การจอง กระเป๋า เอกสารที่ออก ตัวอ่าน และข้อมูลรีสอร์ท ไว้ในไฟล์เดียว " +
                    "(ไม่รวม API key และรหัส Google Sheets — ต้องใส่ใหม่เมื่อย้ายเครื่อง)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Password protection: the backup holds guests' ID / passport numbers (PDPA).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.hasPassword) "🔒 ไฟล์สำรองเข้ารหัสด้วยรหัสผ่าน"
                    else "⚠ ไฟล์สำรองยังไม่มีรหัสผ่าน (มีเลขบัตรประชาชน/พาสปอร์ตแขก)",
                    fontSize = 12.sp,
                    color = if (s.hasPassword) Color(0xFF2E7D32) else Color(0xFFE65100),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { editPassword = true }, enabled = !s.busy) {
                    Text(if (s.hasPassword) "เปลี่ยน" else "ตั้งรหัส", fontSize = 12.sp)
                }
            }

            Button(
                onClick = { saveLauncher.launch(BackupManager.fileName() + if (s.hasPassword) ".enc" else "") },
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth().testTag("backup_save_button")
            ) { Text("สำรองตอนนี้ → บันทึกเป็นไฟล์ (เลือก Google Drive ได้)") }
            OutlinedButton(onClick = { vm.share() }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
                Text("สำรองแล้วส่งต่อ (LINE Keep / อีเมล)")
            }

            // Automatic daily backup to Google Drive (through the same Apps Script as Google Sheets)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("สำรองขึ้น Google Drive อัตโนมัติวันละครั้ง", fontSize = 13.sp)
                    Text(
                        if (!s.sheetsReady) "ต้องตั้งค่า Google Sheets ก่อน"
                        else "ล่าสุด: ${whenText(s.lastDriveBackupAt)} • เก็บ 7 ไฟล์ล่าสุด",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = s.autoDrive, onCheckedChange = { vm.setAutoDrive(it) }, enabled = s.sheetsReady)
            }
            s.lastDriveError?.let {
                Text("✕ ครั้งล่าสุดไม่สำเร็จ: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = { vm.uploadToDriveNow() },
                enabled = !s.busy && s.sheetsReady,
                modifier = Modifier.fillMaxWidth()
            ) { Text("สำรองขึ้น Google Drive ตอนนี้") }

            TextButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !s.busy) {
                Text("กู้คืนจากไฟล์สำรอง...", color = MaterialTheme.colorScheme.error)
            }

            s.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (it.startsWith("✕")) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                )
            }
        }
    }

    s.pendingRestore?.let { (_, info) ->
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("กู้คืนข้อมูลจากไฟล์นี้?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("สำรองเมื่อ: ${whenText(info.createdAt)} (แอปเวอร์ชัน ${info.appVersion})", fontSize = 13.sp)
                    Text("เอกสาร ${info.documents} • การจอง ${info.bookings} • ไฟล์ ${info.files} • ขนาด ${info.sizeBytes / 1024} KB", fontSize = 13.sp)
                    Text(
                        "ข้อมูลทั้งหมดในเครื่องตอนนี้จะถูกแทนที่ด้วยข้อมูลในไฟล์ (แอปจะสำรองข้อมูลปัจจุบันเก็บไว้ในเครื่องก่อน 1 ชุด) " +
                            "จากนั้นแอปจะปิดและเปิดใหม่",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmRestore() }, modifier = Modifier.testTag("confirm_restore_button")) {
                    Text("กู้คืน", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { vm.cancelRestore() }) { Text("ยกเลิก") } }
        )
    }

    if (editPassword) BackupPasswordDialog(s.hasPassword, onSave = { vm.setBackupPassword(it) { editPassword = false } }) { editPassword = false }
    s.askPasswordFor?.let { uri ->
        RestorePasswordDialog(s.passwordWasWrong, onOpen = { vm.pickRestore(uri, it) }, onDismiss = { vm.cancelPassword() })
    }
}

@Composable
private fun BackupPasswordDialog(hasPassword: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val tooShort = pw.isNotEmpty() && pw.length < com.example.data.backup.BackupCrypto.MIN_PASSWORD
    val mismatch = again.isNotEmpty() && again != pw
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("รหัสผ่านไฟล์สำรอง") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "ไฟล์สำรองใหม่ทุกไฟล์ (บันทึก / ส่งต่อ / Google Drive) จะเข้ารหัสด้วยรหัสนี้ " +
                        "ต้องใช้รหัสเดียวกันตอนกู้คืน — ถ้าลืมรหัส จะเปิดไฟล์ไม่ได้",
                    fontSize = 12.sp
                )
                OutlinedTextField(pw, { pw = it }, label = { Text("รหัสผ่าน (อย่างน้อย 6 ตัว)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(again, { again = it }, label = { Text("พิมพ์อีกครั้ง") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (tooShort) Text("สั้นเกินไป", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                if (mismatch) Text("พิมพ์ไม่ตรงกัน", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                if (hasPassword) TextButton(onClick = { onSave("") }) {
                    Text("เลิกใช้รหัสผ่าน (ไม่แนะนำ)", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(pw) }, enabled = pw.isNotEmpty() && !tooShort && pw == again) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

@Composable
private fun RestorePasswordDialog(wrong: Boolean, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ไฟล์สำรองนี้มีรหัสผ่าน") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wrong) Text("รหัสผ่านไม่ถูกต้อง ลองอีกครั้ง", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(pw, { pw = it }, label = { Text("รหัสผ่านไฟล์สำรอง") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(pw) }, enabled = pw.isNotEmpty()) { Text("เปิดไฟล์") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

/** Reminder on the dashboard when there was no backup for a week. */
@Composable
fun BackupReminder(vm: BackupViewModel, onOpenSettings: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val days = if (s.lastBackupAt <= 0) null else ((System.currentTimeMillis() - s.lastBackupAt) / 86_400_000L).toInt()
    if (days != null && days < 7) return
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).testTag("backup_reminder"),
        shape = RoundedCornerShape(16.dp),
        border = appCardBorder()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (days == null) "ยังไม่เคยสำรองข้อมูล" else "ไม่ได้สำรองข้อมูลมา $days วัน",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error
            )
            Text("ถ้ามือถือหายหรือพัง ข้อมูลจะหาย — แตะเพื่อสำรองข้อมูล", fontSize = 12.sp)
        }
    }
}
