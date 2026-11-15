package dev.rflab.scanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Independent Camera2 high-resolution photo capture for future offline SLAM.
 * Contains NO invented poses or ARCore depth. Control images manually or in
 * optional feature/blur-aware automatic mode. Camera+IMU timebase is checked.
 */
class PhotoCaptureActivity : Activity(), TextureView.SurfaceTextureListener,
    SensorEventListener {
    private val dark=Color.rgb(9,17,29)
    private lateinit var view: TextureView
    private lateinit var status: TextView
    private lateinit var total: TextView
    private lateinit var autoButton: Button
    private lateinit var manager: CameraManager
    private lateinit var sensors: SensorManager
    private lateinit var storage: PhotoSessionStore
    private lateinit var directory: File
    private val writer=Executors.newSingleThreadExecutor()
    private var thread: HandlerThread?=null
    private var handler: Handler?=null
    private var camera: CameraDevice?=null
    private var session: CameraCaptureSession?=null
    private var preview: Surface?=null
    private var photoReader: ImageReader?=null
    private var analysisReader: ImageReader?=null
    private var deviceId=""
    private var photoWidth=1920
    private var photoHeight=1080
    private var sensorOrientation=90
    private var realtime=false
    private var intrinsics: FloatArray?=null
    private var distortion: FloatArray?=null
    private var crop: android.graphics.Rect?=null
    private var auto=false
    private var inFlight=false
    private var lastAnalysisNs=0L
    private var lastShotNs=0L
    private var shots=0
    private var current: VisualFeatureTracker.Frame?=null
    private var previous: VisualFeatureTracker.Frame?=null
    private var match: VisualFeatureTracker.Match?=null
    @Volatile private var angular=0f
    private var closing=false
    private var started=false
    private val jpegQueue=HashMap<Long,ByteArray>()
    private val metadataQueue=HashMap<Long,JSONObject>()

    private data class Sample(val t: Long,val kind: String,
                              val x: Float,val y: Float,val z: Float)
    private val imuLock=Any()
    private val imu=ArrayDeque<Sample>(360)
    private var imuRecorder: ImuStreamRecorder?=null

    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun bg(color:Int)=GradientDrawable().apply {
        setColor(color); cornerRadius=dp(14).toFloat()
    }
    private fun button(title:String,primary:Boolean=false, click:()->Unit)=
        Button(this).apply {
            text=title;isAllCaps=false;textSize=14f
            setTextColor(if(primary) dark else Color.WHITE)
            background=bg(if(primary) Color.rgb(64,218,182) else Color.rgb(30,51,68))
            setOnClickListener{click()}
        }
    private fun label(message:String)=TextView(this).apply {
        text=message;textSize=12f;setTextColor(Color.WHITE)
        background=bg(Color.argb(219,11,29,40))
        setPadding(dp(12),dp(8),dp(12),dp(8))
    }

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.statusBarColor=dark
        window.navigationBarColor=dark
        manager=getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sensors=getSystemService(Context.SENSOR_SERVICE) as SensorManager
        storage=PhotoSessionStore(this)
        directory=storage.sessions().firstOrNull {
            it.name==state?.getString("photo_session")
        } ?: storage.newSession()
        shots=storage.frames(directory).size
        val root=FrameLayout(this).apply { setBackgroundColor(dark) }
        setContentView(root)
        view=TextureView(this).apply { surfaceTextureListener=this }
        root.addView(view,FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),dp(10),dp(10),0)
        }
        header.addView(button("‹"){finish()},
            LinearLayout.LayoutParams(dp(55),dp(50)))
        header.addView(label("RF LAB · FOTO-SLAM"),
            LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(8) })
        total=label(shots.toString()+" Fotos")
        header.addView(total,LinearLayout.LayoutParams(-2,-2).apply {
            leftMargin=dp(8)
        })
        root.addView(header,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        status=label("Kamera wird gestartet …")
        root.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply {
            topMargin=dp(74);leftMargin=dp(12);rightMargin=dp(12)
        })
        val footer=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(8),dp(8),dp(14))
            background=bg(Color.argb(242,8,17,29))
        }
        autoButton=button("Auto: AUS") {
            auto=!auto
            autoButton.text=if(auto) "Auto: AN" else "Auto: AUS"
            autoButton.background=bg(if(auto) Color.rgb(23,117,94)
                else Color.rgb(30,51,68))
        }
        footer.addView(autoButton,LinearLayout.LayoutParams(0,dp(55),.9f).apply {
            rightMargin=dp(7)
        })
        footer.addView(button("●  FOTO",true){requestPhoto()},
            LinearLayout.LayoutParams(0,dp(55),1.3f).apply {rightMargin=dp(7)})
        footer.addView(button("Fotos"){openGallery()},
            LinearLayout.LayoutParams(0,dp(55),.8f))
        root.addView(footer,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("photo_session",directory.name)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        closing=false
        inFlight=false
        jpegQueue.clear(); metadataQueue.clear()
        lastAnalysisNs=0L
        imuRecorder=ImuStreamRecorder(directory)
        thread=HandlerThread("RFPhotoCamera2").also{it.start()}
        handler=Handler(thread!!.looper)
        listOf(Sensor.TYPE_GYROSCOPE,Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED).forEach{type->
            sensors.getDefaultSensor(type)?.let {
                sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if(view.isAvailable) openCamera()
    }
    override fun onPause() {
        closing=true
        sensors.unregisterListener(this)
        try { imuRecorder?.close() } catch (_:Exception) {}
        imuRecorder=null
        started=false
        try{session?.close()}catch(_:Exception){}
        try{camera?.close()}catch(_:Exception){}
        try{photoReader?.close()}catch(_:Exception){}
        try{analysisReader?.close()}catch(_:Exception){}
        try{preview?.release()}catch(_:Exception){}
        session=null;camera=null;photoReader=null;analysisReader=null;preview=null
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread=null;handler=null
        super.onPause()
    }
    override fun onDestroy() {writer.shutdown();super.onDestroy()}

    private fun openCamera() {
        if(started||closing||handler==null||!view.isAvailable)return
        if(checkSelfPermission(Manifest.permission.CAMERA)!=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA),607)
            return
        }
        try {
            val rear=manager.cameraIdList.filter {
                manager.getCameraCharacteristics(it).get(
                    CameraCharacteristics.LENS_FACING)==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            deviceId=rear.firstOrNull{it=="0"}?:rear.firstOrNull()
                ?:error("Keine Rückkamera")
            val info=manager.getCameraCharacteristics(deviceId)
            val sizes=info.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)?:error("Keine JPEG-Ausgabe")
            val size=sizes.filter{it.width<=2304&&it.height<=2304&&
                it.width*it.height>=850000}.maxByOrNull{it.width*it.height}
                ?:sizes.minByOrNull{abs(it.width*it.height-2_000_000)}
                ?:error("JPEG-Größe fehlt")
            photoWidth=size.width;photoHeight=size.height
            sensorOrientation=info.get(CameraCharacteristics.SENSOR_ORIENTATION)?:90
            realtime=info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            intrinsics=info.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            distortion=info.get(CameraCharacteristics.LENS_DISTORTION)
            crop=info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            photoReader=ImageReader.newInstance(photoWidth,photoHeight,
                ImageFormat.JPEG,3).also{reader->
                reader.setOnImageAvailableListener({r->
                    val img=r.acquireNextImage()?:return@setOnImageAvailableListener
                    img.use {
                        val b=it.planes[0].buffer
                        val data=ByteArray(b.remaining());b.get(data)
                        jpegQueue[it.timestamp]=data
                        flush(it.timestamp)
                    }
                },handler)
            }
            analysisReader=ImageReader.newInstance(640,480,
                ImageFormat.YUV_420_888,2).also{reader->
                reader.setOnImageAvailableListener({r->
                    val img=r.acquireLatestImage()?:return@setOnImageAvailableListener
                    img.use{analyze(it)}
                },handler)
            }
            started=true
            manager.openCamera(deviceId,object:CameraDevice.StateCallback(){
                override fun onOpened(device:CameraDevice) {
                    if(closing){device.close();return}
                    camera=device;configure(device)
                }
                override fun onDisconnected(device:CameraDevice) {
                    started=false;device.close();report("Kamera getrennt")
                }
                override fun onError(device:CameraDevice,error:Int) {
                    started=false;device.close();report("Kamerafehler: "+error)
                }
            },handler)
        }catch(e:Exception){
            started=false
            report("Camera2: "+(e.message?:e.javaClass.simpleName))
        }
    }
    private fun configure(device:CameraDevice) {
        try {
            val texture=view.surfaceTexture?:return
            texture.setDefaultBufferSize(1280,720)
            preview=Surface(texture)
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(preview!!,
                analysisReader!!.surface,photoReader!!.surface),
                object:CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s:CameraCaptureSession){
                        if(closing){s.close();return}
                        session=s
                        val request=device.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW).apply{
                            addTarget(preview!!)
                            addTarget(analysisReader!!.surface)
                            set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        s.setRepeatingRequest(request.build(),null,handler)
                        report("Bereit · Auslösen oder Auto wählen")
                    }
                    override fun onConfigureFailed(s:CameraCaptureSession){
                        started=false;report("Camera2-Konfiguration fehlgeschlagen")
                    }
                },handler)
        }catch(e:Exception){
            started=false;report("Kamera: "+(e.message?:e.javaClass.simpleName))
        }
    }

    private fun analyze(image:android.media.Image){
        if(closing||!started)return
        val now=SystemClock.elapsedRealtimeNanos()
        if(now-lastAnalysisNs<330_000_000L)return
        lastAnalysisNs=now
        val plane=image.planes[0]
        val b=plane.buffer
        val gray=ByteArray(160*120)
        for(y in 0 until 120)for(x in 0 until 160){
            val iy=y*image.height/120;val ix=x*image.width/160
            val idx=iy*plane.rowStride+ix*plane.pixelStride
            if(idx>=0&&idx<b.limit())gray[y*160+x]=b.get(idx)
        }
        val frame=VisualFeatureTracker.analyze(gray,160,120)
        val overlap=previous?.let{VisualFeatureTracker.compare(it,frame)}
        current=frame;match=overlap
        val stable=angular<.85f
        val suitable=VisualFeatureTracker.autoEligible(
            frame,overlap,previous==null)&&stable
        val quality=when{
            !realtime -> "Kamera/IMU-Zeitbasis nicht verifiziert"
            frame.features.size<24 -> "Zu wenig Bildstruktur"
            frame.sharpness<9f -> "Eventuell unscharf"
            !stable -> "Zu schnelle Drehung"
            overlap!=null&&overlap.count<14 -> "Überlappung fehlt"
            overlap!=null&&overlap.medianMotionPx<3f -> "Seitwärts bewegen"
            else -> "Bildqualität gut"
        }
        report(quality+" · "+frame.features.size+" Merkmale"+
            (overlap?.let{" · "+it.count+" Matches"}?:""))
        if(auto&&suitable&&!inFlight&&now-lastShotNs>1_800_000_000L)
            takePhoto(false)
    }
    private fun requestPhoto(){handler?.post{takePhoto(true)}}
    private fun takePhoto(manual:Boolean){
        val device=camera?:return
        val s=session?:return
        val reader=photoReader?:return
        if(closing||inFlight)return
        val f=current
        if(!manual&&!VisualFeatureTracker.autoEligible(
            f?:return,match,previous==null))return
        val now=SystemClock.elapsedRealtimeNanos()
        if(!manual&&now-lastShotNs<1_800_000_000L)return
        inFlight=true;lastShotNs=now
        try{
            val rotation=jpegRotation()
            val request=device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE).apply{
                addTarget(reader.surface)
                preview?.let{addTarget(it)}
                set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_ORIENTATION,rotation)
            }.build()
            s.capture(request,object:CameraCaptureSession.CaptureCallback(){
                override fun onCaptureCompleted(s:CameraCaptureSession,
                    request:CaptureRequest,result:TotalCaptureResult){
                    val ts=result.get(CaptureResult.SENSOR_TIMESTAMP)?:0L
                    if(ts<=0L){inFlight=false;report("Zeitstempel fehlt");return}
                    val meta=JSONObject()
                        .put("timestamp_ns",ts)
                        .put("timestamp_source",if(realtime)"REALTIME" else "UNKNOWN")
                        .put("camera_imu_clock_comparable",realtime)
                        .put("camera_id",deviceId)
                        .put("image_width",photoWidth).put("image_height",photoHeight)
                        .put("jpeg_rotation",rotation)
                        .put("sensor_orientation",sensorOrientation)
                        .put("manual_capture",manual)
                        .put("exposure_time_ns",result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                        .put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY))
                        .put("focal_mm",result.get(CaptureResult.LENS_FOCAL_LENGTH))
                        .put("focus_diopters",result.get(CaptureResult.LENS_FOCUS_DISTANCE))
                        .put("af_state",result.get(CaptureResult.CONTROL_AF_STATE))
                        .put("ae_state",result.get(CaptureResult.CONTROL_AE_STATE))
                        .put("lens_intrinsics",PhotoSessionStore.floats(intrinsics))
                        .put("lens_distortion",PhotoSessionStore.floats(distortion))
                        .put("active_array",crop?.let{
                            JSONArray().put(it.left).put(it.top)
                                .put(it.right).put(it.bottom)})
                        .put("feature_count",f?.features?.size)
                        .put("sharpness",f?.sharpness)
                        .put("texture_coverage",f?.textureCoverage)
                        .put("overlap_matches",match?.count)
                        .put("feature_motion_px",match?.medianMotionPx)
                        .put("gyroscope_speed_rad_s",angular)
                        .put("imu_window",imuWindow(ts))
                    metadataQueue[ts]=meta
                    flush(ts)
                }
                override fun onCaptureFailed(s:CameraCaptureSession,
                    request:CaptureRequest,failure:CaptureFailure){
                    inFlight=false;report("Auslöser fehlgeschlagen")
                }
            },handler)
            previous=f
            report("Foto aufgenommen …")
        }catch(e:Exception){
            inFlight=false;report("Auslöser: "+(e.message?:"Fehler"))
        }
    }
    private fun flush(ts:Long){
        val jpeg=jpegQueue.remove(ts)?:return
        val metadata=metadataQueue.remove(ts)
        if(metadata==null){jpegQueue[ts]=jpeg;return}
        writer.execute{
            try{
                storage.save(directory,jpeg,metadata)
                runOnUiThread{
                    shots++
                    total.text=shots.toString()+" Fotos"
                }
            }catch(e:Exception){report("Speichern: "+(e.message?:"Fehler"))}
            finally{handler?.post{inFlight=false}}
        }
    }
    private fun imuWindow(ts:Long):JSONArray{
        val result=JSONArray()
        if(!realtime)return result
        synchronized(imuLock){
            imu.forEach{sample->
                if(abs(sample.t-ts)<180_000_000L)
                    result.put(JSONObject().put("type",sample.kind)
                        .put("timestamp_ns",sample.t)
                        .put("x",sample.x).put("y",sample.y).put("z",sample.z))
            }
        }
        return result
    }
    private fun jpegRotation():Int{
        @Suppress("DEPRECATION")
        val rotation=windowManager.defaultDisplay.rotation
        val degrees=when(rotation){
            Surface.ROTATION_90->90;Surface.ROTATION_180->180
            Surface.ROTATION_270->270;else->0
        }
        return (sensorOrientation-degrees+360)%360
    }
    override fun onSensorChanged(event:SensorEvent){
        if(event.values.size<3)return
        val v=event.values
        val kind=when(event.sensor.type){
            Sensor.TYPE_GYROSCOPE->"gyro"
            Sensor.TYPE_ACCELEROMETER->"accelerometer"
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED->"gyro_uncalibrated"
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED->"accelerometer_uncalibrated"
            else->return
        }
        if(kind=="gyro")angular=sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])
        try{imuRecorder?.append(event)}catch(_:Exception){}
        synchronized(imuLock){
            imu.addLast(Sample(event.timestamp,kind,v[0],v[1],v[2]))
            while(imu.size>360)imu.removeFirst()
        }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    private fun report(t:String){
        runOnUiThread{if(!isFinishing&&!isDestroyed)status.text=t}
    }
    private fun openGallery(){
        startActivity(Intent(this,PhotoGalleryActivity::class.java).apply{
            putExtra("photo_session",directory.name)
        })
    }
    override fun onSurfaceTextureAvailable(surface:SurfaceTexture,w:Int,h:Int){
        openCamera()
    }
    override fun onSurfaceTextureSizeChanged(surface:SurfaceTexture,w:Int,h:Int){}
    override fun onSurfaceTextureDestroyed(surface:SurfaceTexture)=true
    override fun onSurfaceTextureUpdated(surface:SurfaceTexture){}
    override fun onRequestPermissionsResult(code:Int,
        permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==607&&results.firstOrNull()==PackageManager.PERMISSION_GRANTED)
            openCamera()
        else if(code==607)report("Kameraberechtigung fehlt")
    }
}
