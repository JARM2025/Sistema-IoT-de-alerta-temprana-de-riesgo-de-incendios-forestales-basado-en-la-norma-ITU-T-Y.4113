package site.weatherstation.ui.gauges

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/* ===========================
 * VERTICAL THERMOMETER
 * =========================== */
@Composable
fun ThermometerGauge(
    value: Float?,
    modifier: Modifier = Modifier,
    min: Float = -5f,
    max: Float = 50f,
    height: Dp = 220.dp,
    tubeWidth: Dp = 28.dp,
    bulbDiameter: Dp = 56.dp,
    majorStep: Float = 10f,
    minorPerMajor: Int = 4,
    units: String = "°C",
    showValueLabel: Boolean = true,
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val tube = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val textCol = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)

    val clamped: Float? = value?.coerceIn(min, max)
    val frac: Float = if (clamped == null) 0f else ((clamped - min) / (max - min)).coerceIn(0f, 1f)
    val animFrac by animateFloatAsState(
        targetValue = frac,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "thermoFrac"
    )

    Column(
        modifier = modifier.width(IntrinsicSize.Min).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Value label (2 decimals)
        if (showValueLabel) {
            val label = if (value == null) "—" else String.format(Locale.US, "%.1f%s", value, units)
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }

        Canvas(modifier = Modifier.height(height).width(bulbDiameter)) {
            val w = size.width
            val h = size.height
            val bulbR = (bulbDiameter.toPx() / 2f)
            val tubeW = tubeWidth.toPx()
            val tubeLeft = (w - tubeW) / 2f
            val tubeRight = tubeLeft + tubeW
            val tubeTop = 8f
            val tubeBottom = h - bulbR * 1.1f

            // Tube
            val tubeRect = Rect(tubeLeft, tubeTop, tubeRight, tubeBottom)
            drawRoundRect(
                color = bg,
                topLeft = tubeRect.topLeft,
                size = tubeRect.size,
                cornerRadius = CornerRadius(tubeW / 2f, tubeW / 2f),
                style = Fill
            )
            drawRoundRect(
                color = tube,
                topLeft = tubeRect.topLeft,
                size = tubeRect.size,
                cornerRadius = CornerRadius(tubeW / 2f, tubeW / 2f),
                style = Stroke(width = 2f)
            )

            // Bulb
            val bulbCenter = Offset(w / 2f, tubeBottom + bulbR * 0.55f)
            drawCircle(color = bg, radius = bulbR, center = bulbCenter)
            drawCircle(color = tube, radius = bulbR, center = bulbCenter, style = Stroke(width = 2f))

            // Fill gradient
            val grad = Brush.verticalGradient(
                colors = listOf(Color(0xFF4FC3F7), Color(0xFFFFF176), Color(0xFFE57373)),
                startY = tubeBottom, endY = tubeTop
            )
            val fillTop = tubeBottom - (tubeBottom - tubeTop) * animFrac
            val fillRect = Rect(tubeLeft + 3f, fillTop, tubeRight - 3f, tubeBottom - 3f)
            drawRoundRect(
                brush = grad,
                topLeft = fillRect.topLeft,
                size = Size(fillRect.width, fillRect.height),
                cornerRadius = CornerRadius(tubeW / 2.2f, tubeW / 2.2f)
            )
            drawCircle(brush = grad, radius = bulbR - 3f, center = bulbCenter)

            // Ticks
            val majorTickLen = 10f
            val minorTickLen = 6f
            val pxPerUnit = (tubeBottom - tubeTop) / (max - min)
            fun yFor(v: Float): Float = (tubeBottom - ((v - min) * pxPerUnit))
            val startMajor: Float = (ceil((min / majorStep).toDouble()).toFloat() * majorStep)

            val paint = android.graphics.Paint().apply {
                color = textCol.toArgb()
                textAlign = android.graphics.Paint.Align.LEFT
                textSize = 11.sp.toPx()
                isAntiAlias = true
            }
            var mv = startMajor
            while (mv <= max + 1e-6f) {
                val y = yFor(mv)
                drawLine(
                    color = textCol,
                    start = Offset(tubeRight + 3f, y),
                    end = Offset(tubeRight + 3f + majorTickLen, y),
                    strokeWidth = 2f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    mv.toInt().toString(),
                    tubeRight + 3f + majorTickLen + 4f,
                    y + 4f,
                    paint
                )
                if (minorPerMajor > 0) {
                    val step = majorStep / (minorPerMajor + 1)
                    for (i in 1..minorPerMajor) {
                        val v = mv + i * step
                        if (v >= max) break
                        val my = yFor(v)
                        drawLine(
                            color = textCol.copy(alpha = 0.6f),
                            start = Offset(tubeRight + 3f, my),
                            end = Offset(tubeRight + 3f + minorTickLen, my),
                            strokeWidth = 1.5f
                        )
                    }
                }
                mv += majorStep
            }
        }
    }
}

/* ===========================
 * F-INDEX: SEGMENTED GAUGE + NEEDLE
 * =========================== */
@Composable
fun FIndexSegmentedGauge(
    value: Float?,
    modifier: Modifier = Modifier,
    minVal: Float = 0f,
    maxVal: Float = 6.1f,            // dial ends at 6.1
    startAngle: Float = 150f,
    sweepAngle: Float = 240f,
    thickness: Dp = 28.dp,
    needleLengthRatio: Float = 0.82f,
    showValue: Boolean = true,
    showRangeBorders: Boolean = true, // marks at band borders
) {
    data class Seg(val from: Float, val to: Float, val color: Color, val name: String)

    // Absolute segments based on your table (English labels)
    val segmentsAbs = listOf(
        Seg(0f, 0.7f, Color(0xFF66BB6A), "Low"),
        Seg(0.7f, 1.5f, Color(0xFFFFEE58), "Moderate"),
        Seg(1.5f, 2.7f, Color(0xFFFFA726), "High"),
        Seg(2.7f, 6.1f, Color(0xFFF4511E), "Very high"),
    )

    // Risk label/color for current value (includes Extreme if > 6.1)
    data class Risk(val name: String, val color: Color)
    fun riskFor(v: Float?): Risk? = v?.let {
        when {
            it < 0.7f  -> Risk("Low",        Color(0xFF66BB6A))
            it < 1.5f  -> Risk("Moderate",   Color(0xFFFFEE58))
            it < 2.7f  -> Risk("High",       Color(0xFFFFA726))
            it <= 6.1f -> Risk("Very high",  Color(0xFFF4511E))
            else       -> Risk("Extreme",    Color(0xFFEF5350))
        }
    }
    val risk = riskFor(value)

    // Normalize value -> fraction in 0..1
    fun fracOf(v: Float): Float = ((v - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)

    val bg = MaterialTheme.colorScheme.surfaceVariant
    val onBg = MaterialTheme.colorScheme.onSurface

    val needleFrac = fracOf(value ?: 0f)
    val anim by animateFloatAsState(
        targetValue = needleFrac,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "findexFrac"
    )

    Column(modifier = modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Top text: risk level + numeric value (2 decimals)
        if (showValue) {
            val riskLine = buildAnnotatedString {
                append("Risk level: ")
                withStyle(SpanStyle(color = risk?.color ?: MaterialTheme.colorScheme.onSurface)) {
                    append(risk?.name ?: "—")
                }
            }
            Text(riskLine, style = MaterialTheme.typography.labelLarge)
            val txt = if (value == null) "—" else String.format(Locale.US, "%.1f", value)
            Text(txt, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }

        Box(
            modifier = Modifier.height(220.dp).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val w = size.width
                val h = size.height
                val diameter = min(w, h)
                val outer = diameter * 0.95f
                val inner = outer - thickness.toPx()
                val arcSize = Size(outer, outer)
                val arcTopLeft = Offset((w - outer) / 2f, (h - outer) / 2f)

                // Base arc
                drawArc(
                    color = bg,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = thickness.toPx(), cap = StrokeCap.Round)
                )

                // Colored segments
                for (seg in segmentsAbs) {
                    val segStart = startAngle + sweepAngle * fracOf(seg.from)
                    val segSweep = sweepAngle * (fracOf(seg.to) - fracOf(seg.from))
                    if (segSweep > 0f) {
                        drawArc(
                            color = seg.color,
                            startAngle = segStart,
                            sweepAngle = segSweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = thickness.toPx(), cap = StrokeCap.Butt)
                        )
                    }
                }

                // Range border marks (0.0, 0.7, 1.5, 2.7, 6.1)
                if (showRangeBorders) {
                    val outerR = outer / 2f
                    val innerR = inner / 2f
                    fun pt(aDeg: Float, r: Float): Offset {
                        val a = (aDeg.toDouble() * (PI / 180.0))
                        return Offset(w / 2f + cos(a).toFloat() * r, h / 2f + sin(a).toFloat() * r)
                    }
                    val borders = listOf(minVal, 0.7f, 1.5f, 2.7f, 6.1f)
                    val paint = android.graphics.Paint().apply {
                        color = onBg.toArgb()
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 10.sp.toPx()
                        isAntiAlias = true
                    }
                    borders.forEach { b ->
                        val a = startAngle + sweepAngle * fracOf(b)
                        val p1 = pt(a, outerR + 6f)
                        val p2 = pt(a, innerR - 6f)
                        drawLine(color = onBg.copy(alpha = 0.9f), start = p1, end = p2, strokeWidth = 2.2f)
                        val lp = pt(a, innerR - 16f)
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.US, "%.1f", b),
                            lp.x, lp.y + 4f, paint
                        )
                    }
                }

                // Needle (sticks to end if > 6.1)
                val needleAngle = startAngle + anim * sweepAngle
                val center = Offset(w / 2f, h / 2f)
                val radius = (inner / 2f) * needleLengthRatio
                val rad = (needleAngle.toDouble() * (PI / 180.0))
                val tip = Offset(center.x + cos(rad).toFloat() * radius, center.y + sin(rad).toFloat() * radius)
                drawLine(color = onBg, start = center, end = tip, strokeWidth = 4f, cap = StrokeCap.Round)
                drawCircle(color = onBg, radius = 6f, center = center)
            }
        }

        // Legend
        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Color(0xFF66BB6A) to "Low",
                Color(0xFFFFEE58) to "Moderate",
                Color(0xFFFFA726) to "High",
                Color(0xFFF4511E) to "Very high",
                Color(0xFFEF5350) to "Extreme"
            ).forEach { (c, t) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(10.dp)) { drawRect(color = c) }
                    Spacer(Modifier.width(4.dp))
                    Text(t, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

/* ===========================
 * HUMIDITY: RING + DROP
 *  - Accepts 0..100 or 0..1 (it is normalized)
 * =========================== */
@Composable
fun HumidityRing(
    value: Float?,
    modifier: Modifier = Modifier,
    thickness: Dp = 20.dp,
    label: String = "Humidity",
    showValue: Boolean = true,
) {
    val frac = when (value) {
        null -> 0f
        else -> {
            val v = if (value > 1f) value / 100f else value
            v.coerceIn(0f, 1f)
        }
    }

    val track = MaterialTheme.colorScheme.surfaceVariant
    val blue1 = Color(0xFF42A5F5)
    val blue2 = Color(0xFF80D8FF)

    val anim by animateFloatAsState(
        targetValue = frac,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "humidityFrac"
    )

    Column(modifier = modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showValue) {
            val perc = if (value == null) null else (if (value > 1f) value else value * 100f)
            val txt = if (perc == null) "—%" else String.format(Locale.US, "%.1f%%", perc)
            Text(txt, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }

        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val w = size.width
                val h = size.height
                val diameter = min(w, h)
                val outer = diameter * 0.95f
                val arcSize = Size(outer, outer)
                val arcTopLeft = Offset((w - outer) / 2f, (h - outer) / 2f)

                // Base ring
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = thickness.toPx(), cap = StrokeCap.Round)
                )

                // Progress
                val grad = Brush.sweepGradient(
                    colors = listOf(blue1, blue2, blue1),
                    center = Offset(w / 2f, h / 2f)
                )
                drawArc(
                    brush = grad,
                    startAngle = -90f,
                    sweepAngle = 360f * anim,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = thickness.toPx(), cap = StrokeCap.Round)
                )

                // Decorative drop (symmetric cubic curves)
                val cx = w / 2f
                val cy = h / 2f

                val rBody = outer * 0.18f
                val bodyCy = cy + rBody * 0.30f
                val topX = cx
                val topY = bodyCy - rBody * 1.75f
                val botX = cx
                val botY = bodyCy + rBody * 0.75f

                val dropPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(topX, topY)

                    // Right side: shoulder → base
                    cubicTo(
                        cx + rBody * 1.05f, bodyCy - rBody * 0.55f, // shoulder
                        cx + rBody * 1.15f, bodyCy + rBody * 0.65f, // rounding
                        botX, botY
                    )

                    // Left side (symmetric)
                    cubicTo(
                        cx - rBody * 1.15f, bodyCy + rBody * 0.65f,
                        cx - rBody * 1.05f, bodyCy - rBody * 0.55f,
                        topX, topY
                    )
                    close()
                }

                // Fill & stroke
                drawPath(path = dropPath, color = blue1.copy(alpha = 0.12f))
                drawPath(path = dropPath, color = blue1, style = Stroke(width = 2f))

                // Small highlight inside the drop
                val highlight = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx + rBody * 0.35f, bodyCy - rBody * 0.25f)
                    cubicTo(
                        cx + rBody * 0.55f, bodyCy - rBody * 0.05f,
                        cx + rBody * 0.55f, bodyCy + rBody * 0.30f,
                        cx + rBody * 0.30f, bodyCy + rBody * 0.50f
                    )
                }
                drawPath(
                    path = highlight,
                    color = Color.White.copy(alpha = 0.25f),
                    style = Stroke(width = 3f)
                )
            }
        }

        // Optional label under the ring
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ===========================
 * WIND: SPEEDOMETER + NEEDLE
 * =========================== */
@Composable
fun WindSpeedGauge(
    value: Float?,
    modifier: Modifier = Modifier,
    max: Float = 20f,                 // dial cap (m/s)
    startAngle: Float = 135f,
    sweepAngle: Float = 270f,
    thickness: Dp = 26.dp,
    label: String = "",
    showValue: Boolean = true,
    unit: String = "m/s",
    majorStep: Float = 2f,            // major tick every 2 m/s (aligned with borders 2/6/12)
    minorEach: Int = 3,               // 3 minor ticks between majors
    showRangeBorders: Boolean = true  // show band borders
) {
    data class Seg(val from: Float, val to: Float, val color: Color, val name: String)

    // —— 4 BANDS (m/s) ——
    // 0–2  : Light
    // 2–6  : Moderate
    // 6–12 : Strong
    // 12–max : Very strong
    val segmentsAbs = listOf(
        Seg(0f, 2f, Color(0xFF66BB6A), "Light"),
        Seg(2f, 6f, Color(0xFFFFEE58), "Moderate"),
        Seg(6f, 12f, Color(0xFFFFA726), "Strong"),
        Seg(12f, max, Color(0xFFEF5350), "Very strong"),
    )

    data class Level(val name: String, val color: Color)
    fun levelFor(v: Float?): Level? = v?.let {
        when {
            it < 2f   -> Level("Light",       Color(0xFF66BB6A))
            it < 6f   -> Level("Moderate",    Color(0xFFFFEE58))
            it < 12f  -> Level("Strong",      Color(0xFFFFA726))
            else      -> Level("Very strong", Color(0xFFEF5350))
        }
    }
    val lvl = levelFor(value)

    fun fracOf(v: Float): Float = (v / max).coerceIn(0f, 1f)

    val on = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val anim by animateFloatAsState(
        targetValue = fracOf(value ?: 0f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "windFrac"
    )

    Column(modifier = modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {

        // Header: colored level + numeric value (2 decimals)
        if (showValue) {
            val head = buildAnnotatedString {
                append("Wind level: ")
                withStyle(SpanStyle(color = lvl?.color ?: on)) { append(lvl?.name ?: "—") }
            }
            Text(head, style = MaterialTheme.typography.labelLarge)
            val txt = if (value == null) "—" else String.format(Locale.US, "%.1f %s", value, unit)
            Text(txt, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }

        Box(Modifier.height(230.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val w = size.width
                val h = size.height
                val diameter = min(w, h)
                val outer = diameter * 0.95f
                val inner = outer - thickness.toPx()
                val arcSize = Size(outer, outer)
                val arcTopLeft = Offset((w - outer) / 2f, (h - outer) / 2f)

                // Base arc
                drawArc(
                    color = bg,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = thickness.toPx(), cap = StrokeCap.Round)
                )

                // Colored segments per band
                segmentsAbs.forEach { seg ->
                    val segStart = startAngle + sweepAngle * fracOf(seg.from)
                    val segSweep = sweepAngle * (fracOf(seg.to) - fracOf(seg.from))
                    if (segSweep > 0f) {
                        drawArc(
                            color = seg.color,
                            startAngle = segStart,
                            sweepAngle = segSweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = thickness.toPx(), cap = StrokeCap.Butt)
                        )
                    }
                }

                // Major/minor ticks with numbers
                val outerR = outer / 2f
                val innerR = inner / 2f
                fun pt(angleDeg: Float, r: Float): Offset {
                    val a = (angleDeg.toDouble() * (PI / 180.0))
                    return Offset(w / 2f + cos(a).toFloat() * r, h / 2f + sin(a).toFloat() * r)
                }
                val paint = android.graphics.Paint().apply {
                    color = on.toArgb()
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                }
                var mv = 0f
                while (mv <= max + 1e-6f) {
                    val a = startAngle + fracOf(mv) * sweepAngle
                    drawLine(on.copy(alpha = 0.85f), pt(a, outerR + 6f), pt(a, innerR - 10f), 2.3f)
                    val lp = pt(a, innerR - 18f)
                    drawContext.canvas.nativeCanvas.drawText(mv.toInt().toString(), lp.x, lp.y + 4f, paint)

                    if (mv < max) {
                        val step = majorStep / (minorEach + 1)
                        for (i in 1..minorEach) {
                            val v = mv + i * step
                            if (v >= max) break
                            val aa = startAngle + fracOf(v) * sweepAngle
                            drawLine(on.copy(alpha = 0.4f), pt(aa, outerR + 2f), pt(aa, innerR + 4f), 1.5f)
                        }
                    }
                    mv += majorStep
                }

                // Band borders (0, 2, 6, 12, max)
                if (showRangeBorders) {
                    val borders = listOf(0f, 2f, 6f, 12f, max)
                    borders.forEach { b ->
                        val a = startAngle + fracOf(b) * sweepAngle
                        drawLine(on.copy(alpha = 0.9f), pt(a, outerR + 8f), pt(a, innerR - 14f), 2.6f)
                    }
                }

                // Needle
                val needleAngle = startAngle + anim * sweepAngle
                val center = Offset(w / 2f, h / 2f)
                val radius = (inner / 2f) * 0.88f
                val rad = (needleAngle.toDouble() * (PI / 180.0))
                val tip = Offset(center.x + cos(rad).toFloat() * radius, center.y + sin(rad).toFloat() * radius)
                drawLine(color = on, start = center, end = tip, strokeWidth = 4f, cap = StrokeCap.Round)
                drawCircle(color = on, radius = 6f, center = center)
            }

            if (label.isNotEmpty()) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}
