package site.weatherstation.ui.notifications

enum class Metric {
    F_INDEX,        // F Index (0–6.1)
    TEMPERATURE,    // Temperature in °C (-10–50)
    HUMIDITY,       // Relative Humidity in % (0–100)
    WIND_SPEED      // Wind speed in m/s (0–30)
}

enum class Operator {
    GREATER_EQUAL,  // ≥
    LESS_EQUAL,     // ≤
    EQUAL           // =
}

data class Alarm(
    val id: Long = System.currentTimeMillis(),
    val metric: Metric,
    val operator: Operator,
    val threshold: Double,
    val enabled: Boolean = true
)
