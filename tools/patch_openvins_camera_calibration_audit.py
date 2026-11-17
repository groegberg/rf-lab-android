#!/usr/bin/env python3
"""Pin Android Camera2 acquisition geometry and record actual capture evidence.

Applied AFTER research/openvins_pixel_test_patch.py. Native ImageReader YUV
frames remain in the camera sensor's pixel axes; device/display rotation MUST
NOT be applied to VIO frames. The landscape UI lock avoids Activity recreation
when physically rotating the phone, but does NOT freeze optical mechanisms.

This is an instrumentation/consistency gate, NOT factory Kalibr calibration.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
file=root/"app/src/main/java/com/openvins/android/Camera2ResView.kt"
s=file.read_text(encoding="utf-8")

def once(a,b):
    global s
    if s.count(a)!=1:
        raise RuntimeError("Pinned Camera2 calibration patch drift at "+a[:100])
    s=s.replace(a,b,1)

once("import android.hardware.camera2.CaptureRequest",
     "import android.hardware.camera2.CaptureRequest\n"
     "import android.hardware.camera2.CaptureResult\n"
     "import android.hardware.camera2.TotalCaptureResult")

once("    private val mPreviewFormat = ImageFormat.YUV_420_888",
"""    private val mPreviewFormat = ImageFormat.YUV_420_888

    // CaptureResult, not merely requested CaptureRequest, proves whether the
    // physical stream is stable. Captured on the Camera2 background thread.
    private var auditCaptures = 0
    private var auditPhysicalFirst: String? = null
    private var auditPhysicalChanged = false
    private var auditWarningAlreadySaved = false
    private var auditFocalFirst: FloatArray? = null
    private var auditMaxFocalDrift = 0.0
    private var auditFocusFirst: Float? = null
    private var auditMaxFocusDrift = 0.0
    private var auditResultAf = "UNKNOWN"
    private var auditResultOis = "UNKNOWN"
    private var auditResultEis = "UNKNOWN"
    private var auditResultDistortion = "UNKNOWN"
    private var auditResultZoom = Double.NaN
    private var auditLastPhysical = "UNKNOWN"

    private fun resetCameraAudit() {
        auditCaptures=0
        auditPhysicalFirst=null
        auditPhysicalChanged=false
        auditWarningAlreadySaved=false
        auditFocalFirst=null
        auditMaxFocalDrift=0.0
        auditFocusFirst=null
        auditMaxFocusDrift=0.0
        auditResultAf="UNKNOWN"
        auditResultOis="UNKNOWN"
        auditResultEis="UNKNOWN"
        auditResultDistortion="UNKNOWN"
        auditResultZoom=Double.NaN
        auditLastPhysical="UNKNOWN"
    }

    private fun captureMode(value: Int?, off: Int) =
        when (value) {
            null -> "UNKNOWN"
            off -> "OFF"
            else -> "ACTIVE(" + value + ")"
        }

    private fun writeCameraAudit() {
        try {
            val id=mCameraID ?: return
            val manager=context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val info=manager.getCameraCharacteristics(id)
            val tsource=info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            val poseRef=info.get(CameraCharacteristics.LENS_POSE_REFERENCE)
            val metadataK=info.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val logical=info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                ?:false
            val pose=when(poseRef) {
                CameraCharacteristics.LENS_POSE_REFERENCE_GYROSCOPE -> "GYROSCOPE"
                CameraCharacteristics.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "PRIMARY_CAMERA"
                CameraCharacteristics.LENS_POSE_REFERENCE_UNDEFINED -> "UNDEFINED"
                else -> "UNKNOWN"
            }
            val timestamp=when(tsource) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
                else -> "UNAVAILABLE"
            }
            val root=context.getExternalFilesDir(null) ?: return
            val dir=java.io.File(root,"config").apply{mkdirs()}
            val status=java.io.File(dir,"PIXEL_CAMERA_CAPTURE_EVIDENCE.txt")
            val buffer=java.io.File(dir,"PIXEL_CAMERA_CAPTURE_EVIDENCE.tmp")
            val report=buildString {
                appendLine("schema=rf-lab-camera-capture-evidence-v1")
                appendLine("camera_id="+id)
                appendLine("timestamp_source="+timestamp)
                appendLine("lens_pose_reference="+pose)
                appendLine("sensor_orientation_degrees="+
                    (info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1))
                appendLine("image_width="+mPreviewSize.width)
                appendLine("image_height="+mPreviewSize.height)
                appendLine("intrinsics_available="+(metadataK!=null && metadataK.size>=4))
                appendLine("logical_multi_camera="+logical)
                appendLine("active_physical_id="+auditLastPhysical)
                appendLine("physical_camera_changed="+auditPhysicalChanged)
                appendLine("capture_result_count="+auditCaptures)
                appendLine("af_mode="+auditResultAf)
                appendLine("ois_mode="+auditResultOis)
                appendLine("eis_mode="+auditResultEis)
                appendLine("distortion_correction_mode="+auditResultDistortion)
                appendLine("zoom_ratio="+auditResultZoom)
                appendLine("max_focal_relative_drift="+auditMaxFocalDrift)
                appendLine("max_focus_diopter_drift="+auditMaxFocusDrift)
                appendLine("calibration_source=approximate_camera2_seed_not_kalibr")
                appendLine("online_openvins_calibration_exported=false")
                appendLine("metric_calibration_verified=false")
            }
            buffer.writeText(report)
            if(!buffer.renameTo(status))status.writeText(report)
        }catch(e:Exception){
            Log.e(TAG,"Cannot persist Camera2 calibration evidence",e)
        }
    }

    private val captureAuditCallback=object:CameraCaptureSession.CaptureCallback(){
        override fun onCaptureCompleted(session:CameraCaptureSession,
                                        request:CaptureRequest,
                                        result:TotalCaptureResult){
            auditCaptures++
            val active=result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            if(!active.isNullOrBlank()){
                if(auditPhysicalFirst!=null && active!=auditPhysicalFirst)
                    auditPhysicalChanged=true
                if(auditPhysicalFirst==null)auditPhysicalFirst=active
                auditLastPhysical=active
            }
            val intr=result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
            if(intr!=null && intr.size>=4 && intr.take(4).all{it.isFinite()}){
                val first=auditFocalFirst
                if(first==null)auditFocalFirst=intr.copyOf()
                else if(first.size>=2 && first[0]>0f && first[1]>0f)
                    auditMaxFocalDrift=maxOf(auditMaxFocalDrift,
                        kotlin.math.abs(intr[0]/first[0]-1f).toDouble(),
                        kotlin.math.abs(intr[1]/first[1]-1f).toDouble())
            }
            val focus=result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if(focus!=null && focus.isFinite()){
                if(auditFocusFirst==null)auditFocusFirst=focus
                else auditMaxFocusDrift=maxOf(auditMaxFocusDrift,
                    kotlin.math.abs(focus-auditFocusFirst!!).toDouble())
            }
            auditResultAf=captureMode(
                result.get(CaptureResult.CONTROL_AF_MODE),
                CaptureRequest.CONTROL_AF_MODE_OFF)
            auditResultOis=captureMode(
                result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            auditResultEis=captureMode(
                result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            auditResultDistortion=captureMode(
                result.get(CaptureResult.DISTORTION_CORRECTION_MODE),
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            auditResultZoom=result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                ?.toDouble() ?:Double.NaN
            // Do not perform disk IO on EVERY camera callback once a
            // persistent warning appears: one-shot immediate alert, then
            // once per ~second. Avoid creating the very VIO lag we audit.
            val warning=auditPhysicalChanged ||
                auditMaxFocalDrift>.01 || auditMaxFocusDrift>.05
            if(auditCaptures<=2 || auditCaptures%30==0 ||
                (warning && !auditWarningAlreadySaved))writeCameraAudit()
            if(warning)auditWarningAlreadySaved=true
        }
    }
""")

# Distortion correction and autofocus are physical geometry, not cosmetic
# settings. Fixed focus avoids lens breathing. Modality is verified later via
# TotalCaptureResult, not assumed from a successful set() call.
once("""                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                Log.d(TAG,"Continuous autofocus for indoor scene")""",
"""                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF)
                val chars=(context.getSystemService(Context.CAMERA_SERVICE) as
                    CameraManager).getCameraCharacteristics(mCameraID!!)
                val maximum=chars.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val hyper=chars.get(
                    CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
                if(maximum>0f){
                    // About 1.5 m, or optical hyperfocal if greater; clamp
                    // to the range allowed by this camera. Fixed per session.
                    val fixed=maxOf(.67f,hyper).coerceAtMost(maximum)
                    mPreviewRequestBuilder!!.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,fixed)
                    Log.i(TAG,"Fixed scan focus at "+fixed+" diopters")
                } else Log.i(TAG,"Fixed-focus lens; no AF tracking")""")

once("""            mCameraDevice!!.createCaptureSession(
                listOf(mImageReader!!.surface),""",
"""            // Force sensor uncorrected YUV when advertised, matching
            // pre-correction Camera2 intrinsics and the recorded radtan K.
            // If hardware ignores this, the capture audit rejects 3D fusion.
            try {
                val info=(context.getSystemService(Context.CAMERA_SERVICE)
                    as CameraManager).getCameraCharacteristics(mCameraID!!)
                val modes=info.get(
                    CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                if(modes?.contains(
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)==true)
                    mPreviewRequestBuilder!!.set(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_ZOOM_RATIO,1.0f)
            }catch(e:Exception){
                Log.w(TAG,"Cannot freeze distortion mode or optical zoom",e)
            }
            resetCameraAudit()
            writeCameraAudit()
            mCameraDevice!!.createCaptureSession(
                listOf(mImageReader!!.surface),""")

once("""                            mCaptureSession!!.setRepeatingRequest(
                                mPreviewRequestBuilder!!.build(),
                                null,
                                mBackgroundHandler
                            )""",
"""                            mCaptureSession!!.setRepeatingRequest(
                                mPreviewRequestBuilder!!.build(),
                                captureAuditCallback,
                                mBackgroundHandler
                            )""")

file.write_text(s,encoding="utf-8")
print("Camera2 fixed focus, distortion OFF, 1x zoom and result-based motion/physical-lens audit")
