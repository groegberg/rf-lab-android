package dev.rflab.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Tappable user-calibrated room rectangle; never substitutes for camera-derived geometry. */
class ManualMapView(context: Context) : View(context) {
    var survey: ManualSurvey? = null
        set(value) { field = value; invalidate() }
    var selectedBssid = ""
        set(value) { field = value; invalidate() }
    var placeMode = true
    var onPlace: ((Float, Float) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressX = 0f
    private var pressY = 0f
    private var dragged = false

    private fun roomRect(widthM: Float, heightM: Float): RectF {
        val padding = 38f * density
        val contentW = (width - padding * 2).coerceAtLeast(1f)
        val contentH = (height - padding * 2).coerceAtLeast(1f)
        val ratio = min(contentW / widthM, contentH / heightM)
        val rw = ratio * widthM
        val rh = ratio * heightM
        val left = (width - rw) / 2f
        val top = (height - rh) / 2f
        return RectF(left, top, left + rw, top + rh)
    }

    private fun tint(rssi: Int): Int {
        val hue = 6f + ((rssi + 90f) / 55f).coerceIn(0f, 1f) * 129f
        return Color.HSVToColor(floatArrayOf(hue, .85f, .96f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 23, 37))
        val survey = survey ?: return
        val widthM = survey.widthM
        val heightM = survey.heightM
        val room = roomRect(widthM, heightM)
        val markers = survey.allPoints()
        val values = survey.summary()
        fun toX(x: Float) = room.left + x / widthM * room.width()
        fun toY(z: Float) = room.top + z / heightM * room.height()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(20, 37, 54)
        canvas.drawRect(room, paint)

        val signalAtPoints = values.filter { it.bssid == selectedBssid }
        val positioned = markers.mapNotNull { point ->
            val measurement = signalAtPoints.find { it.pointId == point.id }
            if (measurement != null) Triple(point.x, point.z, measurement.median) else null
        }
        // Local IDW map only if 3+ distinct points spread through two spatial axes.
        if (positioned.size >= 3 &&
            positioned.maxOf { it.first } - positioned.minOf { it.first } >= .5f &&
            positioned.maxOf { it.second } - positioned.minOf { it.second } >= .5f) {
            val cols = 30
            val rows = 25
            val cellW = widthM / cols
            val cellH = heightM / rows
            for (j in 0 until rows) for (i in 0 until cols) {
                val x = (i + .5f) * cellW
                val z = (j + .5f) * cellH
                var nearest = Float.MAX_VALUE
                var sum = 0.0
                var weight = 0.0
                for (p in positioned) {
                    val deltaX = x - p.first
                    val deltaZ = z - p.second
                    val d2 = deltaX * deltaX + deltaZ * deltaZ
                    if (d2 < nearest) nearest = d2
                    if (d2 < 2.0f * 2.0f) {
                        val w = 1.0 / (d2 + .09)
                        sum += w * p.third
                        weight += w
                    }
                }
                // Prevent apparently measured data in unsurveyed regions.
                if (weight > 0.0 && nearest < 1.1f * 1.1f) {
                    val c = tint((sum / weight).roundToInt())
                    paint.color = Color.argb(105, Color.red(c), Color.green(c), Color.blue(c))
                    canvas.drawRect(toX(x - cellW / 2), toY(z - cellH / 2),
                        toX(x + cellW / 2), toY(z + cellH / 2), paint)
                }
            }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.argb(74, 165, 194, 221)
        for (i in 0..widthM.toInt()) {
            val x = toX(i.toFloat())
            canvas.drawLine(x, room.top, x, room.bottom, paint)
        }
        for (i in 0..heightM.toInt()) {
            val y = toY(i.toFloat())
            canvas.drawLine(room.left, y, room.right, y, paint)
        }
        paint.strokeWidth = 2.5f * density
        paint.color = Color.rgb(142, 172, 199)
        canvas.drawRect(room, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 11f * density
        paint.color = Color.rgb(203, 226, 245)
        canvas.drawText(String.format(java.util.Locale.GERMAN, "%.1f m", widthM),
            room.centerX() - 13f * density, room.top - 8f * density, paint)
        canvas.drawText(String.format(java.util.Locale.GERMAN, "%.1f m", heightM),
            4f * density, room.centerY(), paint)

        for (marker in markers) {
            val x = toX(marker.x)
            val y = toY(marker.z)
            val signal = signalAtPoints.find { it.pointId == marker.id }
            paint.color = if (signal != null) tint(signal.median) else Color.rgb(174, 183, 196)
            canvas.drawCircle(x, y, 9f * density, paint)
            paint.color = Color.rgb(9, 19, 30)
            canvas.drawCircle(x, y, 4f * density, paint)
            if (marker.id == survey.selectedId) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * density
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 12.7f * density, paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = Color.WHITE
            paint.textSize = 11f * density
            canvas.drawText(marker.label + (if (signal == null) "" else " " + signal.median + "dBm"),
                x + 11f * density, y - 9f * density, paint)
        }
        if (markers.isEmpty()) {
            paint.color = Color.rgb(217, 229, 241)
            paint.textSize = 12f * density
            canvas.drawText("Tippe auf den Plan, um P1 zu setzen",
                room.left + 6f * density, room.centerY(), paint)
        }
        paint.color = Color.rgb(160, 184, 206)
        paint.textSize = 10f * density
        canvas.drawText("Manuelle Positionen · keine ARCore-Geometrie",
            8f * density, height - 8f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressX = event.x; pressY = event.y; dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - pressX) > 12f * density ||
                    abs(event.y - pressY) > 12f * density) dragged = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val s = survey ?: return true
                if (dragged) return true
                val rect = roomRect(s.widthM, s.heightM)
                if (!rect.contains(event.x, event.y)) return true
                val x = ((event.x - rect.left) / rect.width() * s.widthM).coerceIn(0f, s.widthM)
                val z = ((event.y - rect.top) / rect.height() * s.heightM).coerceIn(0f, s.heightM)
                if (!placeMode) {
                    val best = s.allPoints().minByOrNull { point ->
                        val px = rect.left + point.x / s.widthM * rect.width()
                        val py = rect.top + point.z / s.heightM * rect.height()
                        (px - event.x) * (px - event.x) + (py - event.y) * (py - event.y)
                    }
                    if (best != null) onSelect?.invoke(best.id)
                } else onPlace?.invoke(x, z)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
}
