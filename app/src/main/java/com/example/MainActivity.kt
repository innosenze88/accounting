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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ImportScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.booking.BookingScreen
import com.example.ui.viewmodel.BookingViewModel
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AccountantViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AccountantViewModel by viewModels()

    /** File shared/opened from another app (e.g. Gmail attachment), waiting to be imported. */
    private val sharedUri = mutableStateOf<Uri?>(null)
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            MyApplicationTheme {
                MainAppScreen(
                    viewModel = viewModel,
                    sharedUri = sharedUri.value,
                    onSharedUriHandled = { sharedUri.value = null },
                    sharedUris = sharedUris.value,
                    onSharedUrisHandled = { sharedUris.value = emptyList() }
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
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (!uris.isNullOrEmpty()) sharedUris.value = uris.toList()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: AccountantViewModel,
    sharedUri: Uri? = null,
    onSharedUriHandled: () -> Unit = {},
    sharedUris: List<Uri> = emptyList(),
    onSharedUrisHandled: () -> Unit = {}
) {
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var showMoreSheet by rememberSaveable { mutableStateOf(false) }

    // A file shared from Gmail/Files: read it and open the Import tab.
    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            viewModel.onFilePicked(sharedUri)
            currentTab = 3
            onSharedUriHandled()
        }
    }
    // Several files shared at once (e.g. many attachments selected in Files / Gmail).
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            viewModel.onFilesPicked(sharedUris)
            currentTab = 3
            onSharedUrisHandled()
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
                            0 -> "ภาพรวม"
                            1 -> "สแกนเอกสาร"
                            2 -> "ประวัติ"
                            3 -> "นำเข้าไฟล์"
                            5 -> "การจองและกระเป๋า"
                            else -> "ตั้งค่า"
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
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("ภาพรวม") },
                    modifier = Modifier.testTag("nav_item_dashboard")
                )
                NavigationBarItem(
                    selected = currentTab == 5,
                    onClick = { currentTab = 5 },
                    icon = { Icon(Icons.Default.Hotel, contentDescription = null) },
                    label = { Text("จอง") },
                    modifier = Modifier.testTag("nav_item_booking")
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    label = { Text("สแกน") },
                    modifier = Modifier.testTag("nav_item_scan")
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("ประวัติ") },
                    modifier = Modifier.testTag("nav_item_history")
                )
                NavigationBarItem(
                    selected = showMoreSheet || currentTab == 3 || currentTab == 4,
                    onClick = { showMoreSheet = true },
                    icon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
                    label = { Text("อื่น ๆ") },
                    modifier = Modifier.testTag("nav_item_more")
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
                    onNavigateToHistory = { currentTab = 2 },
                    onNavigateToSettings = { currentTab = 4 },
                    onNavigateToBooking = { currentTab = 5 }
                )
                1 -> ScannerScreen(viewModel = viewModel)
                2 -> HistoryScreen(viewModel = viewModel, onNavigateToScan = { currentTab = 1 })
                3 -> ImportScreen(viewModel = viewModel, onGoToScanner = { currentTab = 1 })
                4 -> SettingsScreen(viewModel = viewModel)
                5 -> BookingScreen(composeViewModel<BookingViewModel>())
            }
        }
    }

    if (showMoreSheet) {
        ModalBottomSheet(onDismissRequest = { showMoreSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    text = "เมนูเพิ่มเติม",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("นำเข้าไฟล์") },
                    supportingContent = { Text("นำเข้า PDF, รูปภาพ, CSV หรือ Excel") },
                    leadingContent = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentTab = 3
                            showMoreSheet = false
                        }
                        .testTag("more_import")
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                ListItem(
                    headlineContent = { Text("ตั้งค่า") },
                    supportingContent = { Text("ตั้งค่า AI, Google Sheets และการสำรองข้อมูล") },
                    leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentTab = 4
                            showMoreSheet = false
                        }
                        .testTag("more_settings")
                )
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
