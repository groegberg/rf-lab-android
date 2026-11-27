package dev.rflab.scanner

import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Samples true Raw Depth and interpolated Full Depth, aligned to CPU camera RGB. */
internal class RgbdSampler {
    data class Result(val points: List<SurveyDepth>, val rawCount: Int,
                      val fullCount: Int, val coloredCount: Int,
                      val rawTimestamp: Long, val fullTimestamp: Long,
                      val keyframe: RgbdKeyframeData? = null)

    private data class Sample(
        val x: Float, val y: Float, val z: Float, val confidence: Float,
        val raw: Boolean, val u: Float, val v: Float, var argb: Int = 0
    )

    private var lastRawTimestamp = Long.MIN_VALUE
    private var lastFullTimestamp = Long.MIN_VALUE
    fun reset() {
        lastRawTimestamp = Long.MIN_VALUE
        lastFullTimestamp = Long.MIN_VALUE
    }

    fun capture(frame: Frame, camera: Camera, saveKeyframe: Boolean = false): Result? {
        val raw = acquire { frame.acquireRawDepthImage16Bits() }
        val conf = if (raw != null) acquire { frame.acquireRawDepthConfidenceImage() } else null
        val full = acquire { frame.acquireDepthImage16Bits() }
        try {
            val useRaw = raw != null && conf != null && raw.timestamp != lastRawTimestamp
            val useFull = full != null && full.timestamp != lastFullTimestamp
            // RGB-only keyframes are valid: DA3 and photo stereo require no ARCore depth.
            if (!useRaw && !useFull && !saveKeyframe) return null
            val intr = camera.textureIntrinsics
            val dims = intr.imageDimensions
            val focal = intr.focalLength
            val center = intr.principalPoint
            if (dims[0] <= 0 || dims[1] <= 0 || focal[0] <= 0f || focal[1] <= 0f) return null
            val matrix = FloatArray(16)
            camera.pose.toMatrix(matrix, 0)
            val samples = ArrayList<Sample>(4500)
            val rawCount = if (useRaw) sample(raw!!, conf, true, 3200,
                dims, focal, center, matrix, samples) else 0
            val fullCount = if (useFull) sample(full!!, null, false, 1000,
                dims, focal, center, matrix, samples) else 0
            if (useRaw) lastRawTimestamp = raw!!.timestamp
            if (useFull) lastFullTimestamp = full!!.timestamp
            var keyframe: RgbdKeyframeData? = null
            if (samples.isNotEmpty() || saveKeyframe) {
                // Acquire and release on ARCore GL thread: never hand an Image
                // to the asynchronous writer, copy only immutable bytes.
                acquire { frame.acquireCameraImage() }?.use { cpu ->
                    colorize(frame, cpu, samples)
                    if (saveKeyframe && cpu.width % 2 == 0 && cpu.height % 2 == 0) {
                        val imageNv21 = copyNv21(cpu)
                        val depthImage = full ?: raw
                        if (imageNv21 != null) {
                            // If ARCore depth is unavailable, retain RGB, pose,
                            // intrinsics and completely ZERO depth arrays.
                            // These images are usable by AI + photographic stereo.
                            val w = depthImage?.width ?: 640
                            val h = depthImage?.height ?: ((640f * dims[1] /
                                dims[0]).toInt().coerceIn(180, 640) / 2 * 2)
                            val dFx = focal[0] * w / dims[0]
                            val dFy = focal[1] * h / dims[1]
                            val dCx = center[0] * w / dims[0]
                            val dCy = center[1] * h / dims[1]
                            val imageUvCorners = textureToImageCorners(frame)
                            val pose = FloatArray(16)
                            camera.pose.toMatrix(pose, 0)
                            keyframe = RgbdKeyframeData(
                                frame.timestamp, pose, w, h, dFx, dFy, dCx, dCy,
                                imageUvCorners, cpu.width, cpu.height, imageNv21,
                                if (depthImage != null) packDepth(depthImage)
                                else ByteArray(w * h * 2),
                                if (raw != null && raw.width == w && raw.height == h)
                                    packDepth(raw) else ByteArray(w * h * 2),
                                if (conf != null && conf.width == w && conf.height == h)
                                    packConfidence(conf) else ByteArray(w * h))
                        }
                    }
                }
            }
            return Result(samples.map {
                SurveyDepth(it.x, it.y, it.z, it.confidence,
                    argb = it.argb, rawDepth = it.raw)
            }, rawCount, fullCount, samples.count { it.argb != 0 },
                raw?.timestamp ?: -1L, full?.timestamp ?: -1L, keyframe)
        } finally {
            raw?.close()
            conf?.close()
            full?.close()
        }
    }

    private fun acquire(block: () -> Image): Image? =
        try { block() } catch (_: NotYetAvailableException) { null }
        catch (_: IllegalStateException) { null }

    private fun sample(image: Image, confidence: Image?, raw: Boolean, target: Int,
                       dimensions: IntArray, focal: FloatArray, center: FloatArray,
                       m: FloatArray, out: MutableList<Sample>): Int {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return 0
        val plane = image.planes[0]
        val depths = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val qualityPlane = confidence?.planes?.getOrNull(0)
        val qualities = qualityPlane?.buffer?.duplicate()
        val step = ceil(sqrt(width.toDouble() * height / target)).toInt().coerceAtLeast(1)
        val fx = focal[0] * width / dimensions[0]
        val fy = focal[1] * height / dimensions[1]
        val cx = center[0] * width / dimensions[0]
        val cy = center[1] * height / dimensions[1]
        var accepted = 0
        for (y in step / 2 until height step step) {
            for (x in step / 2 until width step step) {
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index < 0 || index + 1 >= depths.limit()) continue
                val mm = depths.getShort(index).toInt() and 0xffff
                if (mm !in 350..7500) continue
                val q = if (raw) {
                    if (qualityPlane == null || qualities == null) continue
                    val qi = y * qualityPlane.rowStride + x * qualityPlane.pixelStride
                    if (qi < 0 || qi >= qualities.limit()) continue
                    (qualities.get(qi).toInt() and 255) / 255f
                } else 0.26f // Interpolated depth: deliberately low weight.
                if (raw && q < 0.48f) continue
                val z = mm / 1000f
                val px = (x - cx) * z / fx
                val py = (cy - y) * z / fy
                val pz = -z
                val wx = m[0] * px + m[4] * py + m[8] * pz + m[12]
                val wy = m[1] * px + m[5] * py + m[9] * pz + m[13]
                val wz = m[2] * px + m[6] * py + m[10] * pz + m[14]
                if (wx.isFinite() && wy.isFinite() && wz.isFinite()) {
                    out.add(Sample(wx, wy, wz, q, raw,
                        (x + .5f) / width, (y + .5f) / height))
                    accepted++
                }
            }
        }
        return accepted
    }

    private fun colorize(frame: Frame, cpu: Image, samples: List<Sample>) {
        if (cpu.planes.size < 3 || samples.isEmpty()) return
        val input = ByteBuffer.allocateDirect(samples.size * 8)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val output = ByteBuffer.allocateDirect(samples.size * 8)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        samples.forEach { input.put(it.u).put(it.v) }
        input.rewind()
        frame.transformCoordinates2d(
            Coordinates2d.TEXTURE_NORMALIZED, input,
            Coordinates2d.IMAGE_PIXELS, output)
        output.rewind()
        samples.forEach { point ->
            val x = output.get().roundToInt()
            val y = output.get().roundToInt()
            if (x in 0 until cpu.width && y in 0 until cpu.height)
                point.argb = yuvPixel(cpu, x, y)
        }
    }

    private fun textureToImageCorners(frame: Frame): FloatArray {
        val input = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val output = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (v in floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)) input.put(v)
        input.rewind()
        frame.transformCoordinates2d(
            Coordinates2d.TEXTURE_NORMALIZED, input,
            Coordinates2d.IMAGE_NORMALIZED, output)
        output.rewind()
        return FloatArray(8) { output.get() }
    }

    private fun packDepth(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val dst = ByteArray(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val src = y * plane.rowStride + x * plane.pixelStride
            if (src < 0 || src + 1 >= buffer.limit()) continue
            val mm = buffer.getShort(src).toInt() and 0xffff
            val i = (y * w + x) * 2
            dst[i] = (mm and 255).toByte()
            dst[i + 1] = (mm ushr 8).toByte()
        }
        return dst
    }

    private fun packConfidence(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val dst = ByteArray(image.width * image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val src = y * plane.rowStride + x * plane.pixelStride
            if (src >= 0 && src < buffer.limit())
                dst[y * image.width + x] = buffer.get(src)
        }
        return dst
    }

    /** Respect YUV_420_888 plane row/pixel strides, output a packed NV21 copy. */
    private fun copyNv21(image: Image): ByteArray? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0 ||
            image.planes.size < 3) return null
        val result = ByteArray(w * h * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        fun pixel(plane: Image.Plane, x: Int, y: Int): Byte {
            val i = y * plane.rowStride + x * plane.pixelStride
            return if (i >= 0 && i < plane.buffer.limit()) plane.buffer.get(i)
                else 0.toByte()
        }
        for (y in 0 until h) for (x in 0 until w)
            result[y * w + x] = pixel(yPlane, x, y)
        var i = w * h
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            result[i++] = pixel(vPlane, x, y)
            result[i++] = pixel(uPlane, x, y)
        }
        return result
    }

    private fun yuvPixel(image: Image, x: Int, y: Int): Int {
        fun component(plane: Image.Plane, px: Int, py: Int): Int? {
            val i = py * plane.rowStride + px * plane.pixelStride
            if (i < 0 || i >= plane.buffer.limit()) return null
            return plane.buffer.get(i).toInt() and 255
        }
        val yy = component(image.planes[0], x, y) ?: return 0
        val u = component(image.planes[1], x / 2, y / 2) ?: return 0
        val v = component(image.planes[2], x / 2, y / 2) ?: return 0
        val c = (yy - 16).coerceAtLeast(0)
        val d = u - 128
        val e = v - 128
        val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
}
