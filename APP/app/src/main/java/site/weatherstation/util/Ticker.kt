package site.weatherstation.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlin.math.ceil

fun alignedTicker(periodMs: Long, offsetMs: Long = 0L) = flow {
    require(periodMs > 0) { "periodMs must be > 0" }
    val now = System.currentTimeMillis()
    val next = ((ceil((now - offsetMs) / periodMs.toDouble()) * periodMs) + offsetMs).toLong()
    val initialDelay = (next - now).coerceAtLeast(0)
    if (initialDelay > 0) delay(initialDelay)
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}
