package site.weatherstation.ui.notifications

import android.os.Build

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import site.weatherstation.notifications.AlarmForegroundService
import site.weatherstation.util.alignedTicker
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

data class NotificationsUiState(
    val selectedMetric: Metric = Metric.F_INDEX,
    val selectedOperator: Operator = Operator.GREATER_EQUAL,
    val threshold: Double = 3.1,
    val alarms: List<Alarm> = emptyList(),
    val isLoading: Boolean = false
) {
    val isFull: Boolean get() = alarms.size >= 4
    val remainingSlots: Int get() = (4 - alarms.size).coerceAtLeast(0)
}

class NotificationsViewModel : ViewModel() {

// Actions actor to serialize DataStore mutations and coalesce service decisions
private sealed interface AlarmAction {
    data class Create(val alarm: Alarm) : AlarmAction
    data class Remove(val id: Long) : AlarmAction
    data class SetEnabled(val id: Long, val enabled: Boolean) : AlarmAction
}
private val actions = Channel<AlarmAction>(capacity = Channel.UNLIMITED)

// Debounce state for maybeStartOrStopService
private var lastServiceWanted: Boolean? = null
private var maybeJob: Job? = null


    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState

    private var appContext: Context? = null
    private var initialized = false

    fun init(context: Context) {

// Actor consumer: process all mutations in a single IO coroutine
viewModelScope.launch(Dispatchers.IO) {
    for (action in actions) {
        try {
            when (action) {
                
is AlarmAction.Create -> {
    val current = AlarmDataStore.getAlarms(appContext!!)
    val next = current + action.alarm // permitir duplicados (misma condición)
    AlarmDataStore.setAlarms(appContext!!, next)
    AlarmEvaluator.resetState(action.alarm.id)
    AlarmEvaluator.evaluateOnce(appContext!!)
}

                is AlarmAction.Remove -> {
                    val current = AlarmDataStore.getAlarms(appContext!!)
                    val next = current.filterNot { it.id == action.id }
                    AlarmDataStore.setAlarms(appContext!!, next)
                    AlarmEvaluator.resetState(action.id)
                }
                is AlarmAction.SetEnabled -> {
                    val current = AlarmDataStore.getAlarms(appContext!!)
                    val next = current.map { if (it.id == action.id) it.copy(enabled = action.enabled) else it }
                    AlarmDataStore.setAlarms(appContext!!, next)
                    AlarmEvaluator.resetState(action.id)
                    if (action.enabled) AlarmEvaluator.evaluateOnce(appContext!!)
                }
            }
        } catch (_: Exception) { /* keep actor alive */ }
    }
}

        if (initialized) return
        initialized = true
        appContext = context.applicationContext

        // Cargar desde DataStore y observar cambios
        viewModelScope.launch {
            AlarmDataStore.alarmsFlow(appContext!!).collect { persisted ->
                _uiState.update { it.copy(alarms = persisted) }
                maybeStartOrStopService()
            }
        }

        // Ticker local (cuando la app está en foreground)
        viewModelScope.launch {
            alignedTicker(10_000L, 5_000L).collect {
                AlarmEvaluator.evaluateOnce(appContext!!)
            }
        }
    }

    // ---------- UI Mutators ----------

    fun onMetricChange(metric: Metric) {
        val range = rangeFor(metric)
        val mid = ((range.start + range.endInclusive) / 2.0f).toDouble()
        _uiState.update { it.copy(selectedMetric = metric, threshold = d1(mid)) }
    }

    fun onOperatorChange(operator: Operator) {
        _uiState.update { it.copy(selectedOperator = operator) }
    }

    fun onThresholdChange(value: Float) {
        val range = rangeFor(uiState.value.selectedMetric)
        val clamped = value.coerceIn(range.start, range.endInclusive)
        _uiState.update { it.copy(threshold = d1(clamped.toDouble())) }
    }

    fun rangeFor(metric: Metric): ClosedFloatingPointRange<Float> = when (metric) {
        Metric.F_INDEX     -> 0.0f..6.1f
        Metric.TEMPERATURE -> -10.0f..50.0f
        Metric.HUMIDITY    -> 0.0f..100.0f
        Metric.WIND_SPEED  -> 0.0f..30.0f
    }

    fun addAlarm() {

val st = uiState.value
val new = Alarm(
    id = System.currentTimeMillis(),
    metric = st.selectedMetric,
    operator = st.selectedOperator,
    threshold = st.threshold,
    enabled = true
)
viewModelScope.launch { actions.send(AlarmAction.Create(new)) }

    }

    fun removeAlarm(id: Long) {

viewModelScope.launch { actions.send(AlarmAction.Remove(id)) }

    }

    /** Toggle enable/disable. Al desactivar, queda limpia; al activar, evalúa de inmediato. */
    fun setAlarmEnabled(id: Long, enabled: Boolean) {

viewModelScope.launch { actions.send(AlarmAction.SetEnabled(id, enabled)) }

    }

    fun evaluateNow() {
        val ctx = appContext ?: return
        viewModelScope.launch { AlarmEvaluator.evaluateOnce(ctx) }
    }

    // ---------- Helpers ----------

    fun maybeStartOrStopService() {
val ctx = appContext ?: return
val hasEnabled = uiState.value.alarms.any { it.enabled }
if (lastServiceWanted == hasEnabled) return
lastServiceWanted = hasEnabled
maybeJob?.cancel()
maybeJob = viewModelScope.launch {
    delay(300)
    val intent = Intent(ctx, AlarmForegroundService::class.java)
    if (hasEnabled) {
        intent.action = AlarmForegroundService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    } else {
        intent.action = AlarmForegroundService.ACTION_STOP
        ctx.startService(intent)
    }
}
}

    // Redondeo exacto a 1 decimal (HALF_UP)
    private fun d1(x: Double) =
        BigDecimal(x).setScale(1, RoundingMode.HALF_UP).toDouble()
}
