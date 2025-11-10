package site.weatherstation.net

import retrofit2.http.GET
import retrofit2.http.Query

// -------- Range models --------
data class RangePoint(val ts: String, val value: Double?)
data class RangeResponse(val points: List<RangePoint> = emptyList())

// -------- Latest summary models --------
data class LatestSummaryResponse(
    val window: String,
    val timestamp_utc: String?,
    val data: LatestSummaryData
)
data class LatestSummaryData(
    val f_index: MetricLatest,
    val temperature: MetricLatest,
    val humidity: MetricLatest,
    val wind_speed: MetricLatest
)
data class MetricLatest(
    val measurement: String,
    val field: String,
    val sensor_id: String?,
    val value: Double?,
    val unit: String?,
    val timestamp_utc: String?
)

// -------- Retrofit service --------
interface ApiService {

    // Time series query with optional aggregation support
    @GET("range")
    suspend fun getRange(
        @Query("measurement") measurement: String,
        @Query("field") field: String,
        @Query("sensor_id") sensorId: String? = null,
        @Query("range") range: String,
        @Query("interval") interval: String? = null,
        @Query("agg") agg: String? = null,
        @Query("fill") fill: String? = null,
        @Query("order") order: String = "ASC",
        @Query("limit") limit: Int = 100000,
        @Query("tz") tz: String? = null
    ): RangeResponse

    // Latest summary (single request for 4 KPIs)
    @GET("latest_summary")
    suspend fun getLatestSummary(
        @Query("window") window: String = "5m"
    ): LatestSummaryResponse
}
