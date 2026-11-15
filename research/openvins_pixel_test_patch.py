#!/usr/bin/env python3
"""Pixel usability patch for separately licensed GPLv3 OpenVINS research app.

Built source remains the original pinned GPLv3 OpenVINS Mobile repo, patched
only inside CI. RF Lab proprietary app is never combined with this code.
Camera-to-IMU extrinsics remain uncalibrated. Results are NOT metric-validated.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "openvins-mobile")
def replace(path,a,b):
    p=root/path
    text=p.read_text()
    if a not in text:
        raise RuntimeError("Upstream changed: "+path+": "+a[:90])
    p.write_text(text.replace(a,b,1))

main="app/src/main/java/com/openvins/android/MainActivity.kt"
replace(main,
    """            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),""",
    """            arrayOf(Manifest.permission.CAMERA),""")
replace(main,
    """        val appRecordFolder =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                .toString() + "/openvins/""",
    """        // Scoped storage: fully local sessions, no public Documents permission.
        val appRecordFolder = File(appPrivateFolderRoot, "sessions").absolutePath""")
# Original Kotlin string includes a closing quote after /openvins/.
# Remove that quote (not part of the replacement's Python triple delimiter).
p=root/main
fixed=p.read_text().replace(
    'File(appPrivateFolderRoot, "sessions").absolutePath"',
    'File(appPrivateFolderRoot, "sessions").absolutePath')
p.write_text(fixed)
replace(main,
    """        File(privateConfigDir).mkdirs()
        
        // Config files should be in private directory (pushed by sync script)""",
    """        File(privateConfigDir).mkdirs()
        // Bundle default research configs: never require ADB or a PC.
        for (name in listOf("estimator_config.yaml",
                "kalibr_imu_chain.yaml", "kalibr_imucam_chain.yaml")) {
            val target=File(privateConfigDir,name)
            if(!target.exists()) {
                try {
                    assets.open("vio_config/"+name).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                } catch(e:Exception) {
                    Log.e(TAG,"Bundled calibration copy failed: "+name,e)
                }
            }
        }
        Toast.makeText(this,
            "Forschungs-VIO: Kamera/IMU nicht fuer dieses Pixel kalibriert",
            Toast.LENGTH_LONG).show()
        // Calibration templates are unvalidated defaults.""")
replace(main,
    """    private var eventAccel: SensorEvent? = null
    private var eventGyro: SensorEvent? = null""",
    """    private var latestAccel: FloatArray? = null
    private var latestAccelNs: Long = 0L
    private var sensorThread: android.os.HandlerThread? = null
    private var sensorHandler: Handler? = null""")
replace(main,
    """        sensorAccel?.also { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
        sensorGyro?.also { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }""",
    """        sensorThread=android.os.HandlerThread("OpenVINS-IMU").also { it.start() }
        sensorHandler=Handler(sensorThread!!.looper)
        sensorAccel?.also { sensor ->
            sensorManager.registerListener(
                this,sensor,SensorManager.SENSOR_DELAY_FASTEST,sensorHandler)
        }
        sensorGyro?.also { sensor ->
            sensorManager.registerListener(
                this,sensor,SensorManager.SENSOR_DELAY_FASTEST,sensorHandler)
        }""")
replace(main,
    """        sensorManager.unregisterListener(this)
        trajectoryUpdateHandler.removeCallbacks(trajectoryUpdateRunnable)""",
    """        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread=null; sensorHandler=null
        trajectoryUpdateHandler.removeCallbacks(trajectoryUpdateRunnable)""")
p=root/main
s=p.read_text()
start=s.index("    override fun onSensorChanged(event: SensorEvent?) {")
end=s.index("    override fun onAccuracyChanged(",start)
s=s[:start]+"""    override fun onSensorChanged(event: SensorEvent?) {
        if(event==null || event.values.size<3)return
        when(event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel=floatArrayOf(
                    event.values[0],event.values[1],event.values[2])
                latestAccelNs=event.timestamp
            }
            Sensor.TYPE_GYROSCOPE -> {
                val accel=latestAccel?:return
                // Max 20 ms sample-and-hold; never pair arbitrarily stale IMU.
                if(kotlin.math.abs(event.timestamp-latestAccelNs)>20_000_000L)
                    return
                processInertialJNI(accel[0],accel[1],accel[2],
                    event.values[0],event.values[1],event.values[2],
                    event.timestamp*1e-9)
            }
        }
    }

"""+s[end:]
p.write_text(s)

cam="app/src/main/java/com/openvins/android/Camera2ResView.kt"
replace(cam,
    """                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                mPreviewRequestBuilder!!.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // Infinity
                Log.d(TAG, "Focus set to infinity")""",
    """                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                Log.d(TAG,"Continuous autofocus for indoor scene")""")
replace(cam,"mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -2)",
            "mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)")
replace(cam,
    "        calcPreviewSize(width, height)",
    "        calcPreviewSize(width, height)\n        writeApproximateCameraIntrinsics()")
calib_method=r'''
    /** Camera2-based intrinsics are approximate; extrinsics stay uncalibrated. */
    private fun writeApproximateCameraIntrinsics() {
        try {
            val manager=context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id=mCameraID?:return
            val info=manager.getCameraCharacteristics(id)
            val intr=info.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?:return
            val active=info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?:return
            if(intr.size<4 || active.width()<=0 || active.height()<=0 ||
               mPreviewSize.width<=0 || mPreviewSize.height<=0)return
            val sw=active.width().toDouble()
            val sh=active.height().toDouble()
            val ratio=mPreviewSize.width.toDouble()/mPreviewSize.height
            val cropW=kotlin.math.min(sw,sh*ratio)
            val cropH=kotlin.math.min(sh,sw/ratio)
            val offX=(sw-cropW)/2
            val offY=(sh-cropH)/2
            val fx=intr[0]*mPreviewSize.width/cropW
            val fy=intr[1]*mPreviewSize.height/cropH
            val cx=(intr[2]-active.left-offX)*mPreviewSize.width/cropW
            val cy=(intr[3]-active.top-offY)*mPreviewSize.height/cropH
            if(fx<=0.0 || fy<=0.0)return
            val root=context.getExternalFilesDir(null)?:return
            val config=java.io.File(root,"config/kalibr_imucam_chain.yaml")
            if(!config.exists())return
            val old=config.readText()
            val replacement=old
                .replace(Regex("(?m)^  intrinsics:.*$"),
                    "  intrinsics: ["+fx+", "+fy+", "+cx+", "+cy+"]")
                .replace(Regex("(?m)^  resolution:.*$"),
                    "  resolution: ["+mPreviewSize.width+", "+
                        mPreviewSize.height+"]")
            if(replacement!=old)config.writeText(replacement)
            java.io.File(root,"config/PIXEL_CALIBRATION_WARNING.txt").writeText(
                "Camera2 intrinsics rescaled with approximate centered crop. "+
                "Calibration distortion / T_cam_imu / time offset are NOT "+
                "verified for Pixel 10 Pro XL. Do not trust metric positions.\n"+
                "id="+id+" K="+fx+","+fy+","+cx+","+cy+"\n"+
                "source="+info.get(
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)+"\n")
            Log.w(TAG,"Camera2 intrinsics updated; Pixel IMU extrinsics NOT calibrated")
        }catch(e:Exception){
            Log.w(TAG,"Cannot update research intrinsics: "+e.message)
        }
    }

'''
p=root/cam
s=p.read_text()
anchor="    private fun calcPreviewSize(width: Int, height: Int): Boolean {"
assert anchor in s
p.write_text(s.replace(anchor,calib_method+anchor,1))

layout="app/src/main/res/layout/activity_main.xml"
replace(layout,"</RelativeLayout>",
'''    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_alignParentTop="true"
        android:layout_marginTop="4dp"
        android:padding="8dp"
        android:background="#CC182632"
        android:textColor="#FFFFFFFF"
        android:textSize="12sp"
        android:text="OpenVINS TEST · Kamera/IMU unkalibriert · Trajektorie unvalidiert"
        android:gravity="center"/>

</RelativeLayout>''')
print("Applied independent GPLv3 Pixel OpenVINS test APK usability/timing patch")
