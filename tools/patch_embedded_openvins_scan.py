#!/usr/bin/env python3
"""Expose native OpenVINS pose/capture to RF Lab's in-process scan Activity.

Patches only the pinned GPLv3 upstream checkout, after the Pixel and recording
patches. No additional camera or IMU is created. Never interpolate missing VIO.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
path = root / "app/src/main/java/com/openvins/android/MainActivity.kt"
s = path.read_text(encoding="utf-8")

def replace_once(old: str, new: str) -> None:
    global s
    if s.count(old) != 1:
        raise RuntimeError("Pinned OpenVINS source/patch drift at: " + old[:90])
    s = s.replace(old, new, 1)

replace_once(
    "class MainActivity : AppCompatActivity(), CameraFrameListener, SensorEventListener {",
    "open class MainActivity : AppCompatActivity(), CameraFrameListener, SensorEventListener {",
)

# Host can read initialized native poses and control *the same* Camera2/IMU
# session. The legacy standalone-style UI continues to work unchanged.
bridge = """
    /** Host scanner receives native MSCKF camera pose, not 2-D feature tracks.
     *  Original upstream world frame is approximately gravity-aligned Z-up.
     *  Calibration, coordinate registration and metric scale are NOT validated.
     */
    protected open fun onNativePoseReady(position: DoubleArray, quaternion: DoubleArray) {}

    /** Called during VIO initialization or tracking loss; no fake pose. */
    protected open fun onNativePoseUnavailable() {}

    /** RF Lab has its own 3D view; do not copy 10k poses 20 times per second. */
    protected open fun renderLegacyTrajectoryOverlay(): Boolean = true

    /** Start OpenVINS and the existing synchronized native RGB/IMU/pose writer. */
    protected fun startEmbeddedScan(): Boolean {
        if (isRunningOV || !hasRecordFolder || isRecording) return false
        trajectoryView?.clearTrajectory()
        toggleSystemJNI(true)
        isRunningOV = true
        try {
            setRecordStateJNI(true)
            isRecording = true
        } catch (failure: Throwable) {
            isRunningOV = false
            toggleSystemJNI(false)
            throw failure
        }
        return true
    }

    /** Flush recording before tearing down the native tracker and its worker. */
    protected fun stopEmbeddedScan() {
        if (isRecording) {
            isRecording = false
            setRecordStateJNI(false)
        }
        if (isRunningOV) {
            isRunningOV = false
            toggleSystemJNI(false)
        }
    }

"""
replace_once("    companion object {\n        private const val TAG = \"MainActivity\"",
             bridge + "    companion object {\n        private const val TAG = \"MainActivity\"")

replace_once(
    '            hud.text = "OpenVINS · INITIALISIERT NOCH · langsam seitwärts gehen"\n            return',
    '            hud.text = "OpenVINS · INITIALISIERT NOCH · langsam seitwärts gehen"\n'
    '            onNativePoseUnavailable()\n            return',
)

replace_once(
    '            currentPos[0],currentPos[1],currentPos[2])',
    '            currentPos[0],currentPos[1],currentPos[2])\n'
    '        onNativePoseReady(currentPos, currentQuat)\n'
    '        if (!renderLegacyTrajectoryOverlay()) return',
)
path.write_text(s, encoding="utf-8")
print("Embedded OpenVINS native pose/capture callbacks patched")
