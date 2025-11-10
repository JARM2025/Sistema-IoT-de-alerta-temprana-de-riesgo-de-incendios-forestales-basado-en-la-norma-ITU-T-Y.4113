package site.weatherstation.ui.details

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import site.weatherstation.util.prettyErrorMessage
import androidx.compose.ui.graphics.Color

/* ---------- KPI model ---------- */
private data class KPI(val last: Double?, val avg: Double?, val min: Double?, val max: Double?)

/* ---------- Composable ---------- */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(
    modifier: Modifier = Modifier,
    vm: Any? = null, // vm sin uso
) {
    // Base state
    val modelProducer = remember { CartesianChartModelProducer() }
    var live by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // Token to reset zoom/scroll
    var resetZoomToken by remember { mutableIntStateOf(0) }

    // Snackbar (no dismiss)
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        snackbarHostState.currentSnackbarData?.dismiss()
        if (error != null) {
            snackbarHostState.showSnackbar(error!!, withDismissAction = false, duration = SnackbarDuration.Indefinite)
        }
    }

    // Measure
    var selected by rememberSaveable { mutableStateOf(MeasureOption.F_INDEX) }
    var expandedMeasure by remember { mutableStateOf(false) }

    // Options
    var selectedRange by rememberSaveable { mutableStateOf("5m") }
    var selectedAgg   by rememberSaveable { mutableStateOf("raw") }
    var selectedInt   by rememberSaveable { mutableStateOf("auto") }
    var selectedFill  by rememberSaveable { mutableStateOf("none") }

    // Toggle para dots
    var showDots by rememberSaveable { mutableStateOf(true) }

    // KPIs
    var kpi by remember { mutableStateOf(KPI(null, null, null, null)) }
    var showKpiFloat by remember { mutableStateOf(false) }

    // Offset Y para centrar
    var yOffset by remember { mutableDoubleStateOf(0.0) }

    // Decimales del eje Y (4 para F_INDEX)
    val yDecimals = 1

    // Units
    fun unitForField(field: String) = when (field) {
        "temperature" -> "°C"; "humidity" -> "%"; "wind_speed" -> "m/s"; "wind_direction" -> "°"; else -> ""
    }
    val unitLabel = remember(selected) { unitForField(selected.field) }

    // Axis labels
    val deviceZone = remember { site.weatherstation.util.deviceZoneId() }
    val timeFmt = remember(selectedRange) {
        val ms = rangeToMs(selectedRange)
        val pattern = when {
            ms <= 6 * 3_600_000L -> "HH:mm:ss"
            ms <= 24 * 3_600_000L -> "HH:mm"
            else -> "EEE d MMM"
        }
        DateTimeFormatter.ofPattern(pattern)
    }

    // --- mapa de puntos para el marker ---
    var lastPointMap by remember { mutableStateOf<Map<Long, Double>>(emptyMap()) }

    // --- chart helpers ---
    suspend fun setEmptyChart() {
        modelProducer.runTransaction {
            val now = System.currentTimeMillis().toDouble()
            lineSeries { series(listOf(now, now + 1.0), listOf(0.0, 0.0)) }
        }
    }

    suspend fun setSegments(segments: List<Pair<List<Double>, List<Double>>>) {
        modelProducer.runTransaction {
            lineSeries {
                val hasAny = segments.any { it.first.isNotEmpty() }
                if (!hasAny) {
                    val now = System.currentTimeMillis().toDouble()
                    series(listOf(now, now + 1.0), listOf(0.0, 0.0))
                } else segments.forEach { (xs, ys) -> if (xs.isNotEmpty()) series(xs, ys) }
            }
        }
    }

    // Fetch & build
    suspend fun fetchAndPlot() {
        try {
            isLoading = true
            error = null
            info = null

            val api = site.weatherstation.net.RetrofitProvider.api
            val rangeApi = normalizeRangeForApi(selectedRange)

            val (aggParam, intParam, fillParam) = if (selectedAgg == "raw") {
                Triple(null, null, null)
            } else {
                val base = if (selectedInt != "auto") selectedInt else deriveInterval(selectedRange)
                val safe = if (base == "auto") base else clampInterval(selectedRange, base)
                Triple(selectedAgg, safe, if (selectedFill != "none") selectedFill else null)
            }

            val res = api.getRange(
                measurement = selected.measurement,
                field = selected.field,
                sensorId = selected.sensorId,
                range = rangeApi,
                agg = aggParam,
                interval = intParam,
                tz = "America/Bogota",
                order = site.weatherstation.net.Order.ASC.wire,
                // fill = fillParam,
                limit = 100_000
            )

            val rawPoints = res.points.orEmpty().mapNotNull { p ->
                val v = p.value ?: return@mapNotNull null
                val t = parseIsoToEpochMillis(p.ts) ?: return@mapNotNull null
                t to v
            }.sortedBy { it.first }

            if (rawPoints.isEmpty()) {
                kpi = KPI(null, null, null, null)
                info = "No data for the selected range"
                setEmptyChart()
                yOffset = 0.0
                lastPointMap = emptyMap()
                return
            }

            val end = System.currentTimeMillis()
            val start = end - rangeToMs(selectedRange)

            val stepMs = stepToMs(intParam)
                ?: stepToMs(deriveInterval(selectedRange))
                ?: 60_000L

            val seriesWithNulls: List<Pair<Long, Double?>> =
                if (aggParam != null) {
                    densifyBuckets(start, end, stepMs, rawPoints, fillParam)
                } else {
                    breakGapsRaw(rawPoints, stepMs)
                }

            // --- SOLO para F_INDEX: redondear a 1 decimal para graficar ---
            fun d1(x: Double) = java.math.BigDecimal(x)
                .setScale(1, java.math.RoundingMode.HALF_UP)
                .toDouble()

            val seriesForPlot: List<Pair<Long, Double?>> =
                if (selected == MeasureOption.F_INDEX) {
                    seriesWithNulls.map { (t, y) -> t to y?.let { d1(it) } }
                } else {
                    seriesWithNulls
                }

            // KPIs
            kpi = computeKpisFromSeries(seriesWithNulls)

            // ---- Y CENTERING (offset from the minimum) usando la serie que se graficará
            val valuesOnly = seriesForPlot.mapNotNull { it.second }.filter { it.isFinite() }
            yOffset = if (valuesOnly.isNotEmpty()) valuesOnly.min() else 0.0

            // desplazar serie restando offset (graficar la serie redondeada si es F_INDEX)
            val shifted = toContiguousSegments(seriesForPlot).map { (xs, ys) ->
                xs to ys.map { it - yOffset }
            }

            // mapa timestamp -> valor real (sin restar offset)
            lastPointMap = seriesWithNulls
                .mapNotNull { (t, y) -> y?.let { t to it } }
                .toMap()

            setSegments(shifted)

        } catch (t: Throwable) {
            Log.e("DetailsScreen", "fetchAndPlot failed", t)
            kpi = KPI(null, null, null, null)
            error = prettyErrorMessage(t)
            setEmptyChart()
            yOffset = 0.0
            lastPointMap = emptyMap()
        } finally {
            isLoading = false
        }
    }

    // Initial + changes
    LaunchedEffect(Unit) { fetchAndPlot() }
    LaunchedEffect(selected, selectedRange, selectedAgg, selectedInt, selectedFill) { fetchAndPlot() }

    // Live tick
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        fetchAndPlot()
        while (live) {
            delay(millisToNextAlignedTick())
            fetchAndPlot()
        }
    }

    // --- Line with/ without puntos ---
    val point = LineCartesianLayer.Point(
        component = rememberShapeComponent(
            fill = fill(MaterialTheme.colorScheme.primary),
            shape = CorneredShape.Pill
        )
    )
    val lineWithDots = LineCartesianLayer.rememberLine(
        pointProvider = LineCartesianLayer.PointProvider.single(point)
    )
    val lineWithoutDots = LineCartesianLayer.rememberLine()
    val lineLayer = rememberLineCartesianLayer(
        lineProvider = if (showDots)
            LineCartesianLayer.LineProvider.series(lineWithDots)
        else
            LineCartesianLayer.LineProvider.series(lineWithoutDots)
    )

    // --- Marker: X formateado + Y real usando el mapa ---
    val markerValueFormatter = remember(timeFmt, yDecimals, unitLabel, lastPointMap) {
        DefaultCartesianMarker.ValueFormatter { _, targets: List<CartesianMarker.Target> ->
            targets.joinToString("\n") { t ->
                val epoch = t.x.roundToLong()
                val xLabel = runCatching {
                    timeFmt.format(Instant.ofEpochMilli(epoch).atZone(deviceZone))
                }.getOrElse { epoch.toString() }

                val yReal = lastPointMap[epoch]
                val yText = if (yReal != null && yReal.isFinite())
                    String.format(Locale.US, "%.${yDecimals}f %s", yReal, unitLabel)
                else
                    "—"

                "$xLabel  |  $yText"
            }
        }
    }

    val marker = rememberDefaultCartesianMarker(
        label = rememberAxisLabelComponent(),
        valueFormatter = markerValueFormatter,
        labelPosition = DefaultCartesianMarker.LabelPosition.Top,
        indicator = {
            ShapeComponent(
                shape = CorneredShape.Pill,
                fill  = fill(Color(0xFFF44336))
            )
        },
        indicatorSize = 12.dp
    )

    // Chart
    val chart = rememberCartesianChart(
        lineLayer,
        startAxis = VerticalAxis.rememberStart(
            label = rememberAxisLabelComponent(),
            valueFormatter = { _, value, _ ->
                val real = value + yOffset
                String.format(Locale.US, "%.${yDecimals}f", real)
            }
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberAxisLabelComponent(),
            valueFormatter = { _, v, _ ->
                val epoch = v.roundToLong()
                runCatching { timeFmt.format(Instant.ofEpochMilli(epoch).atZone(deviceZone)) }
                    .getOrElse { epoch.toString() }
            }
        ),
        marker = marker,
    )

    // Container size (para clamping de la card KPI)
    var parentW by remember { mutableIntStateOf(0) }
    var parentH by remember { mutableIntStateOf(0) }

    // UI
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
    ) { insets ->
        Box(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .padding(12.dp)
                .onSizeChanged { parentW = it.width; parentH = it.height }
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                /* --------- Controles superiores --------- */
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExposedDropdownMenuBox(
                        expanded = expandedMeasure,
                        onExpandedChange = { expandedMeasure = !expandedMeasure },
                        modifier = Modifier.weight(1f)
                    ) {
                        TextField(
                            value = selected.title,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Measure") },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize
                            ),
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                                .fillMaxWidth()
                                .height(56.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedMeasure,
                            onDismissRequest = { expandedMeasure = false }
                        ) {
                            MeasureOption.entries.forEach { opt ->
                                DropdownMenuItem(text = { Text(opt.title) }, onClick = {
                                    selected = opt; expandedMeasure = false
                                })
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    FilledTonalIconButton(
                        onClick = { showKpiFloat = !showKpiFloat },
                        modifier = Modifier.size(48.dp)
                    ) { Text("KPI") }

                    Spacer(Modifier.width(8.dp))

                    var openSettings by remember { mutableStateOf(false) }
                    FilledTonalIconButton(
                        onClick = { openSettings = true },
                        modifier = Modifier.size(48.dp)
                    ) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }

                    DropdownMenu(
                        expanded = openSettings,
                        onDismissRequest = { openSettings = false },
                        offset = DpOffset(0.dp, 8.dp),
                        modifier = Modifier
                            .widthIn(min = 340.dp)
                            .heightIn(max = 480.dp)
                    ) {
                        SettingsGrid(
                            range = selectedRange,
                            onRange = { r ->
                                val changed = selectedRange != r
                                selectedRange = r
                                selectedInt = clampInterval(selectedRange, selectedInt)
                                if (changed) resetZoomToken++
                            },
                            agg = selectedAgg,
                            onAgg = { a ->
                                selectedAgg = a
                                if (a == "raw") {
                                    selectedInt = "auto"
                                    selectedFill = "none"
                                }
                            },
                            interval = selectedInt,
                            onInterval = { selectedInt = it },
                            fill = selectedFill,
                            onFill = { selectedFill = it },
                            isRaw = selectedAgg == "raw",
                            showDots = showDots,
                            onShowDots = { showDots = it }
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    FilledTonalIconButton(
                        onClick = { live = !live },
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (live) Icon(Icons.Filled.Pause, contentDescription = "Pause live")
                        else Icon(Icons.Filled.PlayArrow, contentDescription = "Resume live")
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                info?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }

                /* --------- Chart --------- */
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 240.dp)
                ) {
                    key(resetZoomToken) {
                        val scrollState = rememberVicoScrollState()
                        val zoomState = rememberVicoZoomState(initialZoom = Zoom.Content)

                        
                        // Y-axis unit label (above the axis area)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Text(
                                text = if (unitLabel.isNotEmpty()) "  " + unitLabel else "",
                                        style = MaterialTheme.typography.labelLarge
                            )
                        }

                        CartesianChartHost(
                            chart = chart,
                            modelProducer = modelProducer,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = { resetZoomToken++ })
                                },
                            scrollState = scrollState,
                            zoomState = zoomState,
                        )
                    }
                }
            }

            /* ---------- KPI flotante ---------- */
            DraggableKpiOverlay(
                visible = showKpiFloat,
                kpi = kpi,
                unit = unitLabel,
                onClose = { showKpiFloat = false },
                parentWidthPx = parentW,
                parentHeightPx = parentH
            )
        }
    }
}

/* ---------- Draggable KPI Overlay (compacta) ---------- */
@Composable
private fun DraggableKpiOverlay(
    visible: Boolean,
    kpi: KPI,
    unit: String,
    onClose: () -> Unit,
    parentWidthPx: Int,
    parentHeightPx: Int,
) {
    var offsetX by rememberSaveable { mutableFloatStateOf(16f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(96f) }
    var cardW by remember { mutableIntStateOf(0) }
    var cardH by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    val horizontalMarginDp = 16.dp
    val maxWidthDp = with(density) {
        val marginPx = horizontalMarginDp.toPx() * 2
        val maxPx = (parentWidthPx - marginPx).coerceAtLeast(140f)
        minOf(220.dp, maxPx.toDp())
    }

    fun clampOffsets() {
        val maxX = (parentWidthPx - cardW).coerceAtLeast(0)
        val maxY = (parentHeightPx - cardH).coerceAtLeast(0)
        offsetX = offsetX.coerceIn(0f, maxX.toFloat())
        offsetY = offsetY.coerceIn(0f, maxY.toFloat())
    }

    LaunchedEffect(parentWidthPx, parentHeightPx, cardW, cardH) { clampOffsets() }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + expandVertically(expandFrom = Alignment.Top),
        exit = slideOutVertically { -it } + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = Modifier.zIndex(2f)
    ) {
        Surface(
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .onSizeChanged {
                    cardW = it.width; cardH = it.height
                    clampOffsets()
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            offsetX += drag.x
                            offsetY += drag.y
                            clampOffsets()
                        }
                    )
                }
        ) {
            Column(
                Modifier
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .widthIn(min = 160.dp, max = maxWidthDp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 6.dp)
                ) {
                    Box(
                        Modifier
                            .size(width = 24.dp, height = 4.dp)
                            .clip(MaterialTheme.shapes.small)
                            .align(Alignment.Center)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(30.dp)
                            .align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close KPI",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                CompactKpiRow("Last", kpi.last, unit)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                CompactKpiRow("Avg",  kpi.avg,  unit)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                CompactKpiRow("Min",  kpi.min,  unit)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                CompactKpiRow("Max",  kpi.max,  unit)
            }
        }
    }
}

/* ---------- Fila KPI compacta ---------- */
@Composable
private fun CompactKpiRow(title: String, value: Double?, unit: String) {
    val txt = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = txt,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.End
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ---------- Settings grid (multi-column) ---------- */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsGrid(
    range: String,
    onRange: (String) -> Unit,
    agg: String,
    onAgg: (String) -> Unit,
    interval: String,
    onInterval: (String) -> Unit,
    fill: String,
    onFill: (String) -> Unit,
    isRaw: Boolean,
    showDots: Boolean,
    onShowDots: (Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        SectionTitle("Range")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4
        ) {
            listOf("1m","5m","10m","15m","1h","6h","24h","7d").forEach { r ->
                FilterChip(
                    selected = range == r,
                    onClick = { onRange(r) },
                    label = { Text(r) }
                )
            }
        }

        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

        SectionTitle("Aggregation")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3
        ) {
            listOf("raw","mean","min","max","median","count").forEach { a ->
                FilterChip(
                    selected = agg == a,
                    onClick = { onAgg(a) },
                    label = { Text(a) }
                )
            }
        }

        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

        SectionTitle("Interval")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4
        ) {
            val options = listOf("auto","10s","30s","1m","5m","10m","30m","1h")
            options.forEach { opt ->
                val enabled = when {
                    opt == "auto" -> true
                    isRaw -> false
                    else -> isIntervalAllowed(range, opt)
                }
                FilterChip(
                    enabled = enabled,
                    selected = interval == opt && enabled,
                    onClick = { if (enabled) onInterval(opt) },
                    label = { Text(opt) }
                )
            }
        }

        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

        SectionTitle("Fill")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4
        ) {
            listOf("none","previous").forEach { f ->
                FilterChip(
                    enabled = !isRaw,
                    selected = fill == f,
                    onClick = { if (!isRaw) onFill(f) },
                    label = { Text(f) }
                )
            }
        }

        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

        // Display
        SectionTitle("Display")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Points", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = showDots, onCheckedChange = onShowDots)
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/* ---------- Helpers ---------- */

private fun millisToNextAlignedTick(
    nowMs: Long = System.currentTimeMillis(),
    cadenceMs: Long = 10_000L,
    phaseMs: Long = 5_000L
): Long {
    val delta = (nowMs - phaseMs) % cadenceMs
    val mod = if (delta < 0) delta + cadenceMs else delta
    return if (mod == 0L) cadenceMs else cadenceMs - mod
}

private fun parseIsoToEpochMillis(iso: String): Long? {
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

/* ===== Range / interval ===== */
private val RANGE_MS = mapOf(
    "1m" to 60_000L, "5m" to 5 * 60_000L, "10m" to 10 * 60_000L, "15m" to 15 * 60_000L,
    "1h" to 3_600_000L, "6h" to 6 * 3_600_000L, "24h" to 24 * 3_600_000L, "7d" to 7 * 24 * 3_600_000L,
)
private fun rangeToMs(r: String) = RANGE_MS[r] ?: 5 * 60_000L
private fun normalizeRangeForApi(r: String) = if (r == "7d") "168h" else r

private fun stepToMs(s: String?): Long? {
    if (s == null || s == "auto") return null
    val m = Regex("""^(\d+)([smh])$""").matchEntire(s) ?: return null
    val (n, u) = m.destructured
    val k = when (u) { "s" -> 1_000L; "m" -> 60_000L; "h" -> 3_600_000L; else -> return null }
    return n.toLong() * k
}

private fun deriveInterval(range: String): String = when (range) {
    "1m" -> "10s"; "5m", "10m" -> "30s"; "15m", "1h" -> "1m"; "6h" -> "5m"; "24h" -> "10m"; "7d" -> "1h"; else -> "1m"
}

private fun isIntervalAllowed(range: String, interval: String): Boolean {
    val rMs = rangeToMs(range)
    val iMs = stepToMs(interval) ?: return true
    return iMs <= rMs
}

private fun clampInterval(range: String, selected: String): String {
    if (selected == "auto") return "auto"
    if (isIntervalAllowed(range, selected)) return selected
    val order = listOf("10s", "30s", "1m", "5m", "10m", "30m", "1h")
    val allowed = order.filter { isIntervalAllowed(range, it) }
    return allowed.lastOrNull() ?: "auto"
}

/* ===== Series building ===== */
private fun densifyBuckets(
    start: Long, end: Long, stepMs: Long, points: List<Pair<Long, Double>>, fill: String?
): List<Pair<Long, Double?>> {
    val map = points.associate { it.first to it.second }
    val out = ArrayList<Pair<Long, Double?>>()
    var prev: Double? = null
    var t = (start / stepMs) * stepMs
    if (t < start) t += stepMs
    while (t <= end) {
        val v = map[t]
        val y = when {
            v != null -> v.also { prev = it }
            fill == "previous" -> prev
            else -> null
        }
        out += t to y
        t += stepMs
    }
    return out
}

private fun breakGapsRaw(points: List<Pair<Long, Double>>, stepMs: Long): List<Pair<Long, Double?>> {
    if (points.isEmpty()) return emptyList()
    val gap = ((stepMs * 8) / 5).coerceAtLeast(10_000L)
    val out = ArrayList<Pair<Long, Double?>>()
    for (i in points.indices) {
        val (t, v) = points[i]
        out += t to v
        val next = points.getOrNull(i + 1) ?: continue
        if (next.first - t > gap) out += (t + stepMs) to null
    }
    return out
}

private fun toContiguousSegments(series: List<Pair<Long, Double?>>): List<Pair<List<Double>, List<Double>>> {
    val segments = ArrayList<Pair<List<Double>, List<Double>>>()
    var xs = ArrayList<Double>(); var ys = ArrayList<Double>()
    fun flush() { if (xs.isNotEmpty()) segments += xs to ys; xs = ArrayList(); ys = ArrayList() }
    for ((t, y) in series) {
        if (y == null || y.isNaN()) {
            flush()
        } else {
            xs += t.toDouble(); ys += y
        }
    }
    flush()
    return segments
}

/* ===== KPIs ===== */
private fun computeKpisFromSeries(series: List<Pair<Long, Double?>>): KPI {
    val vals = series.mapNotNull { it.second }.filter { it.isFinite() }
    if (vals.isEmpty()) return KPI(null, null, null, null)
    val last = vals.lastOrNull()
    var sum = 0.0; var min = Double.POSITIVE_INFINITY; var max = Double.NEGATIVE_INFINITY; var n = 0
    for (v in vals) { sum += v; n++; if (v < min) min = v; if (v > max) max = v }
    return KPI(last, if (n > 0) sum / n else null, if (min.isFinite()) min else null, if (max.isFinite()) max else null)
}
