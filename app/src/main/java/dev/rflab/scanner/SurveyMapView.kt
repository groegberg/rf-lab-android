package dev.rflab.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interactive local-frame WiFi map. In 3D mode, the point cloud is a stylized
 * isometric projection of REAL optical ARCore Depth samples, not RF geometry.
 */
class SurveyMapView(context: Context) : View(context) {
    private var poses: List<SurveyPose> = emptyList()
    private var radios: List<SurveyRadio> = emptyList()
    private var cloud: List<SurveyDepth> = emptyList()
    var selectedBssid: String = ""
    var is3d: Boolean = false
    var showDepth: Boolean = false
    var heatmap: Boolean = true
    private var scaleMultiplier = 1f
    private var dx = 0f
    private var dy = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var yaw = -0.45f
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pinch = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleMultiplier = (scaleMultiplier * detector.scaleFactor).coerceIn(.3f, 8f)
                invalidate()
                return true
            }
        })

    fun update(data: SurveyData, selected: String = selectedBssid) {
        poses = data.snapshotPoses()
        radios = data.snapshotRadios()
        cloud = data.snapshotDepth()
        selectedBssid = selected
        invalidate()
    }

    fun resetView() {
        scaleMultiplier = 1f; dx = 0f; dy = 0f; yaw = -0.45f; invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !pinch.isInProgress) {
                    val ddx = event.x - lastX
                    val ddy = event.y - lastY
                    if (is3d && event.y < height * .18f) yaw += ddx / 230f
                    else { dx += ddx; dy += ddy }
                    lastX = event.x; lastY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun colorForRssi(dbm: Int): Int {
        val t = ((dbm + 90f) / 55f).coerceIn(0f, 1f)
        val hue = 5f + 132f * t
        return Color.HSVToColor(floatArrayOf(hue, .84f, .94f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 21, 33))
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val active = radios.filter { it.pose != null &&
            (selectedBssid.isBlank() || it.bssid == selectedBssid) }
        val validPositions = poses
        val originX = if (validPositions.isNotEmpty()) validPositions.map { it.x }.average().toFloat() else 0f
        val originY = if (validPositions.isNotEmpty()) validPositions.map { it.y }.average().toFloat() else 0f
        val originZ = if (validPositions.isNotEmpty()) validPositions.map { it.z }.average().toFloat() else 0f

        val allXY = validPositions.map { Pair(it.x, it.z) } +
            active.mapNotNull { it.pose?.let { pose -> Pair(pose.x, pose.z) } }
        val widthM = if (allXY.isNotEmpty()) (allXY.maxOf { it.first } - allXY.minOf { it.first }) else 2f
        val heightM = if (allXY.isNotEmpty()) (allXY.maxOf { it.second } - allXY.minOf { it.second }) else 2f
        val maxExtent = max(2.5f, max(widthM, heightM) * 1.45f)
        val pixelsPerMeter = min(w, h) / maxExtent * scaleMultiplier

        fun project(x: Float, y: Float, z: Float): Pair<Float, Float> {
            val px = x - originX
            val py = y - originY
            val pz = z - originZ
            if (!is3d) {
                return Pair(w / 2f + px * pixelsPerMeter + dx,
                    h / 2f + pz * pixelsPerMeter + dy)
            }
            val c = cos(yaw)
            val s = sin(yaw)
            val xr = px * c - pz * s
            val zr = px * s + pz * c
            return Pair(w / 2f + xr * pixelsPerMeter + dx,
                h * .55f + (zr * .46f - py * .84f) * pixelsPerMeter + dy)
        }

        // Local metric grid: maps are in ARCore-local coordinates, not compass aligned.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * .6f
        paint.color = Color.argb(45, 162, 188, 215)
        for (i in -12..12) {
            val fx = i.toFloat()
            val a = project(originX + fx, originY, originZ - 12f)
            val b = project(originX + fx, originY, originZ + 12f)
            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
            val c = project(originX - 12f, originY, originZ + fx)
            val d = project(originX + 12f, originY, originZ + fx)
            canvas.drawLine(c.first, c.second, d.first, d.second, paint)
        }

        // IDW interpolation is intentionally local: no fabricated global room coverage.
        if (!is3d && heatmap) {
            val dots = active.mapNotNull { item ->
                val p = item.pose
                if (p != null) Triple(p.x, p.z, item.rssi) else null
            }.takeLast(1200)
            val spreadX = if (dots.isNotEmpty()) dots.maxOf { it.first } - dots.minOf { it.first } else 0f
            val spreadZ = if (dots.isNotEmpty()) dots.maxOf { it.second } - dots.minOf { it.second } else 0f
            if (dots.size >= 6 && spreadX > .35f && spreadZ > .35f) {
                val xmin = dots.minOf { it.first }
                val xmax = dots.maxOf { it.first }
                val zmin = dots.minOf { it.second }
                val zmax = dots.maxOf { it.second }
                val stepM = max(.12f, max(spreadX, spreadZ) / 26f)
                val cell = stepM * pixelsPerMeter
                var z = zmin
                while (z <= zmax) {
                    var x = xmin
                    while (x <= xmax) {
                        val neighbors = dots.map { dot ->
                            val ddx = dot.first - x
                            val ddz = dot.second - z
                            Pair(ddx * ddx + ddz * ddz, dot.third)
                        }.sortedBy { it.first }.take(8)
                        if (neighbors.size >= 3 && neighbors[0].first < .65f * .65f) {
                            val mean = neighbors.fold(Pair(0.0, 0.0)) { acc, item ->
                                val ww = 1.0 / (item.first + .035)
                                Pair(acc.first + item.second * ww, acc.second + ww)
                            }
                            val color = colorForRssi((mean.first / mean.second).toInt())
                            val coord = project(x, originY, z)
                            paint.style = Paint.Style.FILL
                            paint.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
                            canvas.drawRect(coord.first - cell / 2f, coord.second - cell / 2f,
                                coord.first + cell / 2f, coord.second + cell / 2f, paint)
                        }
                        x += stepM
                    }
                    z += stepM
                }
            }
        }

        // Optical point cloud, visually distinct from wireless observations.
        if (showDepth && cloud.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(128, 100, 191, 233)
            for (p in cloud.takeLast(18000)) {
                val at = project(p.x, p.y, p.z)
                if (at.first in 0f..w && at.second in 0f..h)
                    canvas.drawCircle(at.first, at.second, density * 1.05f, paint)
            }
        }

        // Only draw line segments within same ARCore run; never bridge reset origins.
        if (validPositions.size >= 2) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density * 2f
            paint.color = Color.rgb(115, 217, 255)
            val path = Path()
            var last: SurveyPose? = null
            for (p in validPositions) {
                val xy = project(p.x, p.y, p.z)
                val previous = last
                if (previous == null || previous.run != p.run ||
                    abs(p.timeNs - previous.timeNs) > 2_000_000_000L) {
                    path.moveTo(xy.first, xy.second)
                } else path.lineTo(xy.first, xy.second)
                last = p
            }
            canvas.drawPath(path, paint)
        }

        paint.style = Paint.Style.FILL
        for (item in active.takeLast(2000)) {
            val p = item.pose ?: continue
            val xy = project(p.x, p.y, p.z)
            val c = colorForRssi(item.rssi)
            paint.color = Color.argb(150, 5, 12, 18)
            canvas.drawCircle(xy.first, xy.second, density * 5f, paint)
            paint.color = c
            canvas.drawCircle(xy.first, xy.second, density * 3.25f, paint)
        }

        validPositions.lastOrNull()?.let { p ->
            val at = project(p.x, p.y, p.z)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(at.first, at.second, density * 5.5f, paint)
            paint.color = Color.rgb(45, 164, 241)
            canvas.drawCircle(at.first, at.second, density * 3.3f, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(219, 235, 249)
        paint.textSize = 12f * density
        canvas.drawText(if (is3d) "3D · isometrisch" else "2D · Draufsicht", 13f * density, 24f * density, paint)
        paint.color = Color.rgb(151, 174, 196)
        paint.textSize = 10.5f * density
        canvas.drawText("ARCore-Koordinaten · Meter · 2 Finger: Zoom", 13f * density, 43f * density, paint)
        if (validPositions.isEmpty()) {
            paint.color = Color.rgb(203, 216, 227)
            paint.textSize = 14f * density
            canvas.drawText("ARCore starten, um den Weg zu erfassen", 15f * density, h * .56f, paint)
        }
        // RSSI legend: red weaker, green stronger.
        val left = 16f * density
        val bottom = h - 16f * density
        val legendWidth = min(w * .6f, 220f * density)
        val segments = 35
        for (i in 0 until segments) {
            val signal = (-90f + i / (segments - 1f) * 55f).roundToInt()
            paint.color = colorForRssi(signal)
            canvas.drawRect(left + i * legendWidth / segments, bottom - 6f * density,
                left + (i + 1) * legendWidth / segments, bottom, paint)
        }
        paint.color = Color.rgb(210, 225, 235)
        paint.textSize = 10f * density
        canvas.drawText("−90 dBm", left, bottom - 10f * density, paint)
        canvas.drawText("−35 dBm", left + legendWidth - 51f * density,
            bottom - 10f * density, paint)
    }
}
