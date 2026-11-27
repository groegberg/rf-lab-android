package dev.rflab.scanner

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen photo review with genuine local DA3 inference. No fake metre
 * estimates: monocular depth stays explicitly labeled relative.
 */
class PhotoReviewActivity:Activity(){
    private lateinit var store:PhotoSessionStore
    private lateinit var dir:File
    private var frames:List<PhotoSessionStore.Frame> = emptyList()
    private var position=0
    private lateinit var left:ImageView
    private lateinit var right:ImageView
    private lateinit var title:TextView
    private lateinit var status:TextView
    private lateinit var previous:Button
    private lateinit var next:Button
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var generation=0
    private var model:Da3DepthEngine?=null
    private val dark=Color.rgb(8,18,30)
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun shape(color:Int)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(12).toFloat()
    }
    private fun button(value:String,click:()->Unit)=Button(this).apply{
        text=value;isAllCaps=false;textSize=12f
        setTextColor(Color.WHITE);background=shape(Color.rgb(32,54,71))
        setOnClickListener{click()}
    }

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        window.statusBarColor=dark
        window.navigationBarColor=dark
        store=PhotoSessionStore(this)
        dir=store.sessions().firstOrNull{
            it.name==intent.getStringExtra("photo_session")
        }?:store.sessions().firstOrNull()?:store.newSession()
        frames=store.frames(dir)
        position=intent.getIntExtra("photo_index",0).coerceIn(
            0,(frames.size-1).coerceAtLeast(0))
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setBackgroundColor(dark)
            setPadding(dp(8),dp(7),dp(8),dp(7))
        }
        setContentView(root)
        SystemBarsSafeArea.install(this, root)
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹"){finish()},
            LinearLayout.LayoutParams(dp(48),dp(46)))
        title=TextView(this).apply{
            textSize=14f;setTextColor(Color.WHITE);gravity=Gravity.CENTER
        }
        header.addView(title,LinearLayout.LayoutParams(0,dp(46),1f))
        previous=button("‹"){if(position>0){position--;render()}}
        next=button("›"){if(position<frames.lastIndex){position++;render()}}
        header.addView(previous,LinearLayout.LayoutParams(dp(45),dp(46)))
        header.addView(next,LinearLayout.LayoutParams(dp(45),dp(46)))
        root.addView(header)

        val landscape=resources.configuration.orientation==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val viewport=LinearLayout(this).apply{
            orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(viewport,LinearLayout.LayoutParams(-1,0,1f))
        fun pane(name:String):ImageView{
            val column=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
            }
            column.addView(TextView(this).apply{
                text=name;textSize=11f;setTextColor(Color.rgb(183,218,228))
                setPadding(dp(8),dp(6),0,dp(4))
            })
            val image=ImageView(this).apply{
                scaleType=ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(20,32,45))
            }
            column.addView(image,LinearLayout.LayoutParams(-1,0,1f))
            viewport.addView(column,if(landscape)
                LinearLayout.LayoutParams(0,-1,1f)
                else LinearLayout.LayoutParams(-1,0,1f))
            return image
        }
        left=pane("KAMERA · ORIGINALFOTO")
        right=pane("DEPTH ANYTHING 3 · RELATIVE TIEFE")
        status=TextView(this).apply{
            textSize=12f
            setTextColor(Color.rgb(183,221,234))
            setPadding(dp(12),dp(8),dp(12),dp(8))
            maxLines=4
        }
        root.addView(status,LinearLayout.LayoutParams(-1,dp(95)))
        root.addView(button("Foto löschen"){confirmDelete()},
            LinearLayout.LayoutParams(-1,dp(49)))
        render()
    }

    private fun render(){
        val seq=++generation
        frames=store.frames(dir)
        if(frames.isEmpty()){
            title.text="Keine Bilder";status.text="Aufnahmeserie ist leer"
            left.setImageDrawable(null);right.setImageDrawable(null)
            return
        }
        position=position.coerceIn(0,frames.lastIndex)
        previous.isEnabled=position>0
        next.isEnabled=position<frames.lastIndex
        title.text="FOTO "+(position+1)+"/"+frames.size
        val item=frames[position]
        val json=item.json
        val timeSource=json.optString("timestamp_source","UNKNOWN")
        val samples=json.optJSONArray("imu_window")?.length()?:0
        val corners=json.optInt("feature_count",0)
        val sharpness=json.optDouble("sharpness",0.0)
        val matches=json.optInt("overlap_matches",0)
        status.text="Depth Anything 3 lädt …\n"+
            "Merkmale "+corners+" · Matches "+matches+
            " · Schärfe "+String.format(java.util.Locale.GERMAN,"%.1f",sharpness)+
            "\nIMU: "+samples+" Werte · Zeitbasis "+timeSource
        worker.execute{
            try{
                val data=item.jpeg.readBytes()
                val original=BitmapFactory.decodeByteArray(data,0,data.size)
                    ?:error("JPEG konnte nicht dekodiert werden")
                val engine=model?:Da3DepthEngine(applicationContext).also{
                    model=it
                }
                val prediction=engine.predictJpeg(data)
                runOnUiThread{
                    if(seq!=generation||isFinishing||isDestroyed){
                        original.recycle();prediction.depthVisual.recycle()
                        prediction.image.recycle()
                    }else{
                        // Compare identical portrait crop to DA3 prediction.
                        original.recycle()
                        left.setImageBitmap(prediction.image)
                        right.setImageBitmap(prediction.depthVisual)
                        status.text="DA3 "+prediction.accelerator+" · "+
                            prediction.inferenceMs+" ms · relative Tiefe\n"+
                            "Merkmale "+corners+" · Matches "+matches+
                            " · Schärfe "+String.format(
                                java.util.Locale.GERMAN,"%.1f",sharpness)+
                            "\nIMU: "+samples+" Werte · Zeitbasis "+timeSource+
                            " · Keine optimierte SLAM-Pose"
                    }
                }
            }catch(e:Throwable){
                runOnUiThread{
                    if(seq==generation&&!isFinishing)
                        status.text="DA3 fehlgeschlagen: "+
                            (e.message?:e.javaClass.simpleName)+
                            "\nOriginal und Metadaten sind trotzdem gespeichert."
                }
            }
        }
    }
    private fun confirmDelete(){
        if(frames.isEmpty())return
        AlertDialog.Builder(this).setTitle("Foto löschen?")
            .setMessage("Das Originalfoto und seine Kamera-/IMU-Daten werden lokal entfernt.")
            .setNegativeButton("Abbrechen",null)
            .setPositiveButton("Löschen"){_,_->
                val frame=frames.getOrNull(position)?:return@setPositiveButton
                if(store.delete(frame))render()
            }.show()
    }
    override fun onDestroy(){
        generation++
        worker.execute{model?.close()}
        worker.shutdown()
        super.onDestroy()
    }
}
