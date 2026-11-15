package dev.rflab.scanner

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.View
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Config
import com.google.ar.core.exceptions.NotYetAvailableException
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
import kotlin.math.atan

/**
 * Optional camera-based 6DoF trajectory logger. ARCore camera images are rendered only for
 * user guidance; NO images, optical depths, maps or point clouds are saved in RF JSONL.
 * AR poses are explicitly labeled optical/visual-inertial, not RF-derived geometry.
 */
class ArPoseTracker(
    context: Context,
    private val activity: Activity,
    private val store: SessionStore,
    private val report: (String) -> Unit,
    private val poseObserver: ((JSONObject, Long) -> Unit)? = null,
    private val depthObserver: ((List<SurveyDepth>, Long) -> Unit)? = null,
    private val keyframeObserver: ((RgbdKeyframeData) -> Boolean)? = null
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
    private var cameraIntrinsicsReported = false
    private var lastDepthNs = 0L
    private var depthAvailable = false
    private val rgbdSampler = RgbdSampler()
    private var lastKeyframeNs = 0L
    private var lastKeyframePose: com.google.ar.core.Pose? = null
    @Volatile internal var latestRgbdStats: RgbdSampler.Result? = null
        private set
    private var sweepRequested = false
    private var sweepRunning = false
    private var sweepConfigs: List<CameraConfig> = emptyList()
    private var sweepIndex = 0
    private var sweepStartNs = 0L
    private var sweepLastCameraFrame = 0L
    private var sweepFirstCameraFrame = 0L
    private var sweepUniqueFrames = 0
    private var sweepTrackingFrames = 0
    private var sweepFirstTrackingNs = 0L
    private var sweepErrors = 0
    private var sweepActualIntrinsics: JSONObject? = null
    private val sweepDurationNs = 7_000_000_000L
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

    fun startCameraSweep(): Boolean {
        if (running) {
            report("ARCore läuft bereits. Zuerst ARCore stoppen, dann den Kameratest starten.")
            return false
        }
        sweepRequested = true
        return start()
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
            val cameraSelection = selectWidestSupportedRearCamera(newSession)
            depthAvailable = depthObserver != null && !sweepRequested &&
                newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (depthAvailable) {
                val depthConfig = newSession.config
                depthConfig.depthMode = Config.DepthMode.AUTOMATIC
                newSession.configure(depthConfig)
            }
            if (sweepRequested) {
                sweepConfigs = newSession.getSupportedCameraConfigs(
                    CameraConfigFilter(newSession).setFacingDirection(CameraConfig.FacingDirection.BACK))
                if (sweepConfigs.isEmpty()) {
                    sweepRequested = false
                    newSession.close()
                    report("ARCore-Kameratest: keine unterstützten Rückkamera-Konfigurationen.")
                    return false
                }
                newSession.cameraConfig = sweepConfigs[0]
                newSession.configure(newSession.config)
            }
            visibility = View.VISIBLE
            onResume()
            newSession.resume()
            session = newSession
            running = true
            lastPoseNs = 0L
            lastDepthNs = 0L
            rgbdSampler.reset()
            lastKeyframeNs = 0L
            lastKeyframePose = null
            latestRgbdStats = null
            lastFrameTimestamp = 0L
            lastTracking = ""
            cameraIntrinsicsReported = false
            sweepRunning = sweepRequested
            sweepRequested = false
            if (sweepRunning) {
                sweepIndex = 0
                resetSweepCounters(SystemClock.elapsedRealtimeNanos())
                store.write("ar_camera_sweep_start", JSONObject()
                    .put("config_count", sweepConfigs.size)
                    .put("seconds_per_config", sweepDurationNs / 1.0e9)
                    .put("note", "Each config is applied and tested sequentially; local ARCore world map can reset"))
                report("ARCore-Kameratest: ${sweepConfigs.size} Konfigurationen, je 7 Sekunden. Kamera ruhig auf strukturierte Szene richten.")
            }
            store.write("ar_camera_config", cameraSelection)
            store.write("ar_depth_capability", JSONObject()
                .put("supported", depthAvailable)
                .put("mode", if (depthAvailable) "AUTOMATIC" else "DISABLED")
                .put("geometry_source", "ARCore optical depth, not RF reflection"))
            store.write("ar_session_start", JSONObject()
                .put("coordinate_frame", "ARCore per-session local world frame, not an RF map")
                .put("pose_type", "visual_inertial_camera_pose")
                .put("camera_images_saved", false)
                .put("timestamp_warning", "AR frame timestamp timebase is not guaranteed equal to elapsedRealtimeNanos"))
            report("ARCore gestartet. Kamera langsam auf strukturierte Raumbereiche richten.")
            return true
        } catch (e: Exception) {
            running = false
            sweepRunning = false
            sweepRequested = false
            session?.close()
            session = null
            visibility = View.GONE
            report("ARCore-Start nicht möglich: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    fun stop() {
        if (!running && session == null) return
        if (sweepRunning) {
            store.write("ar_camera_sweep_stop", JSONObject()
                .put("completed_configs", sweepIndex)
                .put("cancelled", true))
        }
        sweepRunning = false
        sweepRequested = false
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
            if (sweepRunning) recordSweepFrame(frame.timestamp, camera.trackingState, now, camera.imageIntrinsics)
            if (depthAvailable && !sweepRunning &&
                camera.trackingState == TrackingState.TRACKING &&
                now - lastDepthNs > 250_000_000L) {
                lastDepthNs = now
                captureDepth(frame, now)
            }
            if (!cameraIntrinsicsReported) {
                cameraIntrinsicsReported = true
                val intrinsics = camera.imageIntrinsics
                val dims = intrinsics.imageDimensions
                val focal = intrinsics.focalLength
                val actualHfov = if (focal[0] > 0f)
                    2.0 * atan(dims[0].toDouble() / (2.0 * focal[0])) * 180.0 / Math.PI
                    else -1.0
                store.write("ar_camera_intrinsics", JSONObject()
                    .put("image_width_px", dims[0]).put("image_height_px", dims[1])
                    .put("fx_px", focal[0].toDouble()).put("fy_px", focal[1].toDouble())
                    .put("effective_horizontal_fov_deg", actualHfov)
                    .put("note", "Active ARCore CPU image crop; not necessarily full sensor FoV"))
                report("ARCore effektives horizontales Kamerasichtfeld: %.1f°".format(actualHfov))
            }
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
                poseObserver?.invoke(record, now)
            }
        } catch (e: Exception) {
            if (sweepRunning) sweepErrors++
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastErrorNs > 3_000_000_000L) {
                lastErrorNs = now
                report("ARCore-Framefehler: ${e.javaClass.simpleName}: ${e.message}")
                store.write("ar_error", JSONObject().put("error", e.javaClass.simpleName)
                    .put("message", e.message ?: ""))
            }
        } finally {
            if (sweepRunning && running &&
                SystemClock.elapsedRealtimeNanos() - sweepStartNs >= sweepDurationNs) {
                advanceSweep()
            }
        }
    }


    /**
     * ARCore only allows camera IDs in its own supported-config list.
     * Prefer the widest rear-camera ID that Camera2 can characterize.
     * Never use a physical camera through Camera2 while ARCore owns the session.
     */
    private fun selectWidestSupportedRearCamera(newSession: Session): JSONObject {
        val result = JSONObject().put("preference", "widest_ARCore_supported_rear_camera")
        try {
            val configs = newSession.getSupportedCameraConfigs(
                CameraConfigFilter(newSession).setFacingDirection(CameraConfig.FacingDirection.BACK))
            val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val samples = JSONArray()
            var best: CameraConfig? = null
            var bestFov = Double.NEGATIVE_INFINITY
            val defaultId = newSession.cameraConfig.cameraId
            var defaultFov = Double.NEGATIVE_INFINITY
            for (config in configs) {
                val horizontalFov = try {
                    val ch = cameraManager.getCameraCharacteristics(config.cameraId)
                    val physical = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    if (physical != null && focals != null && focals.isNotEmpty()) {
                        val f = focals.filter { it > 0f }.minOrNull() ?: 0f
                        if (f > 0f) 2.0 * atan(physical.width / (2.0 * f)) * 180.0 / Math.PI
                        else Double.NaN
                    } else Double.NaN
                } catch (_: Exception) { Double.NaN }
                samples.put(arConfigSpec(config, samples.length())
                    .put("estimated_horizontal_fov_deg", if (horizontalFov.isFinite()) horizontalFov else JSONObject.NULL))
                if (config.cameraId == defaultId && horizontalFov.isFinite()) defaultFov = horizontalFov
                if (horizontalFov.isFinite() && horizontalFov > bestFov) {
                    bestFov = horizontalFov
                    best = config
                }
            }
            // A wide-angle change must be significant; unknown or logical camera IDs retain ARCore's default.
            val chooseWide = best != null && best!!.cameraId != defaultId &&
                bestFov > (if (defaultFov.isFinite()) defaultFov + 10.0 else 90.0)
            if (chooseWide) newSession.cameraConfig = best!!
            val selected = newSession.cameraConfig
            result.put("supported_configs", samples)
                .put("available_config_count", configs.size)
                .put("default_camera_id", defaultId)
                .put("selected_camera_id", selected.cameraId)
                .put("wide_config_selected", chooseWide)
                .put("selection_note", if (chooseWide) "Widest supported ARCore camera selected"
                    else "Retaining default ARCore camera; no proven wider supported rear ID")
            report("ARCore Kamera-ID: ${selected.cameraId}; Ultraweitwinkel ${if (chooseWide) "ausgewählt" else "nicht als separate ARCore-Kamera bestätigt"}")
        } catch (e: Exception) {
            result.put("selection_error", e.message ?: e.javaClass.simpleName)
            result.put("selected_camera_id", newSession.cameraConfig.cameraId)
            report("ARCore Kameraauswahl: Standardmodus (${e.javaClass.simpleName})")
        }
        return result
    }



    /** New raw depth plus lower-priority interpolated depth, coloured by CPU YUV. */
    private fun captureDepth(frame: com.google.ar.core.Frame, timeNs: Long) {
        try {
            val cameraPose = frame.camera.pose
            val previous = lastKeyframePose
            val moved = if (previous == null) true else {
                val dx = cameraPose.tx() - previous.tx()
                val dy = cameraPose.ty() - previous.ty()
                val dz = cameraPose.tz() - previous.tz()
                dx * dx + dy * dy + dz * dz >= .18f * .18f
            }
            val turned = if (previous == null) true else {
                val dot = kotlin.math.abs(
                    cameraPose.qx() * previous.qx() +
                    cameraPose.qy() * previous.qy() +
                    cameraPose.qz() * previous.qz() +
                    cameraPose.qw() * previous.qw())
                dot < .985f
            }
            val elapsed = timeNs - lastKeyframeNs
            val wantKeyframe = keyframeObserver != null &&
                (lastKeyframeNs == 0L ||
                    (elapsed >= 1_600_000_000L &&
                     (moved || turned || elapsed >= 6_000_000_000L)))
            val result = rgbdSampler.capture(frame, frame.camera, wantKeyframe) ?: return
            result.keyframe?.let { candidate ->
                if (keyframeObserver?.invoke(candidate) == true) {
                    lastKeyframeNs = timeNs
                    lastKeyframePose = cameraPose
                }
            }
            latestRgbdStats = result
            if (result.points.isEmpty()) return
            val arr = JSONArray()
            // Retain bounded legacy diagnostics; complete samples reach the scan store.
            for (p in result.points.take(240)) arr.put(JSONArray()
                .put(p.x.toDouble()).put(p.y.toDouble())
                .put(p.z.toDouble()).put(p.confidence.toDouble()))
            store.write("ar_depth_points", JSONObject()
                .put("t_frame_ns", frame.timestamp)
                .put("raw_depth_image_timestamp_ns", result.rawTimestamp)
                .put("full_depth_image_timestamp_ns", result.fullTimestamp)
                .put("method", "ARCore raw + interpolated full depth, aligned camera RGB")
                .put("raw_samples", result.rawCount)
                .put("interpolated_samples", result.fullCount)
                .put("colored_samples", result.coloredCount)
                .put("stored_in_room", result.points.size)
                .put("points", arr))
            depthObserver?.invoke(result.points, timeNs)
        } catch (e: Exception) {
            if (timeNs - lastErrorNs > 3_000_000_000L) {
                lastErrorNs = timeNs
                store.write("ar_depth_error", JSONObject()
                    .put("exception", e.javaClass.simpleName)
                    .put("detail", e.message ?: ""))
                report("RGB-D: " + e.javaClass.simpleName)
            }
        }
    }

    private fun arConfigSpec(config: CameraConfig, index: Int): JSONObject {
        val spec = JSONObject()
            .put("index", index)
            .put("camera_id", config.cameraId)
            .put("facing", config.facingDirection.name)
            .put("fps_min", config.fpsRange.lower)
            .put("fps_max", config.fpsRange.upper)
            .put("cpu_width", config.imageSize.width)
            .put("cpu_height", config.imageSize.height)
            .put("gpu_width", config.textureSize.width)
            .put("gpu_height", config.textureSize.height)
            .put("depth_sensor_usage", config.depthSensorUsage.name)
            .put("stereo_camera_usage", config.stereoCameraUsage.name)
        try {
            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ch = manager.getCameraCharacteristics(config.cameraId)
            val physical = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val focalArr = JSONArray()
            focals?.forEach { focalArr.put(it.toDouble()) }
            val physicalIds = JSONArray()
            ch.physicalCameraIds.forEach { physicalIds.put(it) }
            spec.put("camera2_physical_ids", physicalIds)
                .put("camera2_focal_lengths_mm", focalArr)
                .put("camera2_sensor_width_mm", physical?.width?.toDouble() ?: JSONObject.NULL)
                .put("camera2_sensor_height_mm", physical?.height?.toDouble() ?: JSONObject.NULL)
                .put("camera2_active_array_width_px", active?.width() ?: JSONObject.NULL)
                .put("camera2_active_array_height_px", active?.height() ?: JSONObject.NULL)
                .put("camera2_sensor_orientation_deg",
                    ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: JSONObject.NULL)
                .put("camera2_hardware_level",
                    ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: JSONObject.NULL)
            val firstFocal = focals?.filter { it > 0f }?.minOrNull()
            if (physical != null && firstFocal != null) {
                val approxH = 2.0 * atan(physical.width / (2.0 * firstFocal)) * 180.0 / Math.PI
                spec.put("camera2_estimated_full_sensor_hfov_deg", approxH)
                    .put("camera2_fov_is_approximate", true)
            }
        } catch (e: Exception) {
            spec.put("camera2_metadata_error", "${e.javaClass.simpleName}: ${e.message}")
        }
        return spec
    }

    private fun resetSweepCounters(nowNs: Long) {
        sweepStartNs = nowNs
        sweepLastCameraFrame = 0L
        sweepFirstCameraFrame = 0L
        sweepUniqueFrames = 0
        sweepTrackingFrames = 0
        sweepFirstTrackingNs = 0L
        sweepErrors = 0
        sweepActualIntrinsics = null
    }

    private fun recordSweepFrame(
        cameraTimestampNs: Long, trackingState: TrackingState, nowNs: Long,
        intrinsics: CameraIntrinsics
    ) {
        if (cameraTimestampNs <= 0L || cameraTimestampNs == sweepLastCameraFrame) return
        if (sweepFirstCameraFrame == 0L) sweepFirstCameraFrame = cameraTimestampNs
        sweepLastCameraFrame = cameraTimestampNs
        sweepUniqueFrames++
        if (trackingState == TrackingState.TRACKING) {
            sweepTrackingFrames++
            if (sweepFirstTrackingNs == 0L) sweepFirstTrackingNs = nowNs
        }
        if (sweepActualIntrinsics == null) {
            val dim = intrinsics.imageDimensions
            val focal = intrinsics.focalLength
            val center = intrinsics.principalPoint
            val hfov = if (focal[0] > 0f)
                2.0 * atan(dim[0] / (2.0 * focal[0].toDouble())) * 180.0 / Math.PI
                else -1.0
            val vfov = if (focal[1] > 0f)
                2.0 * atan(dim[1] / (2.0 * focal[1].toDouble())) * 180.0 / Math.PI
                else -1.0
            sweepActualIntrinsics = JSONObject()
                .put("width_px", dim[0]).put("height_px", dim[1])
                .put("fx_px", focal[0].toDouble()).put("fy_px", focal[1].toDouble())
                .put("cx_px", center[0].toDouble()).put("cy_px", center[1].toDouble())
                .put("effective_hfov_deg", hfov).put("effective_vfov_deg", vfov)
                .put("note", "ARCore image intrinsics: effective rectified/cropped FoV, not full optical lens FoV")
        }
    }

    private fun finishSweepConfig() {
        val fps = if (sweepUniqueFrames > 1 && sweepLastCameraFrame > sweepFirstCameraFrame)
            (sweepUniqueFrames - 1) * 1.0e9 / (sweepLastCameraFrame - sweepFirstCameraFrame)
            else 0.0
        val elapsedS = (SystemClock.elapsedRealtimeNanos() - sweepStartNs) / 1.0e9
        val result = arConfigSpec(sweepConfigs[sweepIndex], sweepIndex)
            .put("test_duration_s", elapsedS)
            .put("unique_camera_frames", sweepUniqueFrames)
            .put("observed_camera_fps", fps)
            .put("tracking_frames", sweepTrackingFrames)
            .put("tracking_ratio", if (sweepUniqueFrames > 0)
                sweepTrackingFrames.toDouble() / sweepUniqueFrames else 0.0)
            .put("time_to_first_tracking_s", if (sweepFirstTrackingNs != 0L)
                (sweepFirstTrackingNs - sweepStartNs) / 1.0e9 else JSONObject.NULL)
            .put("frame_errors", sweepErrors)
            .put("actual_ar_intrinsics", sweepActualIntrinsics ?: JSONObject.NULL)
            .put("result", if (sweepUniqueFrames == 0) "no_frames" else "sampled")
        store.write("ar_camera_config_test", result)
        report("Kamera ${sweepIndex + 1}/${sweepConfigs.size}: ${sweepUniqueFrames} Frames, " +
            "%.1f".format(fps) + " FPS, Tracking ${sweepTrackingFrames}/${sweepUniqueFrames}")
    }

    /** Called only from the rendering thread, after processing the current camera frame. */
    private fun advanceSweep() {
        finishSweepConfig()
        val nextIndex = sweepIndex + 1
        if (nextIndex >= sweepConfigs.size) {
            sweepRunning = false
            store.write("ar_camera_sweep_stop", JSONObject()
                .put("completed_configs", sweepConfigs.size).put("cancelled", false))
            store.flush()
            report("Kameratest abgeschlossen: ${sweepConfigs.size} Konfigurationen geprüft. Jetzt JSONL exportieren.")
            activity.runOnUiThread { stop() }
            return
        }
        val arSession = session ?: return
        sweepIndex = nextIndex
        resetSweepCounters(SystemClock.elapsedRealtimeNanos())
        lastFrameTimestamp = 0L
        cameraIntrinsicsReported = false
        try {
            arSession.pause()
            arSession.cameraConfig = sweepConfigs[nextIndex]
            arSession.configure(arSession.config)
            arSession.resume()
            store.write("ar_camera_sweep_switch", JSONObject()
                .put("index", nextIndex)
                .put("camera_id", sweepConfigs[nextIndex].cameraId))
            report("Teste Kamera ${nextIndex + 1}/${sweepConfigs.size}: " +
                "${sweepConfigs[nextIndex].imageSize.width}×${sweepConfigs[nextIndex].imageSize.height} " +
                "@ ${sweepConfigs[nextIndex].fpsRange.lower}-${sweepConfigs[nextIndex].fpsRange.upper} fps")
        } catch (e: Exception) {
            store.write("ar_camera_sweep_error", JSONObject()
                .put("index", nextIndex)
                .put("error_type", e.javaClass.simpleName)
                .put("message", e.message ?: ""))
            sweepRunning = false
            report("Konfiguration ${nextIndex + 1} konnte nicht aktiviert werden. Test abgebrochen: ${e.message}")
            activity.runOnUiThread { stop() }
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
