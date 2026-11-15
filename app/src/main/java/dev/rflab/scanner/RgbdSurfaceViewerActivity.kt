package dev.rflab.scanner

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.view.Gravity
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fully offline, first camera-textured surface preview.
 * Frame-mesh patches can overlap; this is not TSDF or watertight geometry.
 */
class RgbdSurfaceViewerActivity : Activity() {
    private lateinit var surface: RgbdSurfaceView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 21, 34)
        window.navigationBarColor = Color.rgb(10, 21, 34)
        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(9, 17, 29))
        }
        setContentView(layout)
        surface = RgbdSurfaceView(this)
        layout.addView(surface, FrameLayout.LayoutParams(-1, -1))
        fun pill(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 30f
        }
        layout.addView(TextView(this).apply {
            text = "3D  ·  MODELL"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = pill(Color.argb(220, 12, 28, 41))
            setPadding(26, 15, 26, 15)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = 24; leftMargin = 24
        })
        layout.addView(Button(this).apply {
            isAllCaps = false
            text = "✕"
            textSize = 17f
            setTextColor(Color.WHITE)
            background = pill(Color.argb(225, 24, 53, 67))
            setOnClickListener { finish() }
            contentDescription = "3D-Modell schließen"
        }, FrameLayout.LayoutParams(120, 112, Gravity.TOP or Gravity.END).apply {
            topMargin = 16; rightMargin = 20
        })
        status = TextView(this).apply {
            text = "Prüfe Tiefen aus mehreren Blickwinkeln …"
            textSize = 12f
            setTextColor(Color.rgb(202, 239, 238))
            background = pill(Color.argb(225, 12, 28, 41))
            setPadding(24, 18, 24, 18)
            maxLines = 3
        }
        layout.addView(status, FrameLayout.LayoutParams(
            -1, -2, Gravity.BOTTOM).apply {
            bottomMargin = 24; leftMargin = 20; rightMargin = 20
        })

        val archive = RgbdKeyframeArchive(this)
        val files = archive.files()
        archive.shutdown()
        if (files.isEmpty()) {
            status.text = "Noch keine gespeicherten RGB-D-Keyframes. Zuerst einen Scan aufnehmen."
            return
        }

        Thread({
            val planes = StructuralPlaneFitter.detect(RoomScanStore(this).points())
            val stride = ceil(files.size.toDouble() / MAX_PREVIEW_FRAMES).toInt()
                .coerceAtLeast(1)
            val loadedFrames = files.indices.step(stride).take(MAX_PREVIEW_FRAMES)
                .mapNotNull { RgbdKeyframeArchive.read(files[it]) }
            var loaded = 0
            var triangles = 0
            var confirmed = 0
            var rejected = 0
            var planar = 0
            for (frame in loadedFrames) {
                if (isFinishing || isDestroyed) break
                val fx = frame.cameraWorld[12]
                val fy = frame.cameraWorld[13]
                val fz = frame.cameraWorld[14]
                val neighbors = loadedFrames.asSequence()
                    .filter { it !== frame }
                    .sortedBy {
                        val dx = it.cameraWorld[12] - fx
                        val dy = it.cameraWorld[13] - fy
                        val dz = it.cameraWorld[14] - fz
                        dx * dx + dy * dy + dz * dz
                    }.take(6).toList()
                val mesh = RgbdSurfaceMesher.build(frame,
                    neighbors = neighbors, planes = planes)
                if (mesh.triangleCount < 2) continue
                val bitmap = decodeScaled(frame.imageJpeg) ?: continue
                triangles += mesh.triangleCount
                confirmed += mesh.multiviewConfirmed
                rejected += mesh.multiviewRejected
                planar += mesh.planeRegularized
                surface.enqueue(mesh, bitmap)
                loaded++
                val message = "3D-Mesh · $loaded/${loadedFrames.size} Bilder" +
                    " · $triangles Dreiecke" +
                    "\nMehrbild: $confirmed bestätigt · $rejected verworfen" +
                    "\nEbenen: ${planes.size} erkannt · $planar korrigiert"
                runOnUiThread { if (!isFinishing) status.text = message }
            }
            if (loaded == 0) runOnUiThread {
                if (!isFinishing) status.text =
                    "Noch keine zuverlässigen Tiefenflächen. Weitere Blickwinkel aufnehmen."
            }
        }, "RgbdMeshLoader").start()
    }

    private fun decodeScaled(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var factor = 1
        while (max(bounds.outWidth, bounds.outHeight) / factor > 900) factor *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = factor })
    }

    override fun onResume() {
        super.onResume()
        if (::surface.isInitialized) surface.onResume()
    }

    override fun onPause() {
        if (::surface.isInitialized) surface.onPause()
        super.onPause()
    }

    companion object {
        private const val MAX_PREVIEW_FRAMES = 36
    }
}

internal class RgbdSurfaceView(context: android.content.Context) : GLSurfaceView(context) {
    private val scene = SurfaceRenderer()
    private var lastX = 0f
    private var lastY = 0f
    private val zoomDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                queueEvent { scene.zoom(scale) }
                requestRender()
                return true
            }
        })

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(scene)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun enqueue(mesh: RgbdSurfaceMesh, bitmap: Bitmap) {
        queueEvent {
            scene.add(mesh, bitmap)
            requestRender()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        zoomDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !zoomDetector.isInProgress) {
                    val dx = (event.x - lastX) / width.coerceAtLeast(1)
                    val dy = (event.y - lastY) / height.coerceAtLeast(1)
                    queueEvent { scene.orbit(dx, dy) }
                    requestRender()
                }
                lastX = event.x
                lastY = event.y
                return true
            }
        }
        return true
    }
}

private class SurfaceRenderer : GLSurfaceView.Renderer {
    private data class GpuPatch(
        val position: FloatBuffer, val uv: FloatBuffer,
        val indices: ShortBuffer, val indexCount: Int, val texture: Int
    )

    private val patches = mutableListOf<GpuPatch>()
    private val lower = floatArrayOf(Float.POSITIVE_INFINITY,
        Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    private val upper = floatArrayOf(Float.NEGATIVE_INFINITY,
        Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    private var program = 0
    private var aPosition = -1
    private var aUv = -1
    private var uMvp = -1
    private var uTexture = -1
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var yaw = .62f
    private var pitch = .38f
    private var zoomFactor = 1.0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(.045f, .085f, .13f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        val vertex = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec3 aPosition;
            attribute vec2 aUv;
            uniform mat4 uMvp;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vUv = aUv;
            }
        """.trimIndent())
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val result = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, result, 0)
        if (result[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw IllegalStateException("Mesh GL shader: $log")
        }
        return id
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    fun add(mesh: RgbdSurfaceMesh, bitmap: Bitmap) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }
        val indices = ByteBuffer.allocateDirect(mesh.triangles.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(mesh.triangles)
                position(0)
            }
        patches.add(GpuPatch(floats(mesh.positions), floats(mesh.uvs), indices,
            mesh.triangles.size, texture))
        for (i in mesh.positions.indices step 3) {
            for (j in 0..2) {
                lower[j] = min(lower[j], mesh.positions[i + j])
                upper[j] = max(upper[j], mesh.positions[i + j])
            }
        }
    }

    fun orbit(dx: Float, dy: Float) {
        yaw += dx * 4f
        pitch = (pitch - dy * 2.7f).coerceIn(-1.30f, 1.30f)
    }

    fun zoom(scale: Float) {
        zoomFactor = (zoomFactor / scale.coerceIn(.5f, 2f)).coerceIn(.3f, 5f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (patches.isEmpty() || program == 0) return
        val x = (lower[0] + upper[0]) * .5f
        val y = (lower[1] + upper[1]) * .5f
        val z = (lower[2] + upper[2]) * .5f
        val sx = upper[0] - lower[0]
        val sy = upper[1] - lower[1]
        val sz = upper[2] - lower[2]
        val radius = (sqrt(sx * sx + sy * sy + sz * sz) * .5f).coerceAtLeast(1.0f)
        val distance = radius * 2.9f * zoomFactor
        val eyeX = x + distance * cos(pitch) * sin(yaw)
        val eyeY = y + distance * sin(pitch)
        val eyeZ = z + distance * cos(pitch) * cos(yaw)
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val mvp = FloatArray(16)
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, x, y, z, 0f, 1f, 0f)
        Matrix.perspectiveM(projection, 0, 55f,
            viewportWidth.toFloat() / viewportHeight, .05f,
            max(40f, radius * 40f))
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aUv)
        for (patch in patches) {
            patch.position.position(0)
            patch.uv.position(0)
            patch.indices.position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, patch.texture)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT,
                false, 0, patch.position)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT,
                false, 0, patch.uv)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, patch.indexCount,
                GLES20.GL_UNSIGNED_SHORT, patch.indices)
        }
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aUv)
    }
}
