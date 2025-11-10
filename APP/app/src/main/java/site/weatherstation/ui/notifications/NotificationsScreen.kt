package site.weatherstation.ui.notifications

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// String literal para evitar referenciar la constante de API 33 con minSdk 24
private const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    vm: NotificationsViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui by vm.uiState.collectAsState()

    // -------- Throttle & saving state to avoid rapid multi-taps --------
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var lastClickAt by remember { mutableLongStateOf(0L) }

    // Cooldown state to force recomposition after 600 ms
    var cooldownOk by remember { mutableStateOf(true) }
    LaunchedEffect(lastClickAt) {
        if (lastClickAt != 0L) {
            cooldownOk = false
            delay(600)
            cooldownOk = true
        }
    }

    // Ensure appContext is set
    LaunchedEffect(Unit) {
        vm.init(ctx.applicationContext)
    }

    // Estado de permiso (reactivo). En 33+ lo consultamos; en <33 asumimos concedido.
    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(ctx, PERM_POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Observa vuelta desde Settings para refrescar permiso
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotifPermission =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(ctx, PERM_POST_NOTIFICATIONS) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Launcher para pedir permiso (usamos string literal)
    val requestPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasNotifPermission =
                granted || (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ||
                        ContextCompat.checkSelfPermission(ctx, PERM_POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED

            // Si sigue bloqueado y es 33+, llevamos a Settings
            if (!hasNotifPermission) {
                openNotificationSettings(ctx)
            }
        }
    )

    val canCreate = !ui.isFull && hasNotifPermission && !isSaving && cooldownOk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header "Create alarm"
        Text(
            "Create alarm",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(8.dp))

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricDropdown(
                        selected = ui.selectedMetric,
                        onSelected = { vm.onMetricChange(it) },
                        modifier = Modifier.weight(1f)
                    )
                    OperatorDropdown(
                        selected = ui.selectedOperator,
                        onSelected = { vm.onOperatorChange(it) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                ThresholdSelector(
                    metric = ui.selectedMetric,
                    value = ui.threshold,
                    onChange = { vm.onThresholdChange(it) }
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPostNotificationsIfNeeded(requestPermission)
                        } else {
                            if (isSaving || !cooldownOk) return@Button
                            lastClickAt = android.os.SystemClock.elapsedRealtime()
                            isSaving = true
                            scope.launch {
                                try {
                                    vm.addAlarm()
                                    vm.maybeStartOrStopService()
                                } finally {
                                    isSaving = false
                                }
                            }
                        }
                    },
                    enabled = canCreate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create alarm")
                }

                // Centered helper (below button)
                Spacer(Modifier.height(12.dp))
                if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Button(
                        onClick = { requestPostNotificationsIfNeeded(requestPermission) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Notifications, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Allow notifications to create alarms")
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Alarms auto-disable when triggered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ======= Separator between create and list =======
        Spacer(Modifier.height(24.dp))
        Text(
            "My alarms",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(8.dp))

        if (ui.alarms.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(
                    "You have no alarms. Create the first one above.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ui.alarms.forEach { alarm ->
                    AlarmRow(
                        alarm = alarm,
                        onToggle = { enabled ->
                            vm.setAlarmEnabled(alarm.id, enabled)
                            vm.maybeStartOrStopService()
                        },
                        onDelete = {
                            vm.removeAlarm(alarm.id)
                            vm.maybeStartOrStopService()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricDropdown(
    selected: Metric,
    onSelected: (Metric) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text("Metric", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(metricLabel(selected), modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Metric.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(metricLabel(m)) },
                    onClick = { onSelected(m); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun OperatorDropdown(
    selected: Operator,
    onSelected: (Operator) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text("Operator", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(operatorLabel(selected), modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Operator.entries.forEach { op ->
                DropdownMenuItem(
                    text = { Text(operatorLabel(op)) },
                    onClick = { onSelected(op); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ThresholdSelector(
    metric: Metric,
    value: Double,
    onChange: (Float) -> Unit
) {
    val range = remember(metric) { rangeFor(metric) }
    val valueState by rememberUpdatedState(value)

    Column {
        // Value above slider, centered and larger
        Text(
            text = "Value: ${format1(valueState)}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalIconButton(
                onClick = {
                    val dec = d1((valueState - 0.1))
                    onChange(dec.toFloat().coerceIn(range.start, range.endInclusive))
                }
            ) { Icon(Icons.Filled.Remove, contentDescription = "Minus 0.1") }

            Slider(
                value = valueState.toFloat().coerceIn(range.start, range.endInclusive),
                onValueChange = { onChange(it) },
                valueRange = range,
                steps = 0,
                modifier = Modifier.weight(1f)
            )

            FilledTonalIconButton(
                onClick = {
                    val inc = d1((valueState + 0.1))
                    onChange(inc.toFloat().coerceIn(range.start, range.endInclusive))
                }
            ) { Icon(Icons.Filled.Add, contentDescription = "Plus 0.1") }
        }

        Spacer(Modifier.height(6.dp))
        // Range below slider, centered
        Text(
            text = "Range: ${format1(range.start)} – ${format1(range.endInclusive)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${metricLabel(alarm.metric)} ${operatorSymbol(alarm.operator)} ${format1(alarm.threshold)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (alarm.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            Spacer(Modifier.width(6.dp))
            FilledTonalIconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

// ------- Label helpers (presentation only) -------

private fun metricLabel(m: Metric) = when (m) {
    Metric.F_INDEX -> "F index"
    Metric.TEMPERATURE -> "Temperature (°C)"
    Metric.HUMIDITY -> "Humidity (%)"
    Metric.WIND_SPEED -> "Wind (m/s)"
}

private fun operatorLabel(op: Operator) = when (op) {
    Operator.GREATER_EQUAL -> "≥"
    Operator.LESS_EQUAL -> "≤"
    Operator.EQUAL -> "="
}

private fun operatorSymbol(op: Operator) = when (op) {
    Operator.GREATER_EQUAL -> "≥"
    Operator.LESS_EQUAL -> "≤"
    Operator.EQUAL -> "="
}

private fun rangeFor(metric: Metric): ClosedFloatingPointRange<Float> = when (metric) {
    Metric.F_INDEX     -> 0.0f..6.1f
    Metric.TEMPERATURE -> -10f..50f
    Metric.HUMIDITY    -> 0f..100f
    Metric.WIND_SPEED  -> 0f..30f
}

private fun d1(x: Double): Double =
    BigDecimal(x).setScale(1, RoundingMode.HALF_UP).toDouble()

private fun format1(x: Number): String = "%,.1f".format(x.toDouble())

private fun openNotificationSettings(ctx: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${ctx.packageName}".toUri()
        }
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}

/** Wrapper para pedir POST_NOTIFICATIONS solo en 33+ */
private fun requestPostNotificationsIfNeeded(
    launcher: ActivityResultLauncher<String>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(PERM_POST_NOTIFICATIONS)
    }
}
