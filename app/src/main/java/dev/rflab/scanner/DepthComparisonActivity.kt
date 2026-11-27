package dev.rflab.scanner

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One fixed RGB keyframe across all five pipelines. RAW / FULL / RGB Stereo
 * are metric depth; DA3 is RELATIVE depth; multi-view is a quality mask.
 * All inference runs after capture, on this phone only.
 */
class DepthComparisonActivity : Activity() {
    private enum class Method(val title: String) {
        RAW("ARCore Raw"), FULL("ARCore Full"), STEREO("Foto-Stereo"),
        DA3("Depth Anything 3"), CROSS("Mehrbild-Check")
    }

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var request = 0
    private var ai: Da3DepthEngine? = null
    private var method = Method.RAW
    private lateinit var files: List<File>
    private var index = 0
    private lateinit var source: ImageView
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private lateinit var prev: Button
    private lateinit var next: Button
    private lateinit var choices: Map<Method, Button>
    private val bg = Color.rgb(9, 18, 30)

    private fun dp(px: Int) = (px * resources.displayMetrics.density + .5f).toInt()
    private fun shape(selected: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (selected) Color.rgb(15, 119, 101) else Color.rgb(31, 54, 71))
            cornerRadius = dp(12).toFloat()
        }
    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = shape()
            setOnClickListener { action() }
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val store = RgbdKeyframeArchive(this)
        files = store.files()
        store.shutdown()
        index = (files.size - 1).coerceAtLeast(0) / 2
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        setContentView(root)
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        title.addView(button("‹") { finish() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        heading = TextView(this).apply {
            text = "3D-VERGLEICH"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        title.addView(heading, LinearLayout.LayoutParams(0, dp(48), 1f))
        prev = button("‹") { if (index > 0) { index--; refresh() } }
        next = button("›") { if (index < files.lastIndex) { index++; refresh() } }
        title.addView(prev, LinearLayout.LayoutParams(dp(44), dp(46)))
        title.addView(next, LinearLayout.LayoutParams(dp(44), dp(46)))
        root.addView(title)

        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val viewport = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        fun panel(label: String): ImageView {
            val frame = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(13, 24, 36))
            }
            frame.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.rgb(179, 214, 225))
                setPadding(dp(13), dp(6), dp(8), dp(5))
            })
            val view = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(15, 26, 38))
            }
            frame.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            viewport.addView(frame, if (landscape) LinearLayout.LayoutParams(0, -1, 1f)
                else LinearLayout.LayoutParams(-1, 0, 1f))
            return view
        }
        source = panel("ORIGINALKAMERA")
        image = panel("TIEFE / QUALITÄT")

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        choices = Method.values().associateWith { m ->
            button(m.title) { method = m; refresh() }.also {
                row.addView(it, LinearLayout.LayoutParams(-2, dp(44)).apply {
                    rightMargin = dp(6)
                })
            }
        }
        scroll.addView(row)
        root.addView(scroll)
        status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(199, 231, 235))
            setPadding(dp(12), dp(4), dp(12), dp(12))
            maxLines = 3
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(68)))
        refresh()
    }

    private fun refresh() {
        val serial = ++request
        heading.text = "3D-VERGLEICH  " + (index + 1) + "/" + files.size
        prev.isEnabled = index > 0
        next.isEnabled = index < files.lastIndex
        for ((m, btn) in choices) btn.background = shape(m == method)
        if (files.isEmpty()) {
            status.text = "Noch kein RGB-D-Scan. Bitte zuerst einen Raum aufnehmen."
            return
        }
        val current = method
        val f = files[index]
        val contextFiles = files.subList(max(0, index - 5),
            min(files.size, index + 6)).filter { it != f }
        status.text = current.title + " · Berechnung auf dem Smartphone …"
        worker.execute {
            try {
                val frame = RgbdKeyframeArchive.read(f)
                    ?: error("Kamerabild kann nicht gelesen werden")
                val original = BitmapFactory.decodeByteArray(frame.imageJpeg, 0,
                    frame.imageJpeg.size) ?: error("Foto fehlt")
                val others = if (current == Method.STEREO ||
                    current == Method.CROSS) contextFiles.mapNotNull {
                    RgbdKeyframeArchive.read(it)
                } else emptyList()
                var inputImage = original
                val resultImage: Bitmap
                val text: String
                when (current) {
                    Method.DA3 -> {
                        val engine = ai ?: Da3DepthEngine(applicationContext).also { ai = it }
                        val prediction = engine.predict(frame)
                        inputImage = prediction.image
                        original.recycle()
                        resultImage = prediction.depthVisual
                        text = "Depth Anything 3 Small · " + prediction.accelerator +
                            " · " + prediction.inferenceMs + " ms" +
                            "\nRELATIVE Tiefe (keine Meter), Zuschnitt " +
                            (prediction.cropPercent * 100).toInt() + "%"
                    }
                    Method.STEREO -> {
                        val stereo = PhotoStereoEstimator().estimate(frame, others)
                        resultImage = DepthRasterVisual.metric(frame, stereo.meters,
                            stereo.width, stereo.height)
                        text = "Foto-Stereo (RGB + Kameraposen) · " +
                            stereo.validPixels + " Pixel · Basis " +
                            String.format(java.util.Locale.GERMAN, "%.2f m",
                                stereo.baselineM) + " · " + stereo.elapsedMs + " ms" +
                            "\n" + stereo.status
                    }
                    Method.CROSS -> {
                        val consistency = DepthRasterVisual.consistency(frame, others)
                        resultImage = consistency.first
                        text = "Mehrbild-Test · " + consistency.second + " bestätigt / " +
                            consistency.third + " widersprüchlich" +
                            "\nGrün = bestätigt, Rot = Konflikt, Grau = unbestimmt"
                    }
                    else -> {
                        val raw = current == Method.RAW
                        val map = FloatArray(frame.width * frame.height)
                        var valid = 0
                        for (i in map.indices) {
                            val mm = DepthRasterVisual.read16(
                                if (raw) frame.rawDepth else frame.fullDepth, i)
                            if (mm in 350..7500 && (!raw ||
                                ((frame.rawConfidence[i].toInt() and 255) >= 128))) {
                                map[i] = mm / 1000f
                                valid++
                            }
                        }
                        resultImage = DepthRasterVisual.metric(frame, map,
                            frame.width, frame.height)
                        text = current.title + " · " + valid + " / " + map.size +
                            " gültige Pixel · Meter (ARCore)"
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed || serial != request) {
                        inputImage.recycle()
                        resultImage.recycle()
                    } else {
                        source.setImageBitmap(inputImage)
                        image.setImageBitmap(resultImage)
                        status.text = text
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (!isFinishing && serial == request)
                        status.text = current.title + ": " +
                            t.javaClass.simpleName + " · " + (t.message ?: "Fehler")
                }
            }
        }
    }

    override fun onDestroy() {
        request++
        worker.execute { ai?.close() }
        worker.shutdown()
        super.onDestroy()
    }
}

/** Same displayed image coordinates for all ARCore/photostereo method outputs. */
internal object DepthRasterVisual {
    fun read16(bytes: ByteArray, index: Int): Int {
        val i = index * 2
        if (i < 0 || i + 1 >= bytes.size) return 0
        return (bytes[i].toInt() and 255) or
            ((bytes[i + 1].toInt() and 255) shl 8)
    }

    private fun aligned(frame: StoredRgbdKeyframe, w: Int, h: Int,
                        colors: IntArray): Bitmap {
        val dstW = 320
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.imageJpeg, 0,
            frame.imageJpeg.size, bounds)
        val imageW = bounds.outWidth.takeIf { it > 0 } ?: w
        val imageH = bounds.outHeight.takeIf { it > 0 } ?: h
        val dstH = (dstW * imageH / imageW).coerceAtLeast(1)
        val c = frame.uvImageCorners
        val ax = c[2] - c[0]; val ay = c[3] - c[1]
        val bx = c[4] - c[0]; val by = c[5] - c[1]
        val det = ax * by - ay * bx
        if (abs(det) < 1e-8f) return Bitmap.createBitmap(
            colors, w, h, Bitmap.Config.ARGB_8888)
        val out = IntArray(dstW * dstH) { Color.rgb(30, 40, 57) }
        for (y in 0 until dstH) for (x in 0 until dstW) {
            val dx = (x + .5f) / dstW - c[0]
            val dy = (y + .5f) / dstH - c[1]
            val u = (dx * by - dy * bx) / det
            val v = (ax * dy - ay * dx) / det
            if (u !in 0f..1f || v !in 0f..1f) continue
            val sx = (u * w).toInt().coerceIn(0, w - 1)
            val sy = (v * h).toInt().coerceIn(0, h - 1)
            out[y * dstW + x] = colors[sy * w + sx]
        }
        return Bitmap.createBitmap(out, dstW, dstH, Bitmap.Config.ARGB_8888)
    }

    fun metric(frame: StoredRgbdKeyframe, meters: FloatArray,
               w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h) { i ->
            val d = meters.getOrElse(i) { 0f }
            if (!d.isFinite() || d <= 0f) Color.rgb(30, 40, 57)
            else Color.HSVToColor(floatArrayOf(
                240f - 232f * ((6.5f - d) / 6f).coerceIn(0f, 1f), .85f, .95f))
        }
        return aligned(frame, w, h, pixels)
    }

    fun consistency(frame: StoredRgbdKeyframe,
                    others: List<StoredRgbdKeyframe>): Triple<Bitmap, Int, Int> {
        val w = (frame.width / 3).coerceIn(48, 240)
        val h = (frame.height / 3).coerceIn(36, 180)
        val colors = IntArray(w * h) { Color.rgb(48, 60, 73) }
        var accepted = 0
        var rejected = 0
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + .5f) / w
            val v = (y + .5f) / h
            val sx = (u * frame.width).toInt().coerceIn(0, frame.width - 1)
            val sy = (v * frame.height).toInt().coerceIn(0, frame.height - 1)
            val i = sy * frame.width + sx
            val raw = read16(frame.rawDepth, i)
            val reliable = raw in 350..7500 &&
                ((frame.rawConfidence[i].toInt() and 255) >= 128)
            val full = read16(frame.fullDepth, i)
            val mm = if (reliable) raw else full
            if (mm !in 350..7500) continue
            val xyz = StereoGeometry.point(frame, u, v, mm / 1000f)
            val evidence = MultiviewDepthConsistency.check(
                xyz[0], xyz[1], xyz[2], frame, others)
            colors[y * w + x] = when (evidence.verdict) {
                MultiviewDepthConsistency.Result.CONFIRMED -> {
                    accepted++; Color.rgb(37, 220, 156)
                }
                MultiviewDepthConsistency.Result.CONTRADICTED -> {
                    rejected++; Color.rgb(248, 85, 91)
                }
                else -> Color.rgb(104, 117, 137)
            }
        }
        return Triple(aligned(frame, w, h, colors), accepted, rejected)
    }
}
