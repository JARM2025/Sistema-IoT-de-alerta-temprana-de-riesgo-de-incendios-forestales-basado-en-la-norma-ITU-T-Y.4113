package site.weatherstation.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import site.weatherstation.util.prettyErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import site.weatherstation.net.RetrofitProvider
import site.weatherstation.net.Order

data class TimePoint(val ts: String, val value: Double?)

enum class MeasureOption(
    val title: String,
    val measurement: String,
    val field: String,
    val sensorId: String?
) {
    F_INDEX("F index", "f_index", "F_index", "calcF"),
    TEMP("Sensor 1 - Temperature", "sensor_data", "temperature", "1"),
    HUM("Sensor 1 - Humidity", "sensor_data", "humidity", "1"),
    WIND("Sensor 2 - Windspeed", "wind_data", "wind_speed", "2"),
}

data class DetailsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val points: List<TimePoint> = emptyList(),
    val selected: MeasureOption = MeasureOption.F_INDEX
)

class DetailsViewModel : ViewModel() {
    private val _ui = MutableStateFlow(DetailsUiState(isLoading = true))
    val ui: StateFlow<DetailsUiState> = _ui

    init {
        refresh()
    }

    fun onSelect(option: MeasureOption) {
        _ui.value = _ui.value.copy(selected = option)
        refresh()
    }

    fun refresh() {
        val sel = _ui.value.selected
        _ui.value = _ui.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val api = RetrofitProvider.api
                val res = api.getRange(
                    measurement = sel.measurement,
                    field = sel.field,
                    sensorId = sel.sensorId,
                    range = "5m",
                    agg = null,
                    interval = null,
                    tz = site.weatherstation.util.deviceZoneIdString(),
                    order = Order.ASC.wire,
                    limit = 5000
                )
                val pts = res.points.map { TimePoint(it.ts, it.value) }
                _ui.value = _ui.value.copy(isLoading = false, points = pts, error = null)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(isLoading = false, error = prettyErrorMessage(e), points = emptyList())
            }
        }
    }
}
