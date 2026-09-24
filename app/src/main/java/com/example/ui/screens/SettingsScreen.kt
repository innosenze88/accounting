package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.settings.AiProvider
import com.example.R
import com.example.data.settings.AiSettings
import com.example.data.settings.AiSettingsRepository
import com.example.ui.viewmodel.AccountantViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: AccountantViewModel,
    modifier: Modifier = Modifier
) {
    val saved by viewModel.aiSettings.collectAsStateWithLifecycle()
    val testMessage by viewModel.connectionTestMessage.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val sheetsMessage by viewModel.sheetsMessage.collectAsStateWithLifecycle()
    val isSheetsBusy by viewModel.isSheetsBusy.collectAsStateWithLifecycle()
    val unsyncedCount by viewModel.unsyncedCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Editable copy of the saved settings; reset whenever the saved value changes.
    var draft by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { draft = saved }
    var savedNotice by remember { mutableStateOf(false) }

    val isDirty = draft != saved

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI ที่ใช้อ่านเอกสาร", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { p ->
                FilterChip(
                    selected = draft.provider == p,
                    onClick = { draft = draft.copy(provider = p); savedNotice = false },
                    label = { Text(p.title) },
                    modifier = Modifier.testTag("provider_${p.code}")
                )
            }
        }

        when (draft.provider) {
            AiProvider.GEMINI -> ProviderCard(
                title = "Google Gemini",
                keyHint = "ขอ key ได้ที่ aistudio.google.com → Get API key",
                apiKey = draft.geminiApiKey,
                onApiKeyChange = { draft = draft.copy(geminiApiKey = it); savedNotice = false },
                model = draft.geminiModel,
                onModelChange = { draft = draft.copy(geminiModel = it); savedNotice = false },
                suggestions = AiSettings.GEMINI_MODEL_SUGGESTIONS,
                extraNote = "ถ้าเว้นว่าง จะใช้ key จาก AI Studio Secrets (ถ้ามี)"
            )
            AiProvider.CLAUDE -> ProviderCard(
                title = "Anthropic Claude",
                keyHint = "ขอ key ได้ที่ platform.claude.com → Settings → API keys (ขึ้นต้นด้วย sk-ant-api)",
                apiKey = draft.claudeApiKey,
                onApiKeyChange = { draft = draft.copy(claudeApiKey = it); savedNotice = false },
                model = draft.claudeModel,
                onModelChange = { draft = draft.copy(claudeModel = it); savedNotice = false },
                suggestions = AiSettings.CLAUDE_MODEL_SUGGESTIONS,
                extraNote = "ถ้า key ผูกหลาย workspace ต้องใส่ Workspace ID ด้านล่างด้วย",
                workspaceId = draft.claudeWorkspaceId,
                onWorkspaceIdChange = { draft = draft.copy(claudeWorkspaceId = it); savedNotice = false }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    viewModel.saveAiSettings(draft)
                    savedNotice = true
                },
                enabled = isDirty,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_save_button"),
                shape = RoundedCornerShape(10.dp)
            ) { Text("บันทึก") }

            OutlinedButton(
                onClick = {
                    savedNotice = false
                    viewModel.testAiConnection(draft)
                },
                enabled = !isTesting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_test_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("บันทึก & ทดสอบ")
            }
        }

        if (savedNotice && !isDirty) {
            Text("✓ บันทึกแล้ว", color = Color(0xFF2E7D32), fontSize = 13.sp)
        }
        testMessage?.let { msg ->
            Text(
                msg,
                fontSize = 13.sp,
                color = if (msg.startsWith("✓")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider()

        // ---------------- Google Sheets ----------------
        Text("Google Sheets", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "วิธีตั้งค่า (ทำครั้งเดียว)\n" +
                        "1. กด \"คัดลอกโค้ด Apps Script\" ด้านล่าง\n" +
                        "2. เปิด Google Sheet → ส่วนขยาย → Apps Script → ลบโค้ดเดิม วางโค้ดที่คัดลอก → บันทึก\n" +
                        "3. Deploy → New deployment → Web app → Execute as: Me, Who has access: Anyone → Deploy\n" +
                        "4. คัดลอก URL ที่ลงท้าย /exec มาวางช่องด้านล่าง แล้วกด \"บันทึก & ทดสอบ Sheets\"",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = draft.sheetsWebAppUrl,
                    onValueChange = { draft = draft.copy(sheetsWebAppUrl = it); savedNotice = false },
                    label = { Text("Web App URL (…/exec)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_sheets_url")
                )
                OutlinedTextField(
                    value = draft.sheetsToken,
                    onValueChange = { draft = draft.copy(sheetsToken = it); savedNotice = false },
                    label = { Text("รหัสลับ (Token) — ต้องตรงกับในโค้ด Apps Script") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            viewModel.saveAiSettings(draft)
                            val code = context.resources.openRawResource(R.raw.apps_script_code)
                                .bufferedReader(Charsets.UTF_8).use { it.readText() }
                                .replace("__TOKEN__", draft.sheetsToken)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Apps Script", code))
                            Toast.makeText(context, "คัดลอกโค้ดแล้ว (มีรหัสลับของคุณอยู่ในโค้ด)", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("คัดลอกโค้ด Apps Script", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { draft = draft.copy(sheetsToken = AiSettingsRepository.newToken()); savedNotice = false }
                    ) { Text("สร้างรหัสใหม่", fontSize = 12.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ส่งเอกสารไป Sheets อัตโนมัติเมื่อกดยืนยัน",
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = draft.sheetsAutoSync,
                        onCheckedChange = { draft = draft.copy(sheetsAutoSync = it); savedNotice = false }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { savedNotice = false; viewModel.testSheets(draft) },
                        enabled = !isSheetsBusy && draft.sheetsWebAppUrl.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_sheets_test"),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("บันทึก & ทดสอบ Sheets", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { viewModel.syncAllToSheets() },
                        enabled = !isSheetsBusy && saved.sheetsEnabled && unsyncedCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("ส่งที่ค้าง ($unsyncedCount)", fontSize = 12.sp) }
                }
                if (isSheetsBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                sheetsMessage?.let { msg ->
                    Text(
                        msg,
                        fontSize = 13.sp,
                        color = if (msg.startsWith("✓")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "ถ้าแก้ Token ต้องคัดลอกโค้ดไปวางใน Apps Script ใหม่ และ Deploy เวอร์ชันใหม่ด้วย",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("เรื่องความปลอดภัย", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "• API key และ Token เก็บในเครื่องนี้เท่านั้น ไม่ถูกสำรองขึ้น cloud\n" +
                        "• ใครมี URL + Token ของ Apps Script จะเขียนข้อมูลลง Sheet ได้ อย่าแชร์\n" +
                        "• รูปเอกสารจะถูกส่งไปยังผู้ให้บริการ AI ที่เลือกเพื่ออ่านข้อมูล\n" +
                        "• ค่าใช้จ่ายการเรียก API คิดกับบัญชีของ key นั้น",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    title: String,
    keyHint: String,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    suggestions: List<String>,
    extraNote: String?,
    workspaceId: String? = null,
    onWorkspaceIdChange: ((String) -> Unit)? = null
) {
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "ซ่อน key" else "แสดง key"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_api_key")
            )
            Text(keyHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extraNote?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (workspaceId != null && onWorkspaceIdChange != null) {
                OutlinedTextField(
                    value = workspaceId,
                    onValueChange = onWorkspaceIdChange,
                    label = { Text("Workspace ID (ถ้ามี)") },
                    placeholder = { Text("wrkspc_...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_workspace_id")
                )
                Text(
                    "ดูได้ที่ platform.claude.com → Settings → Workspaces (เว้นว่างได้ถ้า key ผูก workspace เดียว)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text("โมเดล") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_model")
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { m ->
                    FilterChip(
                        selected = model == m,
                        onClick = { onModelChange(m) },
                        label = { Text(m, fontSize = 11.sp) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ถ้าไม่แน่ใจ ใช้ค่าที่เลือกไว้ได้เลย",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
