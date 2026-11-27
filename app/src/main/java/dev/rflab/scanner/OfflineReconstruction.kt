package dev.rflab.scanner

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Runs ONLY after capture on a dedicated worker. Links true offline optical
 * poses to true Camera2 still images by the same camera sensor timestamps;
 * then saves genuine DA3-Small depth inference for each selected photo.
 *
 * Output is deliberately NOT advertised as a metric fused 3D map. The
 * 2-view VO translations and monocular DA3 scale each remain ambiguous.
 */
internal class OfflineReconstruction(private val context:android.content.Context) {
    data class Summary(val folder:File,val poseCount:Int,val acceptedPairs:Int,
                       val da3Images:Int,val pairedImages:Int,val lostPairs:Int,
                       val lastDepth:android.graphics.Bitmap?,
                       val trajectory:OfflineVisualOdometry.Result?,
                       val warnings:List<String>)

    companion object {
        fun closestIndex(poses:List<OfflineVisualOdometry.Pose>,
                         imageTimestampNs:Long,maxGapNs:Long=170_000_000L):Int {
            if(poses.isEmpty()||imageTimestampNs<=0L)return -1
            var low=0;var high=poses.lastIndex
            while(low<high){
                val mid=(low+high)/2
                if(poses[mid].timestampNs<imageTimestampNs)low=mid+1
                else high=mid
            }
            val current=low
            val before=(current-1).coerceAtLeast(0)
            val selected=if(abs(poses[before].timestampNs-imageTimestampNs) <
                abs(poses[current].timestampNs-imageTimestampNs))
                before else current
            return if(abs(poses[selected].timestampNs-imageTimestampNs)<=maxGapNs)
                selected else -1
        }
    }

    fun run(session:File, update:(String)->Unit = {}):Summary {
        val suffix=System.currentTimeMillis().toString()
        val folder=File(session,"offline_$suffix")
        require(folder.mkdirs()){"Cannot create offline run"}
        val warnings=ArrayList<String>()
        var poses:OfflineVisualOdometry.Result?=null
        try {
            update("1/2 · OpenCV verfolgt Kamera und prüft IMU …")
            poses=OfflineVisualOdometry().estimate(session,update)
            File(folder,"relative_trajectory.json").writeText(
                poses.json().toString(2))
            warnings+=poses.warnings
        }catch(t:Throwable){
            val reason="Optische Odometrie nicht verfügbar: "+
                (t.message?:t.javaClass.simpleName)
            warnings+=reason
            update(reason+" · KI-Fotos werden dennoch berechnet")
        }
        val photos=PhotoSessionStore(context).frames(session)
        if(photos.isEmpty())warnings+="Keine hochauflösenden Fotos vorhanden"
        // Explicitly bounded run to protect thermal/memory stability.
        val selected=photos.take(24)
        var done=0;var paired=0
        var last:android.graphics.Bitmap?=null
        var ai:Da3DepthEngine?=null
        val manifest=JSONArray()
        try{
            for((i,photo) in selected.withIndex()){
                update("2/2 · Depth Anything 3 "+(i+1)+"/"+selected.size)
                val jpeg=photo.jpeg.readBytes()
                val engine=ai?:Da3DepthEngine(context.applicationContext).also{
                    ai=it
                }
                val inference=try{engine.predictJpeg(jpeg)}
                    catch(t:Throwable){
                        warnings+="DA3 Foto "+(i+1)+": "+(t.message?:"error")
                        continue
                    }
                val position=closestIndex(poses?.poses?:emptyList(),
                    photo.json.optLong("timestamp_ns"))
                val matched=if(position>=0)poses!!.poses[position] else null
                if(matched!=null)paired++
                // Store a dense relative-depth map as 16-bit normalized data.
                // 0 means invalid; min/max in metadata restore model units.
                val good=inference.relativeDepth.asSequence()
                    .filter{it.isFinite()&&it>0f}.toList().sorted()
                val lo=if(good.isNotEmpty())
                    good[(good.size*.02).toInt()] else 0f
                val hi=if(good.isNotEmpty())
                    good[(good.size*.98).toInt().coerceAtMost(good.lastIndex)]
                    else 0f
                val spread=(hi-lo).coerceAtLeast(1e-6f)
                val encoded=ByteBuffer.allocate(inference.relativeDepth.size*2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for(d in inference.relativeDepth){
                    val value=if(!d.isFinite()||d<=0f)0 else
                        (1+((d-lo)/spread).coerceIn(0f,1f)*65534f).toInt()
                    encoded.putShort(value.toShort())
                }
                val stem="depth_%04d".format(i)
                File(folder,"$stem.u16").writeBytes(encoded.array())
                File(folder,"$stem.jpg").outputStream().use{stream->
                    inference.depthVisual.compress(
                        android.graphics.Bitmap.CompressFormat.JPEG,88,stream)
                }
                manifest.put(JSONObject()
                    .put("input_photo",photo.jpeg.name)
                    .put("timestamp_ns",photo.json.optLong("timestamp_ns"))
                    .put("depth_u16_file","$stem.u16")
                    .put("depth_preview_file","$stem.jpg")
                    .put("depth_width",Da3DepthEngine.WIDTH)
                    .put("depth_height",Da3DepthEngine.HEIGHT)
                    .put("depth_min_relative",lo.toDouble())
                    .put("depth_max_relative",hi.toDouble())
                    .put("zero_means_invalid",true)
                    .put("model","DA3 Small LiteRT GPU/CPU")
                    .put("backend",inference.accelerator)
                    .put("inference_ms",inference.inferenceMs)
                    .put("crop_percent",inference.cropPercent.toDouble())
                    .put("optical_pose_index",position)
                    .put("optical_pose_valid",matched?.accepted?:false)
                    .put("pose_metric",false))
                last?.recycle()
                last=inference.depthVisual.copy(
                    android.graphics.Bitmap.Config.ARGB_8888,false)
                inference.depthVisual.recycle()
                inference.image.recycle()
                done++
            }
        }finally{ai?.close()}
        File(folder,"manifest.json").writeText(JSONObject()
            .put("schema","rflab-offline-da3-pose-bundle-v1")
            .put("images_processed",done)
            .put("photos_with_nearby_optical_pose",paired)
            .put("input_photos",selected.size)
            .put("relative_units_only",true)
            .put("not_metric_or_fused",true)
            .put("vo_status",if(poses==null)"unavailable" else "relative-only")
            .put("depth_maps",manifest)
            .put("warnings",JSONArray(warnings))
            .toString(2))
        update("Fertig: "+done+" KI-Tiefenkarten, "+
            paired+" Kamerapositionen zugeordnet")
        return Summary(folder,poses?.poses?.size?:0,
            poses?.inliers?:0,done,paired,poses?.rejected?:0,
            last,poses,warnings)
    }
}
