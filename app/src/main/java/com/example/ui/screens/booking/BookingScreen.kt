package com.example.ui.screens.booking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.BookingViewModel

/**
 * "จอง/กระเป๋า" tab: direct bookings, the MAKE wallets, day closing and issued documents.
 * (Walk-in and OTA guests stay in eZee; their money comes in through the day close.)
 */
@Composable
fun BookingScreen(vm: BookingViewModel, tab: Int = 0, onTabChange: (Int) -> Unit = {}) {
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val titles = listOf("การจอง", "กระเป๋า", "ปิดยอด", "เอกสาร")

    Column(Modifier.fillMaxSize()) {
        SegmentedTabs(titles, tab, onTabChange)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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

/** Pill-style switcher like the web app: grey track, the chosen page on a white pill in green. */
@Composable
private fun SegmentedTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(4.dp)
            .testTag("booking_tabs")
    ) {
        titles.forEachIndexed { i, t ->
            val isSelected = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    t,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
