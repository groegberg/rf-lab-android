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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Interactive view of measured optical depth points, intentionally not a
 * phototextured mesh. Height -> false-color hue. Drag to orbit and pinch zoom;
 * orthographic floor view optionally accepts two opposite room corners.
 */
class ScanCloudView(context: Context) : View(context) {
    var store: RoomScanStore? = null
        set(value) { field = value; invalidate() }
    var topDown = false
        set(value) { field = value; invalidate() }
    var selectCorners = false
    var corner1: Pair<Float, Float>? = null
        set(value) { field = value; invalidate() }
    var onRoomCorner: ((Float, Float) -> Unit)? = null
    var rooms: List<HomeRoom> = emptyList()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var yaw = .58f
    private var elevation = .60f
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private val density = resources.displayMetrics.density
    private val scaler = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(.55f, 3.3f)
                invalidate()
                return true
            }
        })

    private fun layout(scan: RoomScanStore, b: ScanBounds): RectF {
        val extra = .70f
        val left = b.minX + scan.originX - extra
        val right = b.maxX + scan.originX + extra
        val top = b.minZ + scan.originZ - extra
        val bottom = b.maxZ + scan.originZ + extra
        return RectF(left, top, max(left + 2f, right), max(top + 2f, bottom))
    }

    private fun viewport(dim: RectF): RectF {
        val pad = 21f * density
        val w = (width - 2f * pad).coerceAtLeast(1f)
        val h = (height - 2f * pad).coerceAtLeast(1f)
        val factor = min(w / dim.width(), h / dim.height())
        val actualW = dim.width() * factor
        val actualH = dim.height() * factor
        return RectF((width - actualW) / 2f, (height - actualH) / 2f,
            (width + actualW) / 2f, (height + actualH) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(11, 22, 34))
        val scan = store ?: return
        val bounds = scan.bounds()
        paint.textSize = 11f * density
        paint.style = Paint.Style.FILL
        if (bounds == null) {
            paint.color = Color.rgb(185, 215, 233)
            canvas.drawText("Noch keine optischen Tiefenpunkte",
                15f * density, height / 2f, paint)
            return
        }
        val points = scan.points()
        if (topDown) {
            val dim = layout(scan, bounds)
            val v = viewport(dim)
            fun sx(x: Float) = v.left + (x - dim.left) / dim.width() * v.width()
            fun sy(z: Float) = v.top + (z - dim.top) / dim.height() * v.height()
            paint.color = Color.rgb(22, 41, 60)
            canvas.drawRect(v, paint)
            for (p in points) {
                val heightNorm = if (bounds.height > .1f)
                    ((p.y - bounds.minY) / bounds.height).coerceIn(0f, 1f) else 0f
                paint.color = falseColor(heightNorm, p.confidence)
                canvas.drawCircle(sx(p.x + scan.originX), sy(p.z + scan.originZ),
                    1.6f * density, paint)
            }
            val trajectory = scan.trajectory()
            if (trajectory.size >= 2) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * density
                paint.color = Color.rgb(112, 233, 221)
                val path = Path()
                trajectory.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(sx(p.x + scan.originX), sy(p.z + scan.originZ))
                    else path.lineTo(sx(p.x + scan.originX), sy(p.z + scan.originZ))
                }
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
            rooms.forEach { room ->
                val r = RectF(sx(room.x), sy(room.z),
                    sx(room.x + room.width), sy(room.z + room.depth))
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(48, 93, 190, 234)
                canvas.drawRect(r, paint)
                paint.color = Color.rgb(119, 222, 245)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.4f * density
                canvas.drawRect(r, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawText(room.name.take(22), r.left + 5f * density,
                    r.top + 15f * density, paint)
            }
            corner1?.let { corner ->
                paint.color = Color.rgb(255, 190, 89)
                canvas.drawCircle(sx(corner.first), sy(corner.second), 7f * density, paint)
            }
            paint.color = Color.rgb(186, 206, 225)
            canvas.drawText(String.format(java.util.Locale.GERMAN,
                "Draufsicht · Ausschnitt %.1f × %.1f m",
                dim.width(), dim.height()), 8f * density, height - 9f * density, paint)
            return
        }
        val cx = (bounds.minX + bounds.maxX) * .5f
        val cy = (bounds.minY + bounds.maxY) * .5f
        val cz = (bounds.minZ + bounds.maxZ) * .5f
        val spread = max(2f, max(bounds.width, max(bounds.depth, bounds.height)))
        val scale = min(width, height) / (spread * 1.85f) * zoom
        val co = cos(yaw)
        val si = sin(yaw)
        val ce = cos(elevation)
        val se = sin(elevation)
        fun project(x: Float, y: Float, z: Float): Pair<Float, Float> {
            val dx = x - cx; val dy = y - cy; val dz = z - cz
            val rx = dx * co - dz * si
            val rz = dx * si + dz * co
            return Pair(width / 2f + rx * scale,
                height / 2f + (rz * se - dy * ce) * scale)
        }
        // Reference axes and a ground grid help read the floating 3D data.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 1f
        paint.color = Color.rgb(50, 76, 98)
        for (i in -4..4) {
            val offset = i * spread / 8f
            val a = project(cx - spread / 2f, bounds.minY, cz + offset)
            val b = project(cx + spread / 2f, bounds.minY, cz + offset)
            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
            val c = project(cx + offset, bounds.minY, cz - spread / 2f)
            val d = project(cx + offset, bounds.minY, cz + spread / 2f)
            canvas.drawLine(c.first, c.second, d.first, d.second, paint)
        }
        paint.style = Paint.Style.FILL
        for (point in points) {
            val norm = if (bounds.height > .1f)
                ((point.y - bounds.minY) / bounds.height).coerceIn(0f, 1f) else 0f
            paint.color = falseColor(norm, point.confidence)
            val p = project(point.x, point.y, point.z)
            canvas.drawCircle(p.first, p.second, density * 1.5f, paint)
        }
        val trajectory = scan.trajectory()
        if (trajectory.size > 1) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(103, 237, 225)
            paint.strokeWidth = density * 2f
            val path = Path()
            trajectory.forEachIndexed { i, p ->
                val projected = project(p.x, p.y, p.z)
                if (i == 0) path.moveTo(projected.first, projected.second)
                else path.lineTo(projected.first, projected.second)
            }
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(184, 211, 228)
        canvas.drawText("3D · Farbe = Höhe · Türkis = Kamerapfad",
            9f * density, height - 10f * density, paint)
    }

    private fun falseColor(height: Float, confidence: Float): Int {
        val hue = 218f - height.coerceIn(0f, 1f) * 200f
        val rgb = Color.HSVToColor(floatArrayOf(hue, .8f, .95f))
        val alpha = (90 + confidence.coerceIn(0f, 1f) * 165).toInt()
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (kotlin.math.abs(dx) > density || kotlin.math.abs(dy) > density) moved = true
                if (!topDown && event.pointerCount == 1 && !scaler.isInProgress) {
                    yaw += dx / max(1f, width.toFloat()) * 4.2f
                    elevation = (elevation + dy / max(1f, height.toFloat()) * 2.0f)
                        .coerceIn(.1f, 1.52f)
                    invalidate()
                }
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!topDown || !selectCorners || moved) return true
                val scan = store ?: return true
                val bounds = scan.bounds() ?: return true
                val dim = layout(scan, bounds)
                val v = viewport(dim)
                if (!v.contains(event.x, event.y)) return true
                val px = dim.left + (event.x - v.left) / v.width() * dim.width()
                val pz = dim.top + (event.y - v.top) / v.height() * dim.height()
                onRoomCorner?.invoke(px, pz)
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
