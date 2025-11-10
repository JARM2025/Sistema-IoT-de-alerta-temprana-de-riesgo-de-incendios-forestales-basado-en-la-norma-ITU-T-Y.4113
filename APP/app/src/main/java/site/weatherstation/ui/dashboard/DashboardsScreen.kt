package site.weatherstation.ui.dashboard

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import site.weatherstation.ui.SummaryScreen
import site.weatherstation.ui.details.DetailsScreen

@Composable
fun DashboardsScreen() {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var fullscreenKey by rememberSaveable { mutableStateOf<String?>(null) }
    val tabs = listOf("Summary", "Details")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = tabIndex == i,
                    onClick = { tabIndex = i },
                    text = { Text(title) }
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            when (tabIndex) {
                0 -> SummaryScreen(
                fullscreenKey = fullscreenKey,
                requestFullscreen = { fullscreenKey = it },
                exitFullscreen = { fullscreenKey = null }
            )
                1 -> DetailsScreen()
            }
        }
    }
}
