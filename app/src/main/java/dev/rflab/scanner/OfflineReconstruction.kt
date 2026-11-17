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
                       val warnings:List<String>,
                       val externalVio:List<ExternalVioTrajectory.Sample> = emptyList(),
                       val fusedPoints:Int = 0, val fusedFrames:Int = 0)

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
        val external=ExternalVioTrajectory.read(session)
        var poses:OfflineVisualOdometry.Result?=null
        if(external.isNotEmpty()){
            // The VIO is native OpenVINS MSCKF, not RF Lab's unit-step VO.
            // Its calibration was not independently validated on the Pixel.
            update("1/2 · Echte OpenVINS-VIO-Trajektorie importiert: "+
                external.size+" Posen")
            warnings+="Externe OpenVINS-Messung: native VIO-Kameraposen; "+
                "Pixel-Kamera/IMU-Kalibrierung und Genauigkeit unvalidiert"
            File(folder,"external_pose_summary.json").writeText(
                JSONObject().put("source","OpenVINS native MSCKF VIO")
                    .put("samples",external.size)
                    .put("metric_calibration_validated",false)
                    .put("loop_closure_optimized",false)
                    .toString(2))
        } else {
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
        }
        val photos=PhotoSessionStore(context).frames(session)
        val vioQuality = if(external.isNotEmpty())
            VioSessionDiagnostics.inspect(external,
                photos.map { it.json.optLong("timestamp_ns") }) else null
        if (vioQuality != null) {
            warnings += vioQuality.warnings
            File(folder,"openvins_capture_diagnostics.json").writeText(
                JSONObject()
                    .put("source","Native OpenVINS MSCKF")
                    .put("calibration_verified",false)
                    .put("metric_accuracy_verified",false)
                    .put("loop_closure_optimized",false)
                    .put("pose_count",vioQuality.poseCount)
                    .put("photo_count",vioQuality.photoCount)
                    .put("matched_photos",vioQuality.matchedPhotos)
                    .put("pose_rate_hz",vioQuality.poseRateHz)
                    .put("median_pose_gap_ms",vioQuality.medianPoseGapMs)
                    .put("max_pose_gap_ms",vioQuality.maxPoseGapMs)
                    .put("duration_s",vioQuality.durationSeconds)
                    .put("path_length_unvalidated_units",
                        vioQuality.pathLengthUnvalidated)
                    .put("return_offset_unvalidated_units",
                        vioQuality.returnOffsetUnvalidated)
                    .put("return_offset_to_path_length",
                        vioQuality.returnOffsetToPathLength ?: JSONObject.NULL)
                    .put("return_ratio_only_useful_if_route_was_closed",true)
                    .put("warnings",JSONArray(vioQuality.warnings))
                    .toString(2))
        }
        if(photos.isEmpty())warnings+="Keine hochauflösenden Fotos vorhanden"
        // Uniformly sample the WHOLE trajectory; first 24 seconds of a long
        // scan would otherwise map only its starting corner.
        val selected=if(photos.size<=24)photos else (0 until 24).map { i ->
            photos[i * (photos.size-1) / 23]
        }.distinctBy { it.jpeg.name }
        val cameraCalibration=if(external.isNotEmpty())
            OpenVinsDa3Fusion.calibration(session) else null
        if(external.isNotEmpty() && cameraCalibration==null)
            warnings+="Keine passende gespeicherte OpenVINS-Pinhole/Radtan-Kalibrierung: "+
                "DA3-Tiefenkarten bleiben erhalten, aber keine 3D-Punkte projiziert"
        val mapSamples=ArrayList<SurveyDepth>()
        var fusedFrames=0
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
                val ts=photo.json.optLong("timestamp_ns")
                val position=if(external.isNotEmpty())
                    ExternalVioTrajectory.closestIndex(external,ts)
                    else closestIndex(poses?.poses?:emptyList(),ts)
                val matched=if(external.isEmpty() && position>=0)
                    poses?.poses?.getOrNull(position) else null
                if(position>=0)paired++
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
                // The ORIGINAL native camera image geometry is used for
                // projection; DA3 internally rotates/crops to portrait.
                // Never equate DA3 model pixel coordinates with Camera2 K.
                if(cameraCalibration!=null && external.isNotEmpty() &&
                    position>=0 && good.isNotEmpty() &&
                    mapSamples.size<180_000){
                    try {
                        val bounds=android.graphics.BitmapFactory.Options().apply{
                            inJustDecodeBounds=true
                        }
                        android.graphics.BitmapFactory.decodeByteArray(jpeg,0,
                            jpeg.size,bounds)
                        if(cameraCalibration.compatibleWith(
                                bounds.outWidth,bounds.outHeight)){
                            val projected=OpenVinsDa3Fusion.sample(inference,
                                external[position],cameraCalibration,
                                bounds.outWidth,bounds.outHeight,lo,hi,
                                good[good.size/2])
                            if(projected.isNotEmpty()){
                                mapSamples.addAll(projected)
                                fusedFrames++
                            }
                        }else if(fusedFrames==0){
                            warnings+="RGB-Auflösung und Kalibrierung stimmen "+
                                "nicht überein; keine ungesicherte Projektion"
                        }
                    }catch(t:Throwable){
                        warnings+="DA3-3D-Projektion Foto "+(i+1)+": "+
                            (t.message?:t.javaClass.simpleName)
                    }
                }
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
                    .put("optical_pose_valid",
                        if(external.isNotEmpty()) position>=0
                        else matched?.accepted?:false)
                    .put("pose_source",if(external.isNotEmpty())
                        "OpenVINS native MSCKF, unvalidated"
                        else "OpenCV relative monocular VO")
                    .put("external_camera_position_unvalidated",
                        if(external.isNotEmpty() && position>=0)
                            JSONArray(external[position].center().toList())
                        else JSONObject.NULL)
                    .put("pose_metric",false))
                last?.recycle()
                last=inference.depthVisual.copy(
                    android.graphics.Bitmap.Config.ARGB_8888,false)
                inference.depthVisual.recycle()
                inference.image.recycle()
                done++
            }
        }finally{ai?.close()}
        var fusedPoints=0
        if(mapSamples.size>=150 && fusedFrames>=2 && external.isNotEmpty()){
            try{
                update("3/3 · OpenVINS/DA3 Farbpunkte in eigener Karte fusionieren …")
                val output=RoomScanStore(context,"rflab_openvins_scan_v1.json")
                output.start("OpenVINS + DA3 " + suffix)
                output.poseSource="OpenVINS native MSCKF + DA3 relative inverse depth; "+
                    "estimated per-frame median scale 2 VIO units; "+
                    "UNVALIDATED camera calibration, NOT metric"
                val stride=(external.size/1200).coerceAtLeast(1)
                external.forEachIndexed{index,sample->
                    if(index%stride==0){
                        OpenVinsPoseTransform.toMap(sample.center())?.let{xyz->
                            output.addPose(JSONObject().put("state","TRACKING")
                                .put("position_m",JSONArray().put(xyz[0])
                                    .put(xyz[1]).put(xyz[2])))
                        }
                    }
                }
                mapSamples.chunked(2000).forEach{output.addDepth(it)}
                if(output.pointCount()>=50 && output.finish()){
                    fusedPoints=output.coloredPointCount()
                    File(folder,"openvins_da3_map_summary.json").writeText(
                        JSONObject().put("source","Native OpenVINS + single-image DA3")
                            .put("namespace","rflab_openvins_scan_v1.json")
                            .put("colored_points",fusedPoints)
                            .put("keyframes",fusedFrames)
                            .put("voxel_size_vio_units",VoxelFusion.VOXEL_M.toDouble())
                            .put("reference_median_depth_vio_units",
                                OpenVinsDa3Fusion.REFERENCE_DISTANCE_VIO)
                            .put("depth_kind","relative inverse depth")
                            .put("per_frame_affine_scale_verified",false)
                            .put("camera_calibration_verified",false)
                            .put("metric_dimensions_valid",false)
                            .put("mesh",false)
                            .toString(2))
                    warnings+="3D-Farbkarte ist ein relativer Prototyp: "+
                        "keine kalibrierte Entfernung oder Raumabmessung"
                }else warnings+="Zu wenige gültige 3D-Punkte für eine Karte"
            }catch(t:Throwable){
                warnings+="OpenVINS/DA3-Kartenfusion fehlgeschlagen: "+
                    (t.message?:t.javaClass.simpleName)
            }
        }else if(external.isNotEmpty()){
            warnings+="Keine ausreichende Bild-/Posenüberdeckung für die "+
                "farbige 3D-Punktwolke (mindestens 2 Bilder erforderlich)"
        }
        File(folder,"manifest.json").writeText(JSONObject()
            .put("schema","rflab-offline-da3-pose-bundle-v1")
            .put("images_processed",done)
            .put("photos_with_nearby_optical_pose",paired)
            .put("input_photos",selected.size)
            .put("relative_units_only",true)
            .put("not_metric_or_fused",fusedPoints<50)
            .put("fused_colored_point_count",fusedPoints)
            .put("fused_keyframes",fusedFrames)
            .put("metric_dimensions_valid",false)
            .put("vo_status",when{
                external.isNotEmpty()->"OpenVINS native VIO, unvalidated"
                poses!=null->"OpenCV relative-only"
                else->"unavailable"
            })
            .put("external_vio_calibration_validated",false)
            .put("depth_maps",manifest)
            .put("warnings",JSONArray(warnings))
            .toString(2))
        update("Fertig: "+done+" KI-Tiefenkarten, "+
            paired+" Posen zugeordnet, "+fusedPoints+" RGB-3D-Punkte")
        return Summary(folder,
            if(external.isNotEmpty()) external.size else poses?.poses?.size?:0,
            poses?.inliers?:0,done,paired,poses?.rejected?:0,
            last,poses,warnings,external,fusedPoints,fusedFrames)
    }
}
