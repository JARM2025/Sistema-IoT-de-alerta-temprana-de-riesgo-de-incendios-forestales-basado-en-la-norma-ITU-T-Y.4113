package site.weatherstation.ui.notifications
import java.io.IOException
import retrofit2.HttpException
import android.util.Log

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.weatherstation.notifications.AlarmCreatedReceiver
import site.weatherstation.net.RetrofitProvider
import site.weatherstation.util.formatIsoDevice
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Lógica compartida para:
 * - Cargar alarmas (DataStore)
 * - Consultar latest_summary
 * - Comparar (1 decimal, HALF_UP) con histeresis
 * - Notificar y auto-desactivar la(s) alarma(s) que disparen
 */
object AlarmEvaluator {

    // Concurrency & stability controls
    private val evalMutex = Mutex()
    private val lastFiredTsById = mutableMapOf<Long, String?>()
    private val lastTriggered = mutableSetOf<Long>()
    private val lastFireAtMsById = mutableMapOf<Long, Long>()
    private const val MIN_FIRE_INTERVAL_MS = 10_000L // 10s cooldown
    private val HYST: BigDecimal = bd1(0.1)          // banda muerta ±0.1

    suspend fun evaluateOnce(ctx: Context) = withContext(Dispatchers.IO) {
        evalMutex.withLock {
            val alarms = AlarmDataStore.getAlarms(ctx).filter { it.enabled }
            if (alarms.isEmpty()) return@withLock

            val api = RetrofitProvider.api
            val latest = try {
                api.getLatestSummary(window = "5m")
            } catch (e: Exception) {
                when (e) {
                    is HttpException, is IOException -> {
                        Log.w(TAG, "latest_summary failed: ${e.message}")
                        return@withLock
                    }
                    else -> throw e
                }
            }

            val data = latest.data
            val fIndex = data.f_index.value?.let { bd1(it) }
            val temperature = data.temperature.value?.let { bd1(it) }
            val humidity = data.humidity.value?.let { bd1(it) }
            val wind = data.wind_speed.value?.let { bd1(it) }
for (alarm in alarms) {
                val valueNullable: BigDecimal? = when (alarm.metric) {
                    Metric.F_INDEX -> fIndex
                    Metric.TEMPERATURE -> temperature
                    Metric.HUMIDITY -> humidity
                    Metric.WIND_SPEED -> wind
                }

                // DB timestamp strictly from the same metric (no cross-metric fallback)
                val metricTs = when (alarm.metric) {
                    Metric.F_INDEX      -> latest.data.f_index.timestamp_utc
                    Metric.TEMPERATURE  -> latest.data.temperature.timestamp_utc
                    Metric.HUMIDITY     -> latest.data.humidity.timestamp_utc
                    Metric.WIND_SPEED   -> latest.data.wind_speed.timestamp_utc
                }
                val humanTs = metricTs?.let { formatIsoDevice(it) }

                // Si no hay valor para esa métrica, saltamos
                val v: BigDecimal = valueNullable ?: continue

                val threshold = bd1(alarm.threshold)
                val rawTriggered = when (alarm.operator) {
                    // Inclusivo con histéresis hacia abajo: v ≥ (umbral - HYST)
                    Operator.GREATER_EQUAL -> v >= (threshold - HYST)
                    // Inclusivo con histéresis hacia arriba: v ≤ (umbral + HYST)
                    Operator.LESS_EQUAL    -> v <= (threshold + HYST)
                    // Igualdad dentro de la banda muerta: |v - umbral| ≤ HYST
                    Operator.EQUAL         -> (v - threshold).abs() <= HYST
                }

                val wasTriggered = lastTriggered.contains(alarm.id)

                val lastTs = lastFiredTsById[alarm.id]
                val tsChanged = (metricTs != null && metricTs != lastTs)
                val nowMs = SystemClock.elapsedRealtime()
                val lastAt = lastFireAtMsById[alarm.id] ?: 0L
                val cooldownOk = nowMs - lastAt >= MIN_FIRE_INTERVAL_MS

                if (rawTriggered && !wasTriggered && cooldownOk && tsChanged) {
                    val metricLabel = when (alarm.metric) {
                        Metric.F_INDEX -> "F index"
                        Metric.TEMPERATURE -> "Temperature"
                        Metric.HUMIDITY -> "Humidity"
                        Metric.WIND_SPEED -> "Wind speed"
                    }
                    val opLabel = when (alarm.operator) {
                        Operator.GREATER_EQUAL -> "≥"
                        Operator.LESS_EQUAL -> "≤"
                        Operator.EQUAL -> "="
                    }

                    val message = buildString {
                        append("$metricLabel $opLabel $threshold — value: $v")
                        if (!humanTs.isNullOrEmpty()) append(" at $humanTs")
                    }

                    val intent = Intent(ctx, AlarmCreatedReceiver::class.java)
                        .setAction(AlarmCreatedReceiver.ACTION)
                        .putExtra(AlarmCreatedReceiver.EXTRA_MESSAGE, message)
                        .putExtra(AlarmCreatedReceiver.EXTRA_ALARM_ID, alarm.id)

                    ctx.sendBroadcast(intent)

                    lastFireAtMsById[alarm.id] = nowMs
                    lastTriggered.add(alarm.id)
                    lastFiredTsById[alarm.id] = metricTs

                    // Auto-desactivar en DataStore: marcar enabled = false y persistir la lista
                    try {
                        val all = AlarmDataStore.getAlarms(ctx)
                        val updated = all.map { if (it.id == alarm.id) it.copy(enabled = false) else it }
                        AlarmDataStore.setAlarms(ctx, updated)
                    } catch (_: Exception) {
                        /* ignore persistence errors */
                    }

                } else if (!rawTriggered && wasTriggered) {
                    lastTriggered.remove(alarm.id)
                }
            }
        }
    }

    fun resetState(alarmId: Long) {
        synchronized(this) {
            lastTriggered.remove(alarmId)
            lastFiredTsById.remove(alarmId)
            lastFireAtMsById.remove(alarmId)
        }
    }

    // Redondeo HALF_UP a 1 decimal
    private fun bd1(number: Double): BigDecimal =
        BigDecimal(number).setScale(1, RoundingMode.HALF_UP)

    private fun bd1(number: Float): BigDecimal =
        BigDecimal(number.toDouble()).setScale(1, RoundingMode.HALF_UP)

    private fun bd1(number: BigDecimal): BigDecimal =
        number.setScale(1, RoundingMode.HALF_UP)

    private const val TAG = "AlarmEvaluator"
}
