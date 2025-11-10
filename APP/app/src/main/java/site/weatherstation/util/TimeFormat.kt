package site.weatherstation.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Helpers para convertir y mostrar timestamps usando la zona horaria del dispositivo,
 * con utilidades adicionales para Bogotá si se requieren para depuración.
 */

// Formato común de salida, con zona
private val DISPLAY_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())

// ---- Zona del dispositivo ----
/** Zona horaria actual del dispositivo. */
fun deviceZoneId(): ZoneId = ZoneId.systemDefault()
/** Id de zona horaria del dispositivo, e.g. "America/Bogota". */
fun deviceZoneIdString(): String = deviceZoneId().id
/** ISO-8601 (UTC o con zona) -> hora local del dispositivo. */
fun formatIsoDevice(iso: String): String =
    runCatching { Instant.parse(iso) }
        .map { DISPLAY_FMT.format(it.atZone(deviceZoneId())) }
        .getOrElse { iso }
/** Instant -> hora local del dispositivo. */
fun formatInstantDevice(instant: Instant): String =
    DISPLAY_FMT.format(instant.atZone(deviceZoneId()))

// ---- Utilidades específicas para Bogotá (compatibilidad) ----
private val BOGOTA_ZONE: ZoneId = ZoneId.of("America/Bogota")
/** Convierte ISO-8601 a hora local de Bogotá (legacy). */
fun formatIsoBogota(iso: String): String =
    runCatching { Instant.parse(iso) }
        .map { DISPLAY_FMT.format(it.atZone(BOGOTA_ZONE)) }
        .getOrElse { iso }
/** Instant a hora local de Bogotá (legacy). */
fun formatInstantBogota(instant: Instant): String =
    DISPLAY_FMT.format(instant.atZone(BOGOTA_ZONE))
