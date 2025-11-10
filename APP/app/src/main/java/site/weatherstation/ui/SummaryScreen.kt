package site.weatherstation.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import site.weatherstation.util.prettyErrorMessage
import site.weatherstation.ui.components.ChartContainer
import site.weatherstation.ui.gauges.FIndexSegmentedGauge
import site.weatherstation.ui.gauges.HumidityRing
import site.weatherstation.ui.gauges.ThermometerGauge
import site.weatherstation.ui.gauges.WindSpeedGauge
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import site.weatherstation.util.alignedTicker


/**
 * Summary with 4 charts and fullscreen overlays.
 * Error handling with a bottom fixed SnackbarHost (indefinite, no action).
 */
@Composable
fun SummaryScreen(
    fullscreenKey: String?,                  // "temp" | "hum" | "wind" | "findex" | null
    requestFullscreen: (String) -> Unit,     // enter
    exitFullscreen: () -> Unit               // exit
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()

    var temperature by remember { mutableStateOf<LatestPoint?>(null) }
    var humidity by remember { mutableStateOf<LatestPoint?>(null) }
    var wind by remember { mutableStateOf<LatestPoint?>(null) }
    var fIndex by remember { mutableStateOf<LatestPoint?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Bottom snackbar host (overlay)
    val snackbarHostState = remember { SnackbarHostState() }

    // Aligned ticker every 10s with 5s offset (:05, :15, :25…)
    val ticker = remember { alignedTicker(10_000L, 5_000L) }

    fun doRefresh() {
        scope.launch {
            refresh(
                onStart = { isLoading = true /* keep snackbar if there is an error */ },
                onDone = { t, h, w, f ->
                    temperature = t; humidity = h; wind = w; fIndex = f
                    isLoading = false
                    error = null
                    snackbarHostState.currentSnackbarData?.dismiss()
                },
                onError = { e ->
                    isLoading = false
                    error = e
                }
            )
        }
    }

    LaunchedEffect(Unit) { doRefresh() }
    LaunchedEffect(ticker) { ticker.collect { doRefresh() } }

    // Show / update snackbar when there is an error
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = msg,
            withDismissAction = false,
            duration = SnackbarDuration.Indefinite
        )
    }

    // Chart specs
    val items = listOf(
        ChartSpec(
            key = Keys.FINDEX, title = "F Index", unit = "",
            value = fIndex?.value,
            subtitle = "at " + formatTimeDevice(fIndex?.ts),
            content = { FIndexSegmentedGauge(value = it?.toFloat()) }
        ),
        ChartSpec(
            key = Keys.TEMP, title = "Temperature", unit = "°C",
            value = temperature?.value,
            subtitle = "at " + formatTimeDevice(temperature?.ts),
            content = { ThermometerGauge(value = it?.toFloat(), min = -5f, max = 50f) }
        ),
        ChartSpec(
            key = Keys.HUM, title = "Humidity", unit = "%",
            value = humidity?.value,
            subtitle = "at " + formatTimeDevice(humidity?.ts),
            content = { HumidityRing(value = it?.toFloat(), label = "") }
        ),
        ChartSpec(
            key = Keys.WIND, title = "Wind speed", unit = "m/s",
            value = wind?.value,
            subtitle = "at " + formatTimeDevice(wind?.ts),
            content = { WindSpeedGauge(value = it?.toFloat(), max = 20f) }
        )
    )

    // ======= FULLSCREEN mode: show only the selected chart (with overlays) =======
    val summaryKeys = setOf(Keys.TEMP, Keys.HUM, Keys.WIND, Keys.FINDEX)
    if (fullscreenKey != null && fullscreenKey in summaryKeys) {
        val spec = items.first { it.key == fullscreenKey }
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ChartContainer(
                isFullscreen = true,
                onToggleFullscreen = exitFullscreen,
                content = { spec.content(spec.value) },
                showIcon = true
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Text(spec.title, style = MaterialTheme.typography.titleLarge)
                spec.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    // ======= NORMAL mode: content + bottom snackbar =======
    Box(Modifier.fillMaxSize()) {

        // Content underneath
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Live summary",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isLoading) "Updating…" else "",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            if (isLandscape) {
                items(items.chunked(2)) { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { spec ->
                            MetricCard(
                                modifier = Modifier.weight(1f),
                                title = spec.title,
                                subtitle = spec.subtitle,
                                action = {
                                    IconButton(onClick = { requestFullscreen(spec.key) }) {
                                        Icon(
                                            Icons.Rounded.Fullscreen,
                                            contentDescription = "Enter fullscreen"
                                        )
                                    }
                                }
                            ) {
                                ChartContainer(
                                    isFullscreen = false,
                                    onToggleFullscreen = { requestFullscreen(spec.key) },
                                    content = { spec.content(spec.value) },
                                    showIcon = false
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items(items) { spec ->
                    MetricCard(
                        title = spec.title,
                        subtitle = spec.subtitle,
                        action = {
                            IconButton(onClick = { requestFullscreen(spec.key) }) {
                                Icon(
                                    Icons.Rounded.Fullscreen,
                                    contentDescription = "Enter fullscreen"
                                )
                            }
                        }
                    ) {
                        ChartContainer(
                            isFullscreen = false,
                            onToggleFullscreen = { requestFullscreen(spec.key) },
                            content = { spec.content(spec.value) },
                            showIcon = false
                        )
                    }
                }
            }
        }

        // Bottom snackbar (overlay)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(8.dp)
        ) { data ->
            Snackbar(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(data.visuals.message)
            }
        }
    }
}

/* ======================= UI helpers ======================= */

private object Keys {
    const val TEMP = "temp"
    const val HUM = "hum"
    const val WIND = "wind"
    const val FINDEX = "findex"
}

private data class ChartSpec(
    val key: String,
    val title: String,
    val unit: String,
    val value: Double?,
    val subtitle: String?,
    val content: @Composable (Double?) -> Unit
)

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.height(500.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (action != null) action()
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/* ======================= Networking & util ======================= */

private data class LatestPoint(val value: Double?, val ts: String?)

/** Build user-friendly error messages for common exceptions. */
private suspend fun refresh(
    onStart: () -> Unit,
    onDone: (LatestPoint, LatestPoint, LatestPoint, LatestPoint) -> Unit,
    onError: (String) -> Unit
) {
    try {
        onStart()
        val api = site.weatherstation.net.RetrofitProvider.api

        // 1) Try fast path: /latest_summary (single request)
        try {
            val s = api.getLatestSummary(window = "5m")
            val t = LatestPoint(s.data.temperature.value, s.data.temperature.timestamp_utc)
            val h = LatestPoint(s.data.humidity.value, s.data.humidity.timestamp_utc)
            val w = LatestPoint(s.data.wind_speed.value, s.data.wind_speed.timestamp_utc)
            val f = LatestPoint(s.data.f_index.value, s.data.f_index.timestamp_utc)
            onDone(t, h, w, f)
            return
        } catch (_: Throwable) {
            // fallback to legacy 4x /range below
        }

        kotlinx.coroutines.coroutineScope {
            val t = async {
                api.getRange(
                    measurement = "sensor_data",
                    field = "temperature",
                    sensorId = "1",
                    range = "5m",
                    agg = null,
                    interval = null,
                    tz = "America/Bogota",
                    order = "DESC",
                    limit = 1
                ).points.firstOrNull().let { LatestPoint(it?.value, it?.ts) }
            }
            val h = async {
                api.getRange(
                    measurement = "sensor_data",
                    field = "humidity",
                    sensorId = "1",
                    range = "5m",
                    agg = null,
                    interval = null,
                    tz = "America/Bogota",
                    order = "DESC",
                    limit = 1
                ).points.firstOrNull().let { LatestPoint(it?.value, it?.ts) }
            }
            val w = async {
                api.getRange(
                    measurement = "wind_data",
                    field = "wind_speed",
                    sensorId = "2",
                    range = "5m",
                    agg = null,
                    interval = null,
                    tz = "America/Bogota",
                    order = "DESC",
                    limit = 1
                ).points.firstOrNull().let { LatestPoint(it?.value, it?.ts) }
            }
            val f = async {
                api.getRange(
                    measurement = "f_index",
                    field = "F_index",
                    sensorId = "calcF",
                    range = "5m",
                    agg = null,
                    interval = null,
                    tz = "America/Bogota",
                    order = "DESC",
                    limit = 1
                ).points.firstOrNull().let { LatestPoint(it?.value, it?.ts) }
            }
            onDone(t.await(), h.await(), w.await(), f.await())
        }
    } catch (t: Throwable) {
        onError(prettyErrorMessage(t))
    }
}

// Format timestamp to local time in America/Bogota
private fun formatTimeDevice(ts: String?): String {
    if (ts.isNullOrBlank()) return "—"
    return try {
        val instant = java.time.Instant.parse(ts)
        val z = site.weatherstation.util.deviceZoneId()
        val lt = instant.atZone(z).toLocalTime()
        lt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
    } catch (_: Exception) {
        "—"
    }
}
