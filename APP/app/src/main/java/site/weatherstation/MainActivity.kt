package site.weatherstation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import site.weatherstation.nav.Route
import site.weatherstation.ui.dashboard.DashboardsScreen
import site.weatherstation.ui.notifications.NotificationsScreen

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val destination = backStackEntry?.destination

                Scaffold { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        // Compact top-level switch (full-width segmented buttons)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {

                            val isDashboardsSelected = destination
                                ?.hierarchy
                                ?.any { it.route == Route.Dashboards.route } == true

                            val isNotificationsSelected = destination
                                ?.hierarchy
                                ?.any { it.route == Route.Notifications.route } == true

                            SegmentedButton(
                                selected = isDashboardsSelected || destination == null, // null al inicio -> Dashboards
                                onClick = {
                                    navController.navigate(Route.Dashboards.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                icon = { Icon(Icons.Filled.Dashboard, contentDescription = "Dashboards") },
                                label = { Text("Dashboards") },
                                modifier = Modifier.weight(1f)
                            )

                            SegmentedButton(
                                selected = isNotificationsSelected,
                                onClick = {
                                    navController.navigate(Route.Notifications.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                icon = { Icon(Icons.Filled.Notifications, contentDescription = "Notifications") },
                                label = { Text("Notifications") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Content
                        NavHost(
                            navController = navController,
                            startDestination = Route.Dashboards.route,
                        ) {
                            composable(Route.Dashboards.route) { DashboardsScreen() }
                            composable(Route.Notifications.route) { NotificationsScreen() }
                        }
                    }
                }
            }
        }
    }
}
