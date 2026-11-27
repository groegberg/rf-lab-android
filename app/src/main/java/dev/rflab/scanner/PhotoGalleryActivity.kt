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
        summary.text=entries.size.toString()+" Fotos · nur lokal gespeichert\n"+
            "Originalbilder · noch keine SLAM-Kameraposen"
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
