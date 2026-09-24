package com.example.ui.components

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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.AccountantViewModel
import com.example.ui.viewmodel.AccountantViewModel.UpdateState
import java.util.Locale

/** "อัปเดตแอป": check GitHub Releases, download, install on top (data is kept). */
@Composable
fun UpdateCard(viewModel: AccountantViewModel) {
    val state by viewModel.updateState.collectAsStateWithLifecycle()

    // Back from Android's "Install unknown apps" page: continue installing if it is now allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state is UpdateState.ReadyToInstall && viewModel.canInstallUpdates()) viewModel.installUpdate()
    }

    Card(modifier = Modifier.fillMaxWidth().testTag("update_card"), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("อัปเดตแอป", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "เวอร์ชันที่ใช้อยู่: ${viewModel.currentAppVersion}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (val s = state) {
                UpdateState.Idle -> CheckButton(viewModel)
                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("กำลังตรวจสอบกับ GitHub...", fontSize = 13.sp)
                }
                UpdateState.UpToDate -> {
                    Text("✓ ใช้เวอร์ชันล่าสุดอยู่แล้ว", color = Color(0xFF2E7D32), fontSize = 13.sp)
                    CheckButton(viewModel, "ตรวจสอบอีกครั้ง")
                }
                is UpdateState.Available -> {
                    Text("มีเวอร์ชันใหม่: ${s.info.versionName}", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    if (s.info.title.isNotBlank() && s.info.title != "v${s.info.versionName}") Text(s.info.title, fontSize = 13.sp)
                    if (s.info.notes.isNotBlank()) {
                        Text(s.info.notes.take(800), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "ขนาด ${String.format(Locale.US, "%.1f", s.info.apkSize / 1_048_576.0)} MB • " +
                            "ติดตั้งทับของเดิม ข้อมูลบัญชี ไฟล์ ตัวอ่าน และการตั้งค่า อยู่ครบ",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { viewModel.downloadUpdate(s.info) },
                        modifier = Modifier.fillMaxWidth().testTag("update_download_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("ดาวน์โหลดและอัปเดต") }
                }
                is UpdateState.Downloading -> {
                    Text("กำลังดาวน์โหลดเวอร์ชัน ${s.info.versionName}...", fontSize = 13.sp)
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(s.progress * 100).toInt()}%", fontSize = 11.sp)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is UpdateState.ReadyToInstall -> {
                    Text("ดาวน์โหลดเสร็จ ✓ — กด \"ติดตั้ง\" ในหน้าจอของ Android", fontSize = 13.sp, color = Color(0xFF2E7D32))
                    if (!viewModel.canInstallUpdates()) {
                        Text(
                            "ครั้งแรก: Android จะให้เปิด \"อนุญาตจากแหล่งที่มานี้\" ให้แอปนี้ก่อน แล้วกดย้อนกลับมา",
                            fontSize = 11.sp,
                            color = Color(0xFFE65100)
                        )
                    }
                    Button(
                        onClick = { viewModel.installUpdate() },
                        modifier = Modifier.fillMaxWidth().testTag("update_install_button")
                    ) { Text("ติดตั้งเวอร์ชัน ${s.info.versionName}") }
                    Text(
                        "ถ้า Android ถามว่า \"อัปเดตแอปนี้ไหม\" ให้กดอัปเดต • ห้ามลบแอปเดิมก่อนติดตั้ง (ข้อมูลจะหาย)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is UpdateState.Error -> {
                    Text("✕ ${s.message}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.checkForUpdate() }) { Text("ลองอีกครั้ง", fontSize = 12.sp) }
                        TextButton(onClick = { viewModel.openReleasesPage() }) { Text("เปิดหน้า GitHub", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckButton(viewModel: AccountantViewModel, label: String = "ตรวจสอบอัปเดต") {
    OutlinedButton(
        onClick = { viewModel.checkForUpdate() },
        modifier = Modifier.fillMaxWidth().testTag("update_check_button")
    ) { Text(label) }
}
