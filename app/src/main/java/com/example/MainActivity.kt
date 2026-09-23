package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ImportScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AccountantViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AccountantViewModel by viewModels()

    /** File shared/opened from another app (e.g. Gmail attachment), waiting to be imported. */
    private val sharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            MyApplicationTheme {
                MainAppScreen(
                    viewModel = viewModel,
                    sharedUri = sharedUri.value,
                    onSharedUriHandled = { sharedUri.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) sharedUri.value = uri
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: AccountantViewModel,
    sharedUri: Uri? = null,
    onSharedUriHandled: () -> Unit = {}
) {
    var currentTab by remember { mutableIntStateOf(0) }

    // A file shared from Gmail/Files: read it and open the Import tab.
    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            viewModel.onFilePicked(sharedUri)
            currentTab = 3
            onSharedUriHandled()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_scaffold"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentTab) {
                            0 -> "แดชบอร์ดสรุปผลบัญชี"
                            1 -> "AI Accountant OCR"
                            2 -> "ประวัติเอกสารบัญชี"
                            3 -> "นำเข้าไฟล์ (PDF / CSV / Excel)"
                            else -> "ตั้งค่า AI"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("bottom_nav_bar")
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "แดชบอร์ด") },
                    label = { Text("แดชบอร์ด", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_dashboard")
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "สแกน") },
                    label = { Text("สแกน", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_scan")
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.History, contentDescription = "ประวัติ") },
                    label = { Text("ประวัติ", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_history")
                )
                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { currentTab = 3 },
                    icon = { Icon(Icons.Default.UploadFile, contentDescription = "นำเข้าไฟล์") },
                    label = { Text("นำเข้า", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_rules")
                )
                NavigationBarItem(
                    selected = currentTab == 4,
                    onClick = { currentTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "ตั้งค่า") },
                    label = { Text("ตั้งค่า", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToScan = { currentTab = 1 },
                    onNavigateToHistory = { currentTab = 2 }
                )
                1 -> ScannerScreen(viewModel = viewModel)
                2 -> HistoryScreen(viewModel = viewModel, onNavigateToScan = { currentTab = 1 })
                3 -> ImportScreen(viewModel = viewModel, onGoToScanner = { currentTab = 1 })
                4 -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * Kept for test suite compatibility
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme { Greeting("Android") }
}
