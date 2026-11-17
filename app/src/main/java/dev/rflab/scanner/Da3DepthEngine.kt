package dev.rflab.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Real on-device Depth Anything 3 Small monocular inference (LiteRT 2.3).
 * Input NCHW [1,3,896,504] float32 ImageNet normalized; output one float
 * relative-depth map. NO network, ARCore depth, or fake/synthetic depth.
 * Camera JPEG is rotated to portrait if needed and center-cropped to the
 * model aspect; comparison UI labels the crop explicitly.
 *
 * Model: litert-community/Depth-Anything-3-Small-LiteRT, Apache-2.0.
 * Source model: ByteDance-Seed Depth-Anything-3.
 */
internal class Da3DepthEngine(private val context: Context) : AutoCloseable {
    companion object {
        const val WIDTH = 504
        const val HEIGHT = 896
        const val MODEL = "da3_small_gpu_fp16.tflite"
        private const val N = WIDTH * HEIGHT
        private val MEAN = floatArrayOf(.485f, .456f, .406f)
        private val STD = floatArrayOf(.229f, .224f, .225f)
    }

    private var model: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()
    var backend = "GPU"
        private set

    private val input = FloatArray(N * 3)
    private val pixels = IntArray(N)

    private fun initialize() {
        if (model != null) return
        var failure: Throwable? = null
        for ((accelerator, name) in listOf(
            Accelerator.GPU to "GPU", Accelerator.CPU to "CPU"
        )) {
            try {
                val compiled = CompiledModel.create(
                    context.assets, MODEL, CompiledModel.Options(accelerator), null)
                try {
                    val ins = compiled.createInputBuffers()
                    val outs = compiled.createOutputBuffers()
                    model = compiled
                    inputBuffers = ins
                    outputBuffers = outs
                    backend = name
                    return
                } catch (e: Throwable) {
                    compiled.close()
                    throw e
                }
            } catch (e: Throwable) {
                failure = e
            }
        }
        throw IllegalStateException(
            "DA3 could not compile on Pixel GPU or CPU: " + failure?.message, failure)
    }

    data class Prediction(
        val image: Bitmap,
        val depthVisual: Bitmap,
        val relativeDepth: FloatArray,
        val inferenceMs: Long,
        val accelerator: String,
        val validFraction: Float,
        val cropPercent: Float,
        val rotationDegrees: Int
    )

    fun predict(frame: StoredRgbdKeyframe): Prediction = predictJpeg(frame.imageJpeg)

    /** Direct Camera2 photo inference: no ARCore pose/depth dependency. */
    fun predictJpeg(jpeg: ByteArray,
                    uprightRotationDegrees: Int? = null): Prediction {
        initialize()
        val start = System.nanoTime()
        val jpeg = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size) ?: error("Invalid camera JPEG")
        // Raw ImageReader pixels stay in the CAMERA SENSOR frame even when
        // Android locks the activity landscape. A gravity-based rotation can
        // now override the historical forced +90 degree model rotation.
        val rotation = uprightRotationDegrees ?:
            if(jpeg.width>jpeg.height)90 else 0
        require(rotation in listOf(0,90,180,270)){
            "DA3 rotation must be an exact right angle"
        }
        val rotated = if(rotation!=0){
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(jpeg, 0, 0, jpeg.width, jpeg.height, m, true)
                .also { jpeg.recycle() }
        } else jpeg
        val scale = max(WIDTH / rotated.width.toFloat(),
            HEIGHT / rotated.height.toFloat())
        val drawnWidth = rotated.width * scale
        val drawnHeight = rotated.height * scale
        val crop = 1f - WIDTH.toFloat() * HEIGHT / (drawnWidth * drawnHeight)
        val inputBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(inputBitmap).drawBitmap(rotated, null,
            RectF((WIDTH - drawnWidth) * .5f, (HEIGHT - drawnHeight) * .5f,
                (WIDTH + drawnWidth) * .5f, (HEIGHT + drawnHeight) * .5f),
            Paint(Paint.FILTER_BITMAP_FLAG))
        rotated.recycle()
        inputBitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        for (i in pixels.indices) {
            val c = pixels[i]
            input[i] = (((c ushr 16) and 255) / 255f - MEAN[0]) / STD[0]
            input[N + i] = (((c ushr 8) and 255) / 255f - MEAN[1]) / STD[1]
            input[2 * N + i] = ((c and 255) / 255f - MEAN[2]) / STD[2]
        }
        inputBuffers[0].writeFloat(input)
        model!!.run(inputBuffers, outputBuffers)
        val depth = outputBuffers[0].readFloat()
        check(depth.size == N) { "Unexpected DA3 output: " + depth.size }
        val visual = RelativeDepthVisual.render(depth, WIDTH, HEIGHT)
        val good = depth.count { it.isFinite() && it > 0f }
        return Prediction(inputBitmap, visual, depth,
            (System.nanoTime() - start) / 1_000_000, backend,
            good.toFloat() / N, crop.coerceIn(0f, 1f),
            rotation)
    }

    override fun close() {
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers = emptyList()
        inputBuffers = emptyList()
        model?.close()
        model = null
    }
}

/** Heatmaps are illustrative relative depth, NOT distance in meters. */
internal object RelativeDepthVisual {
    fun render(depth: FloatArray, width: Int, height: Int): Bitmap {
        require(depth.size == width * height)
        val positive = depth.asSequence()
            .filter { it.isFinite() && it > 0f }
            .map { 1f / it }.toList().sorted()
        val output = IntArray(depth.size) { Color.rgb(32, 39, 54) }
        if (positive.size > 20) {
            val lo = positive[(positive.size * .02).toInt()]
            val hi = positive[(positive.size * .98).toInt()
                .coerceAtMost(positive.size - 1)]
            val range = max(1e-6f, hi - lo)
            for (i in depth.indices) {
                val d = depth[i]
                if (!d.isFinite() || d <= 0f) continue
                val t = ((1f / d - lo) / range).coerceIn(0f, 1f)
                // Near = warm, far = cool; contrast-preserving percentile scale.
                val hue = (235f - t * 230f).coerceIn(0f, 260f)
                output[i] = Color.HSVToColor(floatArrayOf(hue, .82f, .96f))
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }
}
