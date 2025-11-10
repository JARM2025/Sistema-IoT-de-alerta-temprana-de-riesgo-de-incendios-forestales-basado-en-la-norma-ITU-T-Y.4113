package site.weatherstation.nav

sealed class Route(val route: String) {
    data object Dashboards : Route("dashboards")
    data object Notifications : Route("notifications")
}
