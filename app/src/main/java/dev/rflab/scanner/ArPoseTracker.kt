package dev.rflab.scanner

import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.View
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Optional camera-based 6DoF trajectory logger. ARCore camera images are rendered only for
 * user guidance; NO images, optical depths, maps or point clouds are saved in RF JSONL.
 * AR poses are explicitly labeled optical/visual-inertial, not RF-derived geometry.
 */
class ArPoseTracker(
    context: Context,
    private val activity: Activity,
    private val store: SessionStore,
    private val report: (String) -> Unit
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile private var session: Session? = null
    @Volatile private var running: Boolean = false
    private var textureId = 0
    private var program = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastPoseNs = 0L
    private var lastStatusNs = 0L
    private var lastFrameTimestamp = 0L
    private var lastErrorNs = 0L
    private var lastTracking = ""
    private val quad = createBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv = createBuffer(FloatArray(8))

    val isTrackingActive: Boolean get() = running

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        visibility = View.GONE
    }

    fun start(): Boolean {
        if (running) return true
        try {
            val install = ArCoreApk.getInstance().requestInstall(activity, true)
            if (install == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                report("ARCore-Installation angefordert. Anschließend erneut 'ARCore starten' wählen.")
                return false
            }
            val newSession = Session(activity)
            visibility = View.VISIBLE
            onResume()
            newSession.resume()
            session = newSession
            running = true
            lastPoseNs = 0L
            lastFrameTimestamp = 0L
            lastTracking = ""
            store.write("ar_session_start", JSONObject()
                .put("coordinate_frame", "ARCore per-session local world frame, not an RF map")
                .put("pose_type", "visual_inertial_camera_pose")
                .put("camera_images_saved", false)
                .put("timestamp_warning", "AR frame timestamp timebase is not guaranteed equal to elapsedRealtimeNanos"))
            report("ARCore gestartet. Kamera langsam auf strukturierte Raumbereiche richten.")
            return true
        } catch (e: Exception) {
            running = false
            session?.close()
            session = null
            visibility = View.GONE
            report("ARCore-Start nicht möglich: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    fun stop() {
        if (!running && session == null) return
        running = false
        val old = session
        session = null
        try { old?.pause() } catch (_: Exception) {}
        try { old?.close() } catch (_: Exception) {}
        try { onPause() } catch (_: Exception) {}
        visibility = View.GONE
        store.write("ar_session_stop", JSONObject())
        store.flush()
        report("ARCore gestoppt; aufgezeichnete Posen sind im JSONL-Export.")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.06f, 0.10f, 0.16f, 1f)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent())
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val arSession = session ?: return
        if (!running || textureId == 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return
        try {
            @Suppress("DEPRECATION")
            val rotation = activity.windowManager.defaultDisplay.rotation
            arSession.setDisplayGeometry(rotation, surfaceWidth, surfaceHeight)
            arSession.setCameraTextureName(textureId)
            val frame = arSession.update()
            if (frame.timestamp != 0L && frame.timestamp != lastFrameTimestamp) {
                lastFrameTimestamp = frame.timestamp
                quad.position(0)
                uv.position(0)
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                    Coordinates2d.TEXTURE_NORMALIZED, uv)
                drawPreview()
            }
            val now = SystemClock.elapsedRealtimeNanos()
            val camera = frame.camera
            val state = camera.trackingState.name
            if (state != lastTracking || now - lastStatusNs > 2_000_000_000L) {
                lastStatusNs = now
                lastTracking = state
                if (state == TrackingState.TRACKING.name) report("ARCore TRACKING – 6DoF-Posen werden erfasst")
                else report("ARCore $state: ${camera.trackingFailureReason}")
            }
            if (now - lastPoseNs >= 100_000_000L) {
                lastPoseNs = now
                val record = JSONObject()
                    .put("state", state)
                    .put("frame_t_ns", frame.timestamp)
                    .put("android_camera_t_ns", frame.androidCameraTimestamp)
                    .put("tracking_failure_reason", camera.trackingFailureReason.name)
                if (camera.trackingState == TrackingState.TRACKING) {
                    val pose = camera.pose
                    record.put("position_m", JSONArray().put(pose.tx().toDouble())
                        .put(pose.ty().toDouble()).put(pose.tz().toDouble()))
                        .put("quaternion_xyzw", JSONArray().put(pose.qx().toDouble())
                            .put(pose.qy().toDouble()).put(pose.qz().toDouble())
                            .put(pose.qw().toDouble()))
                }
                store.write("ar_pose", record)
            }
        } catch (e: Exception) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastErrorNs > 3_000_000_000L) {
                lastErrorNs = now
                report("ARCore-Framefehler: ${e.javaClass.simpleName}: ${e.message}")
                store.write("ar_error", JSONObject().put("error", e.javaClass.simpleName)
                    .put("message", e.message ?: ""))
            }
        }
    }

    private fun drawPreview() {
        if (program == 0) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        val posLocation = GLES20.glGetAttribLocation(program, "aPosition")
        val uvLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        uv.position(0)
        GLES20.glEnableVertexAttribArray(posLocation)
        GLES20.glVertexAttribPointer(posLocation, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(uvLocation)
        GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLocation)
        GLES20.glDisableVertexAttribArray(uvLocation)
        GLES20.glDepthMask(true)
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
