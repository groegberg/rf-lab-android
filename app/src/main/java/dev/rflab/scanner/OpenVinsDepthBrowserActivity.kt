package dev.rflab.scanner

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * v1.3.8 offline RGB-D explorer. Every successfully inferred DA3 photograph
 * has its OWN stored, viewable RGB 2.5D cloud and depth heatmap. A separate
 * world-view shows ALL pose-matched photos projected by native OpenVINS.
 *
 * World-view is an explicitly NONMETRIC diagnostic: monocular DA3 depth
 * scale/shift, Pixel camera/IMU extrinsics and VIO drift remain unverified.
 * Never write its points to the trusted room map namespace.
 */
class OpenVinsDepthBrowserActivity:Activity() {
    private data class Photo(
        val ordinal:Int,val imageName:String,val cloud:File,
        val depth:File,val pose:Int,val rotation:Int,
        val gate:String,val worldProjected:Boolean,
        val dominantPlaneFraction:Double
    )

    private val bg=Color.rgb(9,18,30)
    private val io=Executors.newSingleThreadExecutor()
    private lateinit var session:File
    private lateinit var folder:File
    private lateinit var cloud:ScanCloudView
    private lateinit var rgbImage:ImageView
    private lateinit var depthImage:ImageView
    private lateinit var headline:TextView
    private lateinit var warning:TextView
    private lateinit var previous:Button
    private lateinit var next:Button
    private lateinit var seek:SeekBar
    private lateinit var one:Button
    private lateinit var all:Button
    private var photos:List<Photo> = emptyList()
    private var worldFile:File?=null
    private var worldImages=0
    private var worldAligned=0
    private var worldApproximate=0
    private var worldPoints=0
    private var drift=false
    private var poses:List<ExternalVioTrajectory.Sample> = emptyList()
    private var current=0
    private var worldMode=false
    private var generation=0

    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun button(text:String,primary:Boolean=false,click:()->Unit)=
        Button(this).apply{
            this.text=text
            isAllCaps=false
            textSize=12f
            setTextColor(if(primary)bg else Color.WHITE)
            background=GradientDrawable().apply{
                setColor(if(primary)Color.rgb(79,223,178)
                    else Color.rgb(31,54,74))
                cornerRadius=dp(10).toFloat()
            }
            setOnClickListener{click()}
        }
    private fun text(text:String,size:Float=12f)=TextView(this).apply{
        this.text=text
        textSize=size
        setTextColor(Color.rgb(217,232,238))
        gravity=Gravity.CENTER_VERTICAL
    }

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val requested=intent.getStringExtra("photo_session")
        session=PhotoSessionStore(this).sessions().firstOrNull{
            it.name==requested
        } ?:run{finish();return}
        val folderName=intent.getStringExtra("offline_folder") ?: ""
        folder=File(session,folderName)
        if(!folderName.startsWith("offline_") || !folder.isDirectory ||
            folder.canonicalFile.parentFile!=session.canonicalFile ||
            !File(folder,"manifest.json").isFile){finish();return}
        val manifest=try{JSONObject(File(folder,"manifest.json").readText())}
            catch(_:Exception){finish();return}
        val maps=manifest.optJSONArray("depth_maps")?:JSONArray()
        val images=ArrayList<Photo>()
        for(i in 0 until maps.length()){
            val item=maps.optJSONObject(i) ?:continue
            val relative=item.optString("relative_rgb_cloud_file")
            val depthName=item.optString("depth_preview_file")
            val inputName=item.optString("input_photo")
            if(!relative.matches(Regex("relative_[0-9]{4}.rgbxyz")) ||
                !depthName.matches(Regex("depth_[0-9]{4}\\.jpg")) ||
                !inputName.matches(Regex("img_[0-9]{4}\\.jpg")))continue
            val file=File(folder,relative)
            if(!file.isFile)continue
            images.add(Photo(item.optInt("source_photo_index",i),
                inputName,file,File(folder,depthName),
                item.optInt("optical_pose_index",-1),
                item.optInt("da3_actual_rotation_degrees",0),
                item.optString("geometry_gate_reason","unbekannt"),
                item.optBoolean("diagnostic_world_pose_projected",false),
                item.optDouble("dominant_plane_support_fraction",Double.NaN)))
        }
        photos=images.sortedBy{it.ordinal}
        worldFile=File(folder,"vio_world_diagnostic.rgbxyz")
            .takeIf{it.isFile}
        val diagnostic=File(folder,"openvins_da3_map_summary.json")
            .takeIf{it.isFile}?.let{
                runCatching{JSONObject(it.readText())}.getOrNull()
            }
        worldImages=diagnostic?.optInt("world_diagnostic_frames")?:0
        worldAligned=diagnostic?.optInt("world_diagnostic_aligned_frames")?:0
        worldApproximate=diagnostic?.optInt("world_diagnostic_approximate_frames")?:0
        worldPoints=diagnostic?.optInt("world_diagnostic_points")?:0
        drift= !manifest.optBoolean("vio_motion_gate_passed",false)
        poses=ExternalVioTrajectory.read(session)

        window.statusBarColor=bg
        window.navigationBarColor=bg
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10),dp(6),dp(10),dp(9))
        }
        setContentView(root)
        SystemBarsSafeArea.install(this,root)
        val toolbar=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        toolbar.addView(button("‹ Zurück"){finish()},
            LinearLayout.LayoutParams(dp(89),dp(43)))
        toolbar.addView(text("RGB-D · ALLE AUFNAHMEN",16f),
            LinearLayout.LayoutParams(0,dp(43),1f))
        root.addView(toolbar)
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        one=button("Einzelbilder",true){
            worldMode=false
            display()
        }
        all=button("Gesamtkarte · OpenVINS"){
            worldMode=true
            display()
        }
        tabs.addView(one,LinearLayout.LayoutParams(0,dp(43),1f))
        tabs.addView(all,LinearLayout.LayoutParams(0,dp(43),1.15f).apply{
            leftMargin=dp(6)
        })
        root.addView(tabs)

        headline=text("",12f).apply{setPadding(dp(4),dp(7),dp(4),dp(3))}
        root.addView(headline,LinearLayout.LayoutParams(-1,dp(52)))
        val viewport=FrameLayout(this)
        cloud=ScanCloudView(this).apply{
            topDown=false
            showCameraColors=true
        }
        viewport.addView(cloud,FrameLayout.LayoutParams(-1,-1))
        rgbImage=ImageView(this).apply{
            scaleType=ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(19,32,44))
        }
        depthImage=ImageView(this).apply{
            scaleType=ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(19,32,44))
        }
        val thumbWidth=dp(124)
        val thumbHeight=dp(108)
        viewport.addView(rgbImage,
            FrameLayout.LayoutParams(thumbWidth,thumbHeight,
                Gravity.TOP or Gravity.LEFT).apply{
                    leftMargin=dp(8);topMargin=dp(8)
                })
        viewport.addView(depthImage,
            FrameLayout.LayoutParams(thumbWidth,thumbHeight,
                Gravity.TOP or Gravity.RIGHT).apply{
                    rightMargin=dp(8);topMargin=dp(8)
                })
        root.addView(viewport,LinearLayout.LayoutParams(-1,0,1f))
        warning=text("",11f).apply{
            setTextColor(Color.rgb(255,188,119))
            setPadding(dp(6),dp(6),dp(6),dp(4))
            maxLines=3
        }
        root.addView(warning,LinearLayout.LayoutParams(-1,dp(59)))

        val nav=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        previous=button("◀ Vorheriges"){move(-1)}
        next=button("Nächstes ▶"){move(1)}
        seek=SeekBar(this).apply{
            max=(photos.size-1).coerceAtLeast(0)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar:SeekBar?,progress:Int,
                                               fromUser:Boolean){
                    if(fromUser){
                        current=progress.coerceIn(0,(photos.size-1)
                            .coerceAtLeast(0))
                        worldMode=false
                        display()
                    }
                }
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){}
            })
        }
        nav.addView(previous,LinearLayout.LayoutParams(dp(112),dp(43)))
        nav.addView(seek,LinearLayout.LayoutParams(0,dp(43),1f))
        nav.addView(next,LinearLayout.LayoutParams(dp(110),dp(43)))
        root.addView(nav)
        worldMode=worldFile!=null && worldImages>0
        display()
    }

    private fun move(delta:Int){
        if(photos.isEmpty())return
        current=(current+delta).coerceIn(0,photos.lastIndex)
        worldMode=false
        display()
    }

    private fun display(){
        val w=worldMode
        val id=++generation
        one.isEnabled=w
        all.isEnabled=!w && worldFile!=null
        previous.isEnabled=!w && current>0
        next.isEnabled=!w && current<photos.lastIndex
        seek.isEnabled=!w && photos.isNotEmpty()
        if(!w && seek.progress!=current)seek.progress=current
        if(w) {
            headline.text="GESAMTKARTE · "+worldImages+
                " Aufnahmen auf OpenVINS-Posen · "+worldPoints+" Farbpunkte"
            warning.text=(if(drift)
                "⚠ VIO-DRIFT: Trajektorie unplausibel. " else "")+
                worldAligned+" Bilder geometrisch tiefenangepasst · "+
                worldApproximate+" nur approximativ · KEIN Metermaßstab."
            rgbImage.visibility=View.GONE
            depthImage.visibility=View.GONE
        }else{
            val frame=photos.getOrNull(current)
            headline.text=if(frame!=null)
                "BILD "+(current+1)+"/"+photos.size+" · "+frame.imageName+
                    " · DA3 "+frame.rotation+"°"
                else "Keine DA3-3D-Einzelbilder in dieser Auswertung"
            warning.text=if(frame!=null)
                (if(frame.pose>=0)
                    "VIO-Pose #"+frame.pose+
                        if(frame.worldProjected)" · Teil der Gesamtansicht. "
                        else " · Nicht in Gesamtansicht. "
                    else "Ohne initialisierte VIO-Pose. ")+
                    (if(frame.dominantPlaneFraction.isFinite())
                        "Ebenenanteil "+
                            (frame.dominantPlaneFraction*100).toInt()+
                            "% (nur Diagnose). " else "")+
                    frame.gate
                else "Offline-Rekonstruktion erneut starten."
            rgbImage.visibility=View.VISIBLE
            depthImage.visibility=View.VISIBLE
        }
        io.execute {
            try{
                val dataset=if(w)worldFile else photos.getOrNull(current)?.cloud
                val points=dataset?.let{OpenVinsRgbCloudCache.read(it)}
                    ?:emptyList()
                val preview=RoomScanStore(applicationContext,
                    "rflab_openvins_scan_v1.json")
                preview.start(if(w)"OpenVINS · ALL RGB frames" else
                    "DA3 · individual RGB")
                preview.poseSource=if(w)
                    "OpenVINS DA3 WORLD DIAGNOSTIC; all posed photos; "+
                        "nonmetric/unverified"
                    else "DA3 RELATIVE SINGLE-VIEW; camera-local 2.5D; nonmetric"
                if(w){
                    val trajectory=poses.mapNotNull{
                        OpenVinsPoseTransform.toMap(it.center())
                    }
                    // A single rigid translation keeps a grossly drifting
                    // 75m trajectory inside the existing voxel render range.
                    // No invented change in depth, orientation or pose scale.
                    val xs=points.map{it.x}+trajectory.map{it[0]}
                    val ys=points.map{it.y}+trajectory.map{it[1]}
                    val zs=points.map{it.z}+trajectory.map{it[2]}
                    val ox=if(xs.isNotEmpty())(xs.minOrNull()!!+
                        xs.maxOrNull()!!)*.5f else 0f
                    val oy=if(ys.isNotEmpty())(ys.minOrNull()!!+
                        ys.maxOrNull()!!)*.5f else 0f
                    val oz=if(zs.isNotEmpty())(zs.minOrNull()!!+
                        zs.maxOrNull()!!)*.5f else 0f
                    val stride=(trajectory.size/1200).coerceAtLeast(1)
                    trajectory.forEachIndexed{index,p->
                        if(index%stride==0)
                            preview.addPose(JSONObject().put("state","TRACKING")
                                .put("position_m",JSONArray().put(p[0]-ox)
                                    .put(p[1]-oy).put(p[2]-oz)))
                    }
                    points.chunked(1600).forEach{chunk->
                        preview.addDepth(chunk.map{p->
                            SurveyDepth(p.x-ox,p.y-oy,p.z-oz,p.confidence,
                                argb=p.argb,rawDepth=false)
                        })
                    }
                }else{
                    points.chunked(1600).forEach{preview.addDepth(it)}
                }
                val frame=if(w)null else photos.getOrNull(current)
                val rgb=frame?.let{
                    BitmapFactory.decodeFile(File(session,it.imageName).path)
                }
                val heat=frame?.let{BitmapFactory.decodeFile(it.depth.path)}
                runOnUiThread {
                    if(isFinishing||isDestroyed||id!=generation){
                        rgb?.recycle();heat?.recycle()
                        return@runOnUiThread
                    }
                    cloud.store=preview
                    cloud.topDown=false
                    rgbImage.setImageBitmap(rgb)
                    depthImage.setImageBitmap(heat)
                    if(points.isEmpty())
                        warning.text=warning.text.toString()+
                            " · Keine gespeicherten 3D-Punkte"
                }
            }catch(e:Throwable){
                runOnUiThread{
                    if(!isFinishing&&!isDestroyed&&id==generation)
                        warning.text="RGB-D-Ansicht fehlgeschlagen: "+
                            (e.message?:e.javaClass.simpleName)
                }
            }
        }
    }

    override fun onDestroy(){
        generation++
        io.shutdownNow()
        super.onDestroy()
    }
}
