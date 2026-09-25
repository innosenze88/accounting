package com.example.ui.screens.booking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.BookingViewModel

/**
 * "จอง/กระเป๋า" tab: direct bookings, the MAKE wallets, day closing and issued documents.
 * (Walk-in and OTA guests stay in eZee; their money comes in through the day close.)
 */
@Composable
fun BookingScreen(vm: BookingViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val titles = listOf("การจอง", "กระเป๋า", "ปิดยอด", "เอกสาร")

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            MessageBanner(message) { vm.clearMessage() }
        }
        when (tab) {
            0 -> BookingsTab(vm)
            1 -> WalletsTab(vm)
            2 -> DayCloseTab(vm)
            else -> DocumentsTab(vm)
        }
    }
}
