package dev.rflab.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.EditText
import android.text.InputType
import org.json.JSONObject
import org.json.JSONArray
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** Inspect/select real Camera2 photographs; session switch, delete and ZIP export. */
class PhotoGalleryActivity: Activity() {
    private lateinit var store: PhotoSessionStore
    private lateinit var session: File
    private lateinit var grid: GridLayout
    private lateinit var summary: TextView
    private lateinit var selector: Button
    private val background=Color.rgb(9,18,30)
    private val writer=Executors.newSingleThreadExecutor()
    private var exportCode=712
    private var importCode=713

    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun shape(color:Int)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(12).toFloat()
    }
    private fun button(label:String,action:()->Unit)=Button(this).apply{
        text=label;isAllCaps=false;textSize=12f
        setTextColor(Color.WHITE);background=shape(Color.rgb(31,55,70))
        setOnClickListener{action()}
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        window.statusBarColor=background
        window.navigationBarColor=background
        store=PhotoSessionStore(this)
        val selected=intent.getStringExtra("photo_session")
        session=store.sessions().firstOrNull{it.name==selected}
            ?:store.sessions().firstOrNull()?:store.newSession()
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setBackgroundColor(this@PhotoGalleryActivity.background)
            setPadding(dp(10),dp(7),dp(10),0)
        }
        setContentView(root)
        SystemBarsSafeArea.install(this, root)
        val top=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        }
        top.addView(button("‹"){finish()},
            LinearLayout.LayoutParams(dp(48),dp(48)))
        selector=button("Aufnahmeserie  ▾"){showSessions()}
        top.addView(selector,LinearLayout.LayoutParams(0,dp(48),1f).apply{
            leftMargin=dp(8);rightMargin=dp(8)
        })
        top.addView(button("ZIP sichern"){requestExport()},
            LinearLayout.LayoutParams(dp(115),dp(48)))
        root.addView(top)
        summary=TextView(this).apply{
            setTextColor(Color.rgb(187,221,232));textSize=12f
            setPadding(dp(5),dp(9),0,dp(10))
        }
        root.addView(summary)
        root.addView(button("Interne OpenVINS-Aufnahme übernehmen") {
            summary.text="Native VIO-Daten werden direkt übernommen …"
            writer.execute {
                try {
                    val result=OpenVinsSessionImport(applicationContext).importLocalLatest()
                    runOnUiThread {
                        if(!isFinishing && !isDestroyed) {
                            session=result.session
                            refresh()
                            Toast.makeText(this,result.photos.toString()+
                                " Fotos + "+result.poses+" native VIO-Posen",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }catch(t:Exception) {
                    runOnUiThread {
                        if(!isFinishing && !isDestroyed) {
                            refresh()
                            Toast.makeText(this,"Übernahme fehlgeschlagen: "+
                                (t.message?:"Fehler"),Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        },LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(button("Ältere OpenVINS-ZIP importieren") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type="application/zip"
            },importCode)
        },LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(button("Offline: Optische Posen + Depth Anything 3"){
            startActivity(Intent(this,OfflineReconstructionActivity::class.java).apply{
                putExtra("photo_session",session.name)
            })
        },LinearLayout.LayoutParams(-1,dp(52)))
        root.addView(button("Schachbrett-Kamerakalibrierung prüfen"){
            evaluateCameraCheckerboard()
        },LinearLayout.LayoutParams(-1,dp(44)))
        root.addView(button("OpenVINS Referenztest · 1 m / Loop / 90°"){
            showReferenceDialog()
        },LinearLayout.LayoutParams(-1,dp(44)))
        val scroll=ScrollView(this).apply{isFillViewport=true}
        grid=GridLayout(this).apply{
            columnCount=3
            alignmentMode=GridLayout.ALIGN_BOUNDS
        }
        scroll.addView(grid)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply{
            text="Tippen: Foto + Depth Anything 3 prüfen"
            setTextColor(Color.rgb(145,178,194));textSize=11f
            setPadding(dp(6),dp(9),0,dp(14))
        })
    }
    override fun onResume(){super.onResume();if(::grid.isInitialized)refresh()}
    private fun refresh(){
        grid.removeAllViews()
        val entries=store.frames(session)
        val vio=ExternalVioTrajectory.read(session)
        val diagnostics = VioSessionDiagnostics.inspect(
            vio, entries.map { it.json.optLong("timestamp_ns") })
        summary.text=entries.size.toString()+" Fotos · nur lokal gespeichert\n"+
            if(vio.isNotEmpty()){
                val rate=String.format(java.util.Locale.GERMANY,"%.1f",
                    diagnostics.poseRateHz)
                vio.size.toString()+" native OpenVINS-Posen · "+
                    diagnostics.matchedPhotos+"/"+diagnostics.photoCount+
                    " Fotos mit VIO-Pose\n"+
                    rate+" Posen/s · größte Lücke "+
                    diagnostics.maxPoseGapMs.toInt()+" ms · Kalibrierung unvalidiert"+
                    if(diagnostics.warnings.isNotEmpty())
                        "\n⚠ "+diagnostics.warnings.first() else ""
            }else "Camera2-Bilder · keine unabhängige metrische SLAM-Pose"
        selector.text=session.name.removePrefix("session_")+"  ▾"
        val columnWidth=(resources.displayMetrics.widthPixels-dp(48))/3
        entries.forEachIndexed{index,f->
            val tile=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                background=shape(Color.rgb(22,42,56))
            }
            val options=BitmapFactory.Options().apply{inSampleSize=8}
            val bitmap=BitmapFactory.decodeFile(f.jpeg.absolutePath,options)
            val image=ImageView(this).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
                setBackgroundColor(Color.rgb(16,27,39))
                setOnClickListener{openPhoto(index)}
            }
            tile.addView(image,LinearLayout.LayoutParams(-1,dp(112)))
            tile.addView(TextView(this).apply{
                text=(index+1).toString()+" · "+f.json.optInt("feature_count",0)+" Merkmale"
                textSize=10f
                setTextColor(Color.rgb(212,233,239))
                setPadding(dp(6),dp(5),0,dp(6))
                setOnClickListener{openPhoto(index)}
            })
            val spec=GridLayout.LayoutParams(
                GridLayout.spec(index/3),GridLayout.spec(index%3)).apply{
                width=columnWidth
                height=dp(150)
                setMargins(dp(4),dp(4),dp(4),dp(4))
            }
            grid.addView(tile,spec)
        }
    }
    private fun showReferenceDialog(){
        val recorded=ExternalVioTrajectory.read(session)
        if(recorded.size<15){
            AlertDialog.Builder(this).setTitle("VIO-Referenztest")
                .setMessage("Zuerst eine OpenVINS-Aufnahme mit "+
                    "initialisierter Trajektorie aufzeichnen oder importieren.")
                .setPositiveButton("OK",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Physischer Referenztest")
            .setItems(arrayOf("Bekannte geradlinige Strecke (Meter)",
                "Geschlossene Runde (Start = Ende)",
                "Exakte 90°-Handydrehung")){_,option->
                val kind=when(option){
                    0->OpenVinsReferenceBenchmark.Kind.KNOWN_TRANSLATION
                    1->OpenVinsReferenceBenchmark.Kind.CLOSED_ROUTE
                    else->OpenVinsReferenceBenchmark.Kind.KNOWN_ROTATION
                }
                if(option==0){
                    val input=EditText(this).apply{
                        inputType=InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setSingleLine(true)
                        setText("1.0")
                        selectAll()
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Bekannte physische Strecke in m")
                        .setMessage("Start/Ende der INITIALISIERTEN "+
                            "VIO-Pose müssen den real gemessenen Endpunkten "+
                            "entsprechen. Dies ist kein Kalibrierzertifikat.")
                        .setView(input)
                        .setPositiveButton("Auswerten"){_,_->
                            val metres=input.text.toString()
                                .replace(',','.').toDoubleOrNull()
                            if(metres!=null && metres in .1..25.0)
                                showReferenceResult(kind,metres)
                            else Toast.makeText(this,
                                "Strecke 0,1–25 m eingeben",
                                Toast.LENGTH_LONG).show()
                        }.setNegativeButton("Abbrechen",null).show()
                }else showReferenceResult(kind,1.0)
            }.setNegativeButton("Abbrechen",null).show()
    }

    private fun showReferenceResult(
        kind:OpenVinsReferenceBenchmark.Kind,
        knownMetres:Double){
        val recorded=ExternalVioTrajectory.read(session)
        val r=OpenVinsReferenceBenchmark.evaluate(recorded,kind,knownMetres)
        val json=JSONObject()
            .put("source","native OpenVINS with user-defined physical reference")
            .put("kind",kind.name)
            .put("reference_distance_m",r.referenceMetres?:JSONObject.NULL)
            .put("pose_count",r.poseCount)
            .put("unvalidated_path_units",r.pathLengthUnvalidated)
            .put("unvalidated_net_displacement",r.netDisplacementUnvalidated)
            .put("relative_translation_error",
                r.relativeTranslationError?:JSONObject.NULL)
            .put("pose_rotation_angle_degrees",r.rotationAngleDegrees)
            .put("rotation_reference_error_degrees",
                r.rotationErrorDegrees?:JSONObject.NULL)
            .put("metric_calibration_certified",false)
            .put("warnings",JSONArray(r.warnings))
        File(session,"vio_reference_"+kind.name.lowercase()+".json")
            .writeText(json.toString(2))
        val pct=r.relativeTranslationError?.let{
            String.format(java.util.Locale.GERMANY,"%.1f%%",it*100)
        }?:"–"
        val angle=String.format(java.util.Locale.GERMANY,"%.1f°",
            r.rotationAngleDegrees)
        AlertDialog.Builder(this).setTitle("VIO-Referenzauswertung")
            .setMessage("VIO-Netto-Verschiebung: "+
                String.format(java.util.Locale.GERMANY,"%.2f",r.netDisplacementUnvalidated)+
                " unvalidierte Einheiten\nPfad: "+
                String.format(java.util.Locale.GERMANY,"%.2f",r.pathLengthUnvalidated)+
                " Einheiten\nTranslations-/Loop-Fehler: "+pct+
                "\nRotation zwischen Endpunkten: "+angle+
                "\n\nNur aussagekräftig, wenn Referenzbewegung "+
                "und VIO-Endpunkte übereinstimmen. "+
                "JSON im Messdaten-ZIP enthalten.")
            .setPositiveButton("OK",null).show()
    }

    private fun evaluateCameraCheckerboard(){
        val snapshot=session
        val files=store.frames(snapshot).map{it.jpeg}
        if(files.size<10){
            AlertDialog.Builder(this).setTitle("Kamera-Kalibrierung")
                .setMessage("Mindestens 10 Fotos eines planen Schachbretts "+
                    "mit 10×7 Feldern (9×6 innere Ecken) aufnehmen, "+
                    "aus verschiedenen Winkeln und Bildpositionen. "+
                    "Die Kamera-IMU-Extrinsik wird damit noch nicht kalibriert.")
                .setPositiveButton("OK",null).show()
            return
        }
        summary.text="Schachbrett: Kameraintrinsik und Reprojektion prüfen …"
        writer.execute {
            try{
                val report=OpenVinsCheckerboardCalibration.estimate(files)
                File(snapshot,"checkerboard_intrinsics_audit.json")
                    .writeText(report.json().toString(2))
                val message="Erkannte Muster: "+report.detected+"/"+
                    report.tested+"\nRMS: "+
                    (report.rmsReprojectionPx?.let{
                        String.format(java.util.Locale.GERMANY,"%.2f px",it)
                    }?:"nicht bestimmbar")+
                    "\nKamera-Parameter: "+
                    if(report.sufficientForCameraIntrinsics)
                        "Kandidat berechnet, noch unabhängig prüfen."
                    else "unzureichend; mehr variable Musterfotos nötig."
                runOnUiThread{
                    if(!isFinishing && !isDestroyed){
                        refresh()
                        AlertDialog.Builder(this)
                            .setTitle("Kamera-Kalibrierungsdiagnose")
                            .setMessage(message+
                                "\n\nIMU-Rotation, Hebelarm und Sensorzeitoffset "+
                                "sind NICHT kalibriert. JSON im ZIP-Export.")
                            .setPositiveButton("OK",null).show()
                    }
                }
            }catch(e:Exception){
                runOnUiThread{
                    if(!isFinishing && !isDestroyed){
                        refresh()
                        AlertDialog.Builder(this)
                            .setTitle("Kalibrierung fehlgeschlagen")
                            .setMessage(e.message?:e.javaClass.simpleName)
                            .setPositiveButton("OK",null).show()
                    }
                }
            }
        }
    }

    private fun openPhoto(index:Int){
        startActivity(Intent(this,PhotoReviewActivity::class.java).apply{
            putExtra("photo_session",session.name)
            putExtra("photo_index",index)
        })
    }
    private fun showSessions(){
        val popup=PopupMenu(this,selector)
        store.sessions().forEachIndexed{index,f->
            popup.menu.add(0,index+1,index,f.name.removePrefix("session_"))
        }
        popup.setOnMenuItemClickListener{
            session=store.sessions().getOrNull(it.itemId-1)?:session
            refresh();true
        }
        popup.show()
    }
    private fun requestExport(){
        if(store.frames(session).isEmpty()){
            Toast.makeText(this,"Noch keine Fotos",Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/zip"
            putExtra(Intent.EXTRA_TITLE,"rf-lab-photos-"+session.name+".zip")
        },exportCode)
    }
    @Deprecated("Storage Access Framework result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==importCode && resultCode==RESULT_OK){
            val uri=data?.data?:return
            summary.text="OpenVINS-Bilder und Posen werden lokal importiert …"
            writer.execute {
                try {
                    val result=contentResolver.openInputStream(uri)?.use {
                        OpenVinsSessionImport(applicationContext).import(it)
                    }?:error("VIO-Archiv konnte nicht geöffnet werden")
                    runOnUiThread{
                        if(!isFinishing && !isDestroyed){
                            session=result.session
                            refresh()
                            Toast.makeText(this,
                                result.photos.toString()+" RGB-Bilder + "+
                                    result.poses+" VIO-Posen importiert",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }catch(t:Exception){
                    runOnUiThread{
                        if(!isFinishing && !isDestroyed){
                            refresh()
                            Toast.makeText(this,"Import fehlgeschlagen: "+
                                (t.message?:""),Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            return
        }
        if(requestCode!=exportCode||resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        val snapshot=session
        writer.execute{
            try{
                val count=contentResolver.openOutputStream(uri)?.use{
                    store.export(snapshot,it)
                }?:error("Speicherziel nicht verfügbar")
                runOnUiThread{Toast.makeText(this,
                    count.toString()+" Bilder exportiert",Toast.LENGTH_LONG).show()}
            }catch(t:Exception){
                runOnUiThread{Toast.makeText(this,
                    "Export fehlgeschlagen: "+(t.message?:""),Toast.LENGTH_LONG).show()}
            }
        }
    }
    override fun onDestroy(){writer.shutdown();super.onDestroy()}
}
