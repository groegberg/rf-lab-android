package dev.rflab.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Schematic 2D home plan in manually assigned metric coordinates. */
class SpatialFloorplanView(context: Context) : View(context) {
    var home: SpatialHomeStore? = null
    var activeRoomId: String? = null
    var activeAnchorId: String? = null
    var estimate: RoomEstimate? = null
    var locationHint: SpatialHint? = null
    var onTap: ((Float, Float) -> Unit)? = null
    var showPoints = true
    var showDevices = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    private fun bounds(rooms: List<HomeRoom>): RectF {
        val maxX = max(6f, rooms.maxOfOrNull { it.x + it.width + 1f } ?: 6f)
        val maxZ = max(5f, rooms.maxOfOrNull { it.z + it.depth + 1f } ?: 5f)
        return RectF(0f, 0f, maxX, maxZ)
    }

    private fun viewport(extent: RectF): RectF {
        val padding = 20f * density
        val usableW = (width - padding * 2).coerceAtLeast(1f)
        val usableH = (height - padding * 2).coerceAtLeast(1f)
        val factor = min(usableW / extent.width(), usableH / extent.height())
        val w = extent.width() * factor
        val h = extent.height() * factor
        return RectF((width - w) / 2f, (height - h) / 2f,
            (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(13, 25, 40))
        val model = home ?: return
        val rooms = model.rooms
        val extent = bounds(rooms)
        val vp = viewport(extent)
        fun px(x: Float) = vp.left + (x - extent.left) / extent.width() * vp.width()
        fun py(z: Float) = vp.top + (z - extent.top) / extent.height() * vp.height()
        paint.strokeWidth = density * .8f
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(38, 61, 82)
        for (i in 0..extent.width().toInt()) {
            val x = px(i.toFloat())
            canvas.drawLine(x, vp.top, x, vp.bottom, paint)
        }
        for (i in 0..extent.height().toInt()) {
            val y = py(i.toFloat())
            canvas.drawLine(vp.left, y, vp.right, y, paint)
        }
        rooms.forEach { r ->
            val rect = RectF(px(r.x), py(r.z), px(r.x + r.width), py(r.z + r.depth))
            paint.style = Paint.Style.FILL
            paint.color = when {
                r.id == estimate?.roomId && estimate?.quality == "OK" -> Color.rgb(33, 83, 84)
                r.id == activeRoomId -> Color.rgb(42, 71, 99)
                else -> Color.rgb(29, 46, 65)
            }
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.STROKE
            paint.color = if (r.id == activeRoomId) Color.rgb(100, 207, 235)
                else Color.rgb(129, 157, 185)
            paint.strokeWidth = if (r.id == activeRoomId) density * 2.5f else density * 1.6f
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = density * 11f
            canvas.drawText(r.name.take(21), rect.left + 7f * density,
                rect.top + 16f * density, paint)
            val area = String.format(java.util.Locale.GERMAN,
                "%.1f x %.1f m", r.width, r.depth)
            paint.textSize = density * 9f
            paint.color = Color.rgb(159, 189, 213)
            canvas.drawText(area, rect.left + 7f * density,
                rect.top + 30f * density, paint)
        }
        if (showPoints) model.anchors.forEach { a ->
            val x = px(a.x)
            val y = py(a.z)
            paint.style = Paint.Style.FILL
            paint.color = if (a.id == activeAnchorId) Color.rgb(252, 187, 85)
                else if (a.sweeps.size >= 2) Color.rgb(97, 228, 162)
                else Color.rgb(181, 189, 201)
            canvas.drawCircle(x, y, density * 5.5f, paint)
            if (a.id == activeAnchorId) {
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * density
                canvas.drawCircle(x, y, density * 10f, paint)
                paint.style = Paint.Style.FILL
            }
            paint.textSize = density * 9f
            paint.color = Color.WHITE
            canvas.drawText(a.label, x + 7f * density, y - 7f * density, paint)
        }
        if (showDevices) model.devices.forEach { device ->
            val x = px(device.x)
            val y = py(device.z)
            paint.style = Paint.Style.FILL
            paint.color = if (device.type == "Lampe") Color.rgb(250, 208, 120)
                else Color.rgb(153, 185, 250)
            canvas.drawCircle(x, y, density * 7.5f, paint)
            paint.color = Color.rgb(16, 29, 40)
            canvas.drawCircle(x, y, density * 3.5f, paint)
            paint.color = Color.WHITE
            paint.textSize = density * 9.5f
            canvas.drawText(device.name.take(16), x + 10f * density, y + 4f * density, paint)
        }
        val matchedAnchor = model.anchors.firstOrNull {
            it.id == estimate?.bestAnchorId && estimate?.quality == "OK"
        }
        if (matchedAnchor != null) {
            paint.color = Color.argb(45, 105, 222, 244)
            canvas.drawCircle(px(matchedAnchor.x), py(matchedAnchor.z), density * 18f, paint)
            paint.color = Color.rgb(121, 228, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            canvas.drawCircle(px(matchedAnchor.x), py(matchedAnchor.z), density * 18f, paint)
            paint.style = Paint.Style.FILL
        }
        locationHint?.let { h ->
            val pxScale = vp.width() / extent.width()
            val pyScale = vp.height() / extent.height()
            val radius = h.uncertaintyM * min(pxScale, pyScale)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(28, 81, 225, 232)
            canvas.drawCircle(px(h.x), py(h.z), radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density * 1.5f
            paint.color = Color.argb(175, 81, 225, 232)
            canvas.drawCircle(px(h.x), py(h.z), radius, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(px(h.x), py(h.z), density * 4.5f, paint)
        }
        if (rooms.isEmpty()) {
            paint.style = Paint.Style.FILL
            paint.textSize = density * 14f
            paint.color = Color.rgb(199, 224, 242)
            canvas.drawText("Noch keine Zimmer angelegt",
                vp.left + 12f * density, vp.centerY(), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(147, 179, 201)
        paint.textSize = density * 10f
        canvas.drawText("Schematischer Grundriss · manuelle Meterkoordinaten",
            9f * density, height - 9f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val model = home ?: return true
        val extent = bounds(model.rooms)
        val vp = viewport(extent)
        if (!vp.contains(event.x, event.y)) return true
        val x = extent.left + (event.x - vp.left) / vp.width() * extent.width()
        val z = extent.top + (event.y - vp.top) / vp.height() * extent.height()
        onTap?.invoke(x, z)
        return true
    }
}
