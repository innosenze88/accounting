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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }
    val saveLockedLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }
    var showPasswordDialog by remember { mutableStateOf(false) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickRestore(uri)
    }

    Card(modifier = Modifier.fillMaxWidth().testTag("backup_card"), shape = RoundedCornerShape(12.dp)) {
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

            // Password lock for backups that leave the phone (they hold guest names, ID numbers and all accounts).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.passwordSet) "🔒 ไฟล์สำรองล็อกด้วยรหัสผ่าน" else "ไฟล์สำรองยังไม่ได้ตั้งรหัสผ่าน",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (s.passwordSet) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                    Text(
                        if (s.passwordSet) "ใช้กับไฟล์ที่บันทึก ส่งต่อ และสำรองขึ้น Google Drive"
                        else "ถ้าไฟล์หลุดไปถึงคนอื่น จะเปิดดูข้อมูลลูกค้าและบัญชีได้ทันที",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showPasswordDialog = true }, enabled = !s.busy, modifier = Modifier.testTag("backup_password_button")) {
                    Text(if (s.passwordSet) "เปลี่ยน" else "ตั้งรหัส")
                }
            }

            Button(
                onClick = {
                    if (s.passwordSet) saveLockedLauncher.launch(BackupManager.fileName(locked = true))
                    else saveLauncher.launch(BackupManager.fileName())
                },
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

    if (showPasswordDialog) {
        BackupPasswordDialog(
            passwordSet = s.passwordSet,
            onSave = { p, c -> vm.setPassword(p, c).also { if (it == null) showPasswordDialog = false } },
            onRemove = { vm.clearPassword(); showPasswordDialog = false },
            onDismiss = { showPasswordDialog = false }
        )
    }

    if (s.pendingLocked != null) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("ไฟล์สำรองนี้ล็อกไว้") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ใส่รหัสผ่านที่ตั้งไว้ตอนสำรองข้อมูล", fontSize = 13.sp)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("รหัสผ่าน") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("unlock_password_field")
                    )
                    s.unlockError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.unlockRestore(password) }, enabled = password.isNotEmpty() && !s.busy) {
                    Text("เปิดไฟล์")
                }
            },
            dismissButton = { TextButton(onClick = { vm.cancelRestore() }) { Text("ยกเลิก") } }
        )
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
}

/** Set / change / remove the password that locks backup files. */
@Composable
private fun BackupPasswordDialog(
    passwordSet: Boolean,
    onSave: (String, String) -> String?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (passwordSet) "เปลี่ยนรหัสผ่านไฟล์สำรอง" else "ตั้งรหัสผ่านไฟล์สำรอง") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "ถ้าลืมรหัสผ่าน จะกู้คืนไฟล์สำรองที่ล็อกไว้ไม่ได้เลย — จดเก็บไว้ในที่ปลอดภัย",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
                if (passwordSet) {
                    Text("ไฟล์เก่ายังต้องใช้รหัสเดิมเปิด", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it; problem = null },
                    label = { Text("รหัสผ่านใหม่ (อย่างน้อย 8 ตัว)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("backup_password_field")
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it; problem = null },
                    label = { Text("ใส่รหัสผ่านอีกครั้ง") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("backup_password_confirm_field")
                )
                problem?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                if (passwordSet) {
                    TextButton(onClick = onRemove) { Text("ยกเลิกรหัสผ่าน (ไฟล์สำรองต่อจากนี้ไม่ล็อก)", fontSize = 12.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { problem = onSave(password, confirm) }) { Text("บันทึก") } },
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
        shape = RoundedCornerShape(14.dp)
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
