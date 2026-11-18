package dev.rflab.scanner

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Explicit user-triggered post-capture VO + DA3 processing, entirely on-phone. */
class OfflineReconstructionActivity:Activity() {
    private val bg=Color.rgb(9,18,30)
    private val executor=Executors.newSingleThreadExecutor()
    private lateinit var store:PhotoSessionStore
    private lateinit var session:File
    private lateinit var title:TextView
    private lateinit var details:TextView
    private lateinit var calculate:Button
    private lateinit var trajectory:RelativeTrajectoryView
    private lateinit var cloud:ScanCloudView
    private lateinit var mapToggle:Button
    private lateinit var depth:ImageView
    private var running=false

    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun shape(color:Int)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(12).toFloat()
    }
    private fun button(label:String,primary:Boolean=false,onClick:()->Unit)=
        Button(this).apply {
            text=label;isAllCaps=false;textSize=13f
            setTextColor(if(primary)bg else Color.WHITE)
            background=shape(if(primary)Color.rgb(61,214,174)
                else Color.rgb(28,53,71))
            setOnClickListener{onClick()}
        }

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        window.statusBarColor=bg;window.navigationBarColor=bg
        store=PhotoSessionStore(this)
        session=store.sessions().firstOrNull{
            it.name==intent.getStringExtra("photo_session")
        }?:store.sessions().firstOrNull()?:store.newSession()
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12),dp(9),dp(12),dp(10))
        }
        setContentView(root)
        SystemBarsSafeArea.install(this,root)
        val toolbar=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        toolbar.addView(button("‹"){finish()},
            LinearLayout.LayoutParams(dp(50),dp(48)))
        title=TextView(this).apply{
            text="OFFLINE · REKONSTRUKTION"
            textSize=16f;setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
        }
        toolbar.addView(title,LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(toolbar)
        val description=TextView(this).apply{
            text=if(ExternalVioTrajectory.read(session).isNotEmpty())
                "OpenVINS: native VIO-Kameraposen + RGB-Zeitstempel\n"+
                "Depth Anything 3: farbige, RELATIVE 3D-Punkte (nicht vermessen)"
            else "OpenCV: echte Bildkorrespondenzen und relative Posen\n"+
                "Depth Anything 3: dichte KI-Tiefe, noch ohne metrischen Maßstab"
            textSize=11f;setTextColor(Color.rgb(190,222,229))
            setPadding(dp(7),dp(5),dp(7),dp(7))
        }
        root.addView(description)
        val views=FrameLayout(this)
        trajectory=RelativeTrajectoryView(this)
        views.addView(trajectory,FrameLayout.LayoutParams(-1,-1))
        cloud=ScanCloudView(this).apply {
            visibility=View.GONE
            showCameraColors=true
        }
        views.addView(cloud,FrameLayout.LayoutParams(-1,-1))
        root.addView(views,LinearLayout.LayoutParams(-1,0,.45f))
        depth=ImageView(this).apply{
            scaleType=ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(17,33,47))
        }
        root.addView(depth,LinearLayout.LayoutParams(-1,0,.55f))
        details=TextView(this).apply{
            textSize=12f;setTextColor(Color.rgb(190,220,230))
            text="Bereit · "+store.frames(session).size+" Originalfotos\n"+
                "Nur relative Tiefen/Posen, KEIN vermessener 3D-Raum."
            maxLines=9
            setPadding(dp(9),dp(11),dp(9),dp(9))
        }
        root.addView(details)
        mapToggle=button("3D-Kamerapfad / 2D-Pfad") {
            val show=cloud.visibility!=View.VISIBLE
            cloud.visibility=if(show)View.VISIBLE else View.GONE
            trajectory.visibility=if(show)View.GONE else View.VISIBLE
        }.apply{isEnabled=false}
        root.addView(mapToggle,LinearLayout.LayoutParams(-1,dp(44)).apply{
            bottomMargin=dp(5)
        })
        calculate=button("Offline berechnen",true){start()}
        root.addView(calculate,LinearLayout.LayoutParams(-1,dp(55)))
        root.addView(button("Aufnahmeserie / Export öffnen"){
            startActivity(Intent(this,PhotoGalleryActivity::class.java).apply{
                putExtra("photo_session",session.name)
            })
        },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(6)})
        val importedVio=ExternalVioTrajectory.read(session)
        if(importedVio.isNotEmpty()){
            trajectory.externalPoses=importedVio
            show3dNativeTrajectory(importedVio)
            details.text=importedVio.size.toString()+" echte OpenVINS-Posen · "+
                store.frames(session).size+" RGB-Fotos\n"+
                "3D-Kamerapfad sichtbar. Farbige Tiefe nur nach Qualitätsprüfung."
        }
    }

    private fun show3dNativeTrajectory(
        poses:List<ExternalVioTrajectory.Sample>) {
        if(poses.isEmpty())return
        val preview=RoomScanStore(this,"rflab_openvins_scan_v1.json")
        // Create a TRANSIENT 3D track, not a persisted artificial depth
        // map. Prevent mixing this session with any previous map origin.
        preview.start("OpenVINS · 3D native VIO trajectory")
        preview.poseSource="OpenVINS native camera path only; "+
            "no color depth; no validated metric calibration"
        val stride=(poses.size/1200).coerceAtLeast(1)
        poses.forEachIndexed { index,sample ->
            if(index%stride==0){
                OpenVinsPoseTransform.toMap(sample.center())?.let { xyz ->
                    preview.addPose(JSONObject().put("state","TRACKING")
                        .put("position_m",JSONArray().put(xyz[0])
                            .put(xyz[1]).put(xyz[2])))
                }
            }
        }
        cloud.store=preview
        cloud.visibility=View.VISIBLE
        trajectory.visibility=View.GONE
        mapToggle.text="3D-Kamerapfad / 2D-Pfad"
        mapToggle.isEnabled=true
    }

    private fun start(){
        if(running)return
        running=true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        calculate.isEnabled=false
        mapToggle.isEnabled=false
        // Keep the real trajectory visible during slow AI reconstruction.
        details.text="OpenVINS / Depth Anything 3 wird offline verarbeitet …"
        executor.execute{
            try{
                val result=OfflineReconstruction(applicationContext)
                    .run(session){s->
                        runOnUiThread{
                            if(!isFinishing&&!isDestroyed)details.text=s
                        }
                    }
                runOnUiThread{
                    if(isFinishing||isDestroyed){
                        result.lastDepth?.recycle()
                        return@runOnUiThread
                    }
                    trajectory.poses=result.trajectory?.poses?:emptyList()
                    trajectory.externalPoses=result.externalVio
                    if(result.fusedPoints>=50){
                        cloud.store=RoomScanStore(this,
                            "rflab_openvins_scan_v1.json")
                        cloud.visibility=View.VISIBLE
                        trajectory.visibility=View.GONE
                        mapToggle.isEnabled=true
                        mapToggle.text="3D-Farbpunkte / 2D-Kamerapfad"
                    }else if(result.externalVio.isNotEmpty()){
                        // Lack of calibrated depth MUST NOT hide the native
                        // 3D trajectory the user has already recorded.
                        show3dNativeTrajectory(result.externalVio)
                    }
                    result.lastDepth?.let{depth.setImageBitmap(it)}
                    title.text="OFFLINE · ERGEBNIS"
                    details.text=if(result.externalVio.isNotEmpty()){
                        val diag=VioSessionDiagnostics.inspect(result.externalVio,
                            store.frames(session).map{
                                it.json.optLong("timestamp_ns") })
                        result.da3Images.toString()+" DA3-Bilder · "+
                            diag.matchedPhotos+"/"+diag.photoCount+
                            " Fotos mit VIO-Pose\n"+
                            if(result.fusedPoints>=50)
                                result.fusedPoints+" RGB-Farbpunkte aus "+
                                    result.fusedFrames+" Keyframes\n"+
                                    "Experimentelle Karte · Maßstab unvalidiert"
                            else "3D-Kamerapfad ist vorhanden, aber KEINE Farbkarte.\n"+
                                "Grund: "+(OpenVinsCameraEvidence.read(session)
                                    .problems.firstOrNull()
                                    ?:result.warnings.lastOrNull()
                                    ?:"Unzureichende Multi-View-Triangulation")
                                    .take(230)
                    }else{
                        result.da3Images.toString()+
                            " DA3-Bilder · "+result.pairedImages+
                            " Posen zugeordnet\n"+
                            result.acceptedPairs+" gültige VO-Schritte · "+
                            result.lostPairs+" unzuverlässig · "+
                            (result.trajectory?.gyroFusedPairs?:0)+" IMU-Rotationen\n"+
                            "Relativer Maßstab – kein metrisches Mesh."
                    }
                    calculate.text="Erneut auswerten"
                    calculate.isEnabled=true
                    running=false
                    window.clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }catch(t:Throwable){
                runOnUiThread{
                    if(!isFinishing&&!isDestroyed){
                        details.text="Offline-Verarbeitung fehlgeschlagen: "+
                            (t.message?:t.javaClass.simpleName)
                        calculate.isEnabled=true
                        running=false
                        window.clearFlags(
                            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }
    override fun onDestroy(){
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        executor.shutdown()
        super.onDestroy()
    }
}

/** 2D projection of relative camera centres. No metres or accurate room claim. */
internal class RelativeTrajectoryView(context:android.content.Context):View(context){
    var poses:List<OfflineVisualOdometry.Pose> = emptyList()
        set(value){field=value;invalidate()}
    var externalPoses:List<ExternalVioTrajectory.Sample> = emptyList()
        set(value){field=value;invalidate()}
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(71,216,188);strokeWidth=4f;style=Paint.Style.STROKE
    }
    private val point=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(245,130,94);style=Paint.Style.FILL
    }
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(174,205,218);textSize=29f
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15,31,45))
        canvas.drawText(if(externalPoses.isNotEmpty())
            "KAMERAPFAD · OPENVINS (UNKALIBRIERT)"
            else "KAMERAPFAD · RELATIV",25f,40f,text)
        if(poses.size<2 && externalPoses.size<2){
            canvas.drawText("Noch keine optische Trajektorie",25f,height*.55f,text)
            return
        }
        val isOpenVins=externalPoses.size>=2
        val centers=if(isOpenVins)
            externalPoses.map{it.center()} else poses.map{it.center()}
        // Native OpenVINS initializes a gravity-aligned global frame with
        // X/Y horizontal and Z vertical. Old two-view OpenCV kept X/Z.
        val horizontalY=if(isOpenVins)1 else 2
        val minX=centers.minOf{it[0]};val maxX=centers.maxOf{it[0]}
        val minZ=centers.minOf{it[horizontalY]}
        val maxZ=centers.maxOf{it[horizontalY]}
        val length=max(1e-4,max(maxX-minX,maxZ-minZ))
        val scale=min((width-70.0)/length,(height-130.0)/length)
        fun px(x:Double)=35+(x-minX)*scale+
            ((width-70)-(maxX-minX)*scale)/2
        fun py(z:Double)=75+(z-minZ)*scale+
            ((height-130)-(maxZ-minZ)*scale)/2
        val route=Path()
        for((i,c)in centers.withIndex()){
            val x=px(c[0]).toFloat()
            val y=py(c[horizontalY]).toFloat()
            if(i==0)route.moveTo(x,y) else route.lineTo(x,y)
            if(externalPoses.isEmpty() && i>0 && !poses[i].accepted)
                canvas.drawCircle(x,y,4f,point)
        }
        canvas.drawPath(route,line)
    }
}
