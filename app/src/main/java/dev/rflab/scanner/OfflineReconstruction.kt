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
        val cameraEvidence=if(external.isNotEmpty())
            OpenVinsCameraEvidence.read(session) else null
        val cameraCalibration=if(cameraEvidence?.allowedForExperimentalFusion==true)
            OpenVinsDa3Fusion.calibration(session) else null
        if(cameraEvidence!=null){
            File(folder,"camera_geometry_audit.json").writeText(JSONObject()
                .put("source","real Camera2 CaptureResult and saved OpenVINS YAML")
                .put("experimental_fusion_gate",cameraEvidence.allowedForExperimentalFusion)
                .put("metric_calibration_verified",false)
                .put("camera_id",cameraEvidence.cameraId ?: JSONObject.NULL)
                .put("capture_result_count",cameraEvidence.testedFrames)
                .put("camera_metadata",JSONObject(cameraEvidence.metrics))
                .put("rejection_reasons",JSONArray(cameraEvidence.problems))
                .toString(2))
            if(!cameraEvidence.allowedForExperimentalFusion)
                warnings+="3D-Karte aus Qualitätsgründen gesperrt; "+
                    "DA3 und VIO bleiben verfügbar: "+
                    cameraEvidence.problems.take(3).joinToString(" · ")
        }
        if(external.isNotEmpty() && cameraCalibration==null)
            warnings+="Für OpenVINS/DA3 keine verlässliche Kamera-Geometrie; "+
                "keine ungeprüften Farbflächen erzeugt"
        // Native ImageReader JPEGs are sensor-coordinate pixels. Align DA3
        // input orientation using measured IMU gravity and the recorded
        // camera<-IMU extrinsic, NEVER the landscape-locked UI orientation.
        val imu=if(external.isNotEmpty())
            OpenVinsImageOrientation.readImu(session) else emptyList()
        val rotationMatrix=if(external.isNotEmpty())
            File(session,"external_calibration/kalibr_imucam_chain.yaml")
                .takeIf{it.isFile}?.let{
                    OpenVinsImageOrientation.camFromImu(it.readText())
                } else null
        if(external.isNotEmpty() &&
            (imu.isEmpty() || rotationMatrix==null))
            warnings+="IMU-Schwerkraft bzw. T_cam_imu nicht verfügbar: "+
                "Bildausrichtung ungesichert; DA3-Vorschau ohne 3D-Fusion"
        if(external.isNotEmpty()){
            val online=external.count{it.onlineCamera!=null}
            if(online<external.size*.8)
                warnings+="Nativer Kamerapfad enthält nur "+online+"/"+external.size+
                    " Posen mit exportierter Online-Kalibrierung; "+
                    "alte VIO-CSV-Formate werden nicht für 3D verwendet"
        }
        val candidateFeatures=ArrayList<OpenVinsDa3MultiView.Frame>()
        val candidateRays=HashMap<Int,List<OpenVinsDa3Fusion.RaySample>>()
        var fusedFrames=0
        var done=0;var paired=0
        var last:android.graphics.Bitmap?=null
        var ai:Da3DepthEngine?=null
        val manifest=JSONArray()
        try{
            for((i,photo) in selected.withIndex()){
                update("2/2 · Depth Anything 3 "+(i+1)+"/"+selected.size)
                val jpeg=photo.jpeg.readBytes()
                val ts=photo.json.optLong("timestamp_ns")
                val position=if(external.isNotEmpty())
                    ExternalVioTrajectory.closestIndex(external,ts)
                    else closestIndex(poses?.poses?:emptyList(),ts)
                val online=if(external.isNotEmpty() && position>=0)
                    external[position].onlineCamera else null
                val correctedRotation=online?.rotationCamFromImu()
                    ?:rotationMatrix
                val imageOrientation=correctedRotation?.let{
                    OpenVinsImageOrientation.estimate(imu,ts,it)
                }
                // OpenVINS optimizes K, R_ItoC and sensor time offset
                // AFTER startup. Use its SAME-POSE state, not a stale
                // session-start Camera2 seed, to project the RGB keyframe.
                val frameCalibration=if(cameraCalibration!=null &&
                    online!=null && kotlin.math.abs(online.timeOffsetSeconds)<.10)
                    online.cameraCalibration(cameraCalibration)
                    else null
                val engine=ai?:Da3DepthEngine(context.applicationContext).also{
                    ai=it
                }
                // The old fixed +90 rotation remains preview-only when the
                // IMU cannot provide a reliable upright direction.
                val inference=try{
                    engine.predictJpeg(jpeg,imageOrientation?.degrees)
                }
                    catch(t:Throwable){
                        warnings+="DA3 Foto "+(i+1)+": "+(t.message?:"error")
                        continue
                    }
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
                // Keep sparse ORB features plus ~2k true RGB camera rays
                // per frame. Do NOT apply an invented median depth scale.
                // After all DA3 frames have run, fit inverse-depth scale/shift
                // from triangulated native VIO camera baselines.
                if(frameCalibration!=null && external.isNotEmpty() &&
                    imageOrientation?.usableForFusion==true &&
                    inference.cropPercent<.70f &&
                    position>=0 && good.size>=100){
                    try {
                        val bounds=android.graphics.BitmapFactory.Options().apply{
                            inJustDecodeBounds=true
                        }
                        android.graphics.BitmapFactory.decodeByteArray(jpeg,0,
                            jpeg.size,bounds)
                        if(frameCalibration.compatibleWith(
                                bounds.outWidth,bounds.outHeight)){
                            val frame=OpenVinsDa3MultiView.extract(i,
                                inference.image,inference.relativeDepth,
                                external[position],frameCalibration,
                                bounds.outWidth,bounds.outHeight,
                                inference.rotationDegrees)
                            if(frame!=null){
                                candidateFeatures.add(frame)
                                val rays=OpenVinsDa3Fusion.sampleUnscaled(
                                    inference,external[position],frameCalibration,
                                    bounds.outWidth,bounds.outHeight,lo,hi)
                                if(rays.isNotEmpty())candidateRays[i]=rays
                            }
                        }else{
                            warnings+="RGB-Bild "+(i+1)+
                                ": Kamera-Kalibrierung passt nicht zum JPEG"
                        }
                    }catch(t:Throwable){
                        warnings+="VIO/DA3 Bildmerkmale "+(i+1)+": "+
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
                    .put("source_photo_index",i)
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
                    .put("gravity_upright_rotation_degrees",
                        imageOrientation?.degrees ?: JSONObject.NULL)
                    .put("gravity_alignment_error_degrees",
                        imageOrientation?.errorDegrees ?: JSONObject.NULL)
                    .put("imu_gravity_samples",imageOrientation?.samples ?: 0)
                    .put("gravity_orientation_safe_for_fusion",
                        imageOrientation?.usableForFusion ?: false)
                    .put("da3_actual_rotation_degrees",
                        inference.rotationDegrees)
                    .put("online_openvins_camera_calibration_used",
                        frameCalibration!=null)
                    .put("online_camera_imu_time_offset_ms",
                        online?.timeOffsetSeconds?.times(1000.0)
                            ?:JSONObject.NULL)
                    .put("online_camera_focal_length_px",
                        frameCalibration?.let{
                            JSONArray().put(it.fx).put(it.fy)
                        }?:JSONObject.NULL)
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
        var alignedFrames=0
        var matchedFeatures=0
        var triangulatedFeatures=0
        var pairCandidates=0
        val fits=try{
            if(candidateFeatures.size>=2){
                update("3/3 · ORB-Mehrbildmerkmale triangulieren und DA3 "+
                    "Skala/Offset an OpenVINS ausrichten …")
                val alignment=OpenVinsDa3MultiView.align(candidateFeatures)
                matchedFeatures=alignment.matchedFeatures
                triangulatedFeatures=alignment.triangulatedFeatures
                pairCandidates=alignment.pairsConsidered
                alignment.fits
            }else emptyMap()
        }catch(t:Throwable){
            warnings+="Geometrischer Tiefenabgleich fehlgeschlagen: "+
                (t.message?:t.javaClass.simpleName)
            emptyMap()
        }finally{
            candidateFeatures.forEach{it.close()}
        }
        val mapSamples=ArrayList<SurveyDepth>()
        val quality=JSONArray()
        for((index,rays) in candidateRays.toSortedMap()){
            val fit=fits[index] ?: continue
            val fused=OpenVinsDa3Fusion.align(rays,fit)
            if(fused.size>=50 && mapSamples.size+fused.size<180_000){
                mapSamples.addAll(fused)
                fusedFrames++
                alignedFrames++
            }
            quality.put(JSONObject().put("photo_index",index)
                .put("matched_triangular_correspondences",fit.observations)
                .put("inliers",fit.inliers)
                .put("median_relative_error",fit.medianRelativeError)
                .put("inverse_depth_scale_vio",fit.scale)
                .put("inverse_depth_shift_vio",fit.shift)
                .put("coverage_quadrants",fit.coverageQuadrants)
                .put("aligned_3d_points",fused.size))
            for(manifestIdx in 0 until manifest.length()){
                val entry=manifest.optJSONObject(manifestIdx) ?: continue
                if(entry.optInt("source_photo_index",-1)==index)
                    entry.put("inverse_depth_alignment",JSONObject()
                        .put("scale",fit.scale).put("shift",fit.shift)
                        .put("inliers",fit.inliers)
                        .put("median_relative_error",fit.medianRelativeError))
            }
        }
        // At least two independently aligned views are required; otherwise
        // there is no observable shared geometry, only a DA3 depth preview.
        if(mapSamples.size>=150 && alignedFrames>=2 && external.isNotEmpty()){
            try{
                update("3/3 · Verifizierte OpenVINS/DA3-Punkte fusionieren …")
                val output=RoomScanStore(context,"rflab_openvins_scan_v1.json")
                output.start("OpenVINS + DA3 " + suffix)
                output.poseSource="OpenVINS native MSCKF + DA3 aligned inverse "+
                    "depth from real triangulated RGB correspondence; "+
                    "camera calibration/metric accuracy UNVALIDATED"
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
                    warnings+="Mehrbild-abgeglichene Farbkarte ist experimentell: "+
                        "VIO-Kalibrierung und metrische Genauigkeit ungeprüft"
                }else warnings+="Zu wenige 3D-Punkte nach Qualitätsprüfung"
            }catch(t:Throwable){
                warnings+="OpenVINS/DA3-Kartenfusion fehlgeschlagen: "+
                    (t.message?:t.javaClass.simpleName)
            }
        }else if(external.isNotEmpty()){
            warnings+="Keine belastbare Multi-View-Tiefenanpassung. "+
                "Auswertung zeigt nur echte DA3-Tiefen und OpenVINS-Trajektorie; "+
                "bitte mit ausreichender seitlicher Bewegung und Struktur scannen"
        }
        File(folder,"openvins_da3_map_summary.json").writeText(
            JSONObject().put("source","Native OpenVINS + DA3 Small")
                .put("namespace","rflab_openvins_scan_v1.json")
                .put("method","reciprocal ORB, native VIO ray triangulation, "+
                    "robust affine inverse-depth scale/shift, RGB voxel fusion")
                .put("registered_keyframes",alignedFrames)
                .put("colored_points",fusedPoints)
                .put("matches_checked",matchedFeatures)
                .put("triangulated_correspondences",triangulatedFeatures)
                .put("pairs_considered",pairCandidates)
                .put("aligned_keyframe_diagnostics",quality)
                .put("depth_kind","affine-invariant inverse depth")
                .put("multiview_scale_shift_aligned",alignedFrames>=2)
                .put("native_pose_calibration_verified",false)
                .put("camera_geometry_capture_result_gate_passed",
                    cameraEvidence?.allowedForExperimentalFusion?:false)
                .put("metric_accuracy_verified",false)
                .put("loop_closure",false)
                .put("mesh",false)
                .toString(2))
        File(folder,"manifest.json").writeText(JSONObject()
            .put("schema","rflab-offline-da3-pose-bundle-v1")
            .put("images_processed",done)
            .put("photos_with_nearby_optical_pose",paired)
            .put("input_photos",selected.size)
            .put("relative_units_only",true)
            .put("not_metric_or_fused",true)
            .put("multiview_rgb_fusion",fusedPoints>=50)
            .put("fused_colored_point_count",fusedPoints)
            .put("fused_keyframes",fusedFrames)
            .put("multiview_depth_aligned_keyframes",alignedFrames)
            .put("multiview_triangulations",triangulatedFeatures)
            .put("camera_geometry_gate_passed",
                cameraEvidence?.allowedForExperimentalFusion?:false)
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
            alignedFrames+" geometrisch angeglichen, "+
            fusedPoints+" RGB-3D-Punkte")
        return Summary(folder,
            if(external.isNotEmpty()) external.size else poses?.poses?.size?:0,
            poses?.inliers?:0,done,paired,poses?.rejected?:0,
            last,poses,warnings,external,fusedPoints,fusedFrames)
    }
}
