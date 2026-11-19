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
                       val fusedPoints:Int = 0, val fusedFrames:Int = 0,
                       val sparsePreview:List<SurveyDepth> = emptyList(),
                       val relativePreview:List<SurveyDepth> = emptyList(),
                       val multiViewResearchPreview:List<SurveyDepth> = emptyList(),
                       val motionQuality:VioMotionQuality.Report? = null,
                       val worldDiagnosticPoints:List<SurveyDepth> = emptyList(),
                       val worldDiagnosticFrames:Int = 0,
                       val worldAlignedFrames:Int = 0,
                       val worldApproximateFrames:Int = 0)

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

    fun run(session:File,
            onRelativePreview:(List<SurveyDepth>)->Unit = {},
            update:(String)->Unit = {}):Summary {
        val suffix=System.currentTimeMillis().toString()
        val folder=File(session,"offline_$suffix")
        require(folder.mkdirs()){"Cannot create offline run"}
        val warnings=ArrayList<String>()
        val external=ExternalVioTrajectory.read(session)
        val nativeCapture = File(session, "external_vio_trajectory.csv").isFile
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
        } else if (nativeCapture) {
            warnings += "OpenVINS lieferte keine nutzbare Kamerapose. DA3 zeigt die Einzelaufnahmen."
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
        // Reject physically impossible initialized native motion BEFORE geometry.
        val motionQuality=if(external.isNotEmpty())
            VioMotionQuality.inspect(external) else null
        if(motionQuality!=null){
            File(folder,"openvins_vio_motion_audit.json").writeText(
                JSONObject().put("source","Native OpenVINS coarse drift plausibility")
                    .put("acceptable_for_experimental_geometry",
                        motionQuality.acceptableForExperimentalGeometry)
                    .put("metric_calibration_verified",false)
                    .put("duration_s",motionQuality.durationSeconds)
                    .put("path_length_unvalidated",
                        motionQuality.pathLengthUnvalidated)
                    .put("displacement_unvalidated",
                        motionQuality.displacementUnvalidated)
                    .put("median_speed_unvalidated",
                        motionQuality.medianSpeedUnitsPerSecond)
                    .put("p90_speed_unvalidated",
                        motionQuality.p90SpeedUnitsPerSecond)
                    .put("maximum_speed_unvalidated",
                        motionQuality.maxSpeedUnitsPerSecond)
                    .put("rejection_reasons",JSONArray(motionQuality.reasons))
                    .toString(2))
            if(!motionQuality.acceptableForExperimentalGeometry)
                warnings+="VIO-DRIFT: Mehrbild-Fusion gesperrt: "+
                    motionQuality.reasons.joinToString(" · ")
        }
        if(photos.isEmpty())warnings+="Keine hochauflösenden Fotos vorhanden"
        // User needs per-image evaluation, including frames recorded before
        // MSCKF initialization. Sequentially run DA3 on EVERY saved RGB JPEG;
        // only timestamp-matched frames may contribute to the diagnostic map.
        val selected=photos
        if(external.isNotEmpty())
            warnings+="DA3 analysiert ALLE "+selected.size+
                " RGB-Fotos; Bilder ohne VIO-Pose bleiben einzeln durchsuchbar"
        val cameraEvidence=if(external.isNotEmpty())
            OpenVinsCameraEvidence.read(session) else null
        val initialCalibration=if(nativeCapture)
            OpenVinsDa3Fusion.calibration(session) else null
        val onlineQuality=if(external.isNotEmpty())
            OpenVinsOnlineCalibrationHealth.inspect(external,initialCalibration)
            else null
        // JSONObject rejects NaN/Infinity; missing old online states must
        // remain a readable JSON diagnostic instead of aborting inference.
        fun safeNumber(value:Double):Any =
            if(value.isFinite())value else JSONObject.NULL
        if(onlineQuality!=null){
            File(folder,"openvins_online_calibration_audit.json").writeText(JSONObject()
                .put("source","Native OpenVINS camera state at each VIO timestamp")
                .put("online_state_count",onlineQuality.samples)
                .put("settled_for_experimental_fusion",
                    onlineQuality.acceptedForExperimentalFusion)
                .put("max_focal_variation_percent",
                    safeNumber(onlineQuality.maxFocalVariationPercent))
                .put("max_extrinsic_rotation_variation_degrees",
                    safeNumber(onlineQuality.maxExtrinsicRotationVariationDegrees))
                .put("max_extrinsic_translation_variation",
                    safeNumber(onlineQuality.maxExtrinsicTranslationVariation))
                .put("max_time_offset_variation_ms",
                    safeNumber(onlineQuality.maxTimeOffsetVariationMs))
                .put("median_time_offset_ms",
                    safeNumber(onlineQuality.medianTimeOffsetMs))
                .put("metric_calibration_verified",false)
                .put("rejection_reasons",JSONArray(onlineQuality.reasons))
                .toString(2))
            if(!onlineQuality.acceptedForExperimentalFusion)
                warnings+="OpenVINS-Online-Kalibrierung nicht stabil genug: "+
                    onlineQuality.reasons.take(3).joinToString(" · ")
        }
        val cameraCalibration=if(
            cameraEvidence?.allowedForExperimentalFusion==true &&
            onlineQuality?.acceptedForExperimentalFusion==true &&
            motionQuality?.acceptableForExperimentalGeometry==true)
            initialCalibration else null
        val seedText=File(session,
            "external_calibration/PIXEL_CALIBRATION_WARNING.txt")
            .takeIf{it.isFile && it.length() in 40..128_000}?.readText()
        // PRIMARY_CAMERA references do not certify the camera-gyro lever
        // arm. For stable hardware+VIO we may display *only* real ORB
        // triangulations as a visibly marked, in-memory research preview.
        val previewCalibration=if(initialCalibration!=null &&
            OpenVinsSparsePreviewGate.allowed(
                cameraEvidence,onlineQuality,seedText) &&
            motionQuality?.acceptableForExperimentalGeometry==true)
            initialCalibration else null
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
            warnings+="Kalibrierte DA3-Raumkarte gesperrt; "+
                "stattdessen relative farbige 3D-Einzelbildvorschau"
        // Native ImageReader JPEGs are sensor-coordinate pixels. Align DA3
        // input orientation using measured IMU gravity and the recorded
        // camera<-IMU extrinsic, NEVER the landscape-locked UI orientation.
        val imu=if(nativeCapture)
            OpenVinsImageOrientation.readImu(session) else emptyList()
        if(external.isNotEmpty()){
            val imuAudit=OpenVinsImuQualityAudit.inspect(imu)
            File(folder,"openvins_imu_quality_audit.json").writeText(
                JSONObject()
                    .put("source","raw Android gyro and accelerometer")
                    .put("samples",imuAudit.samples)
                    .put("median_sample_hz",imuAudit.medianHz)
                    .put("max_timestamp_gap_ms",imuAudit.maxGapMs)
                    .put("stationary_windows",imuAudit.stationaryWindows)
                    .put("checked_windows",imuAudit.checkedWindows)
                    .put("stationary_gyro_mean_rad_s",
                        JSONArray(imuAudit.stationaryGyroBiasRadS))
                    .put("stationary_acceleration_norm",
                        imuAudit.stationaryAccelerationMagnitude
                            ?:JSONObject.NULL)
                    .put("camera_imu_extrinsics_verified",false)
                    .put("imu_noise_density_calibrated",false)
                    .put("gyro_bias_complete_calibration",false)
                    .put("warnings",JSONArray(imuAudit.warnings))
                    .toString(2))
            warnings+=imuAudit.warnings
        }
        val rotationMatrix=if(nativeCapture)
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
        var relativePreview=emptyList<SurveyDepth>()
        val candidateFeatures=ArrayList<OpenVinsDa3MultiView.Frame>()
        val candidateRays=HashMap<Int,List<OpenVinsDa3Fusion.RaySample>>()
        // Separate research-only, pose-projected rays even when strict
        // gyroscope/extrinsic evidence blocks the certified map namespace.
        val diagnosticWorldFrames=
            ArrayList<OpenVinsDa3DiagnosticWorld.Frame>()
        var fusedFrames=0
        var done=0;var paired=0
        var insufficientRgbForGeometry=0
        var minimumRgbMedian=255
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
                // Interpolate the VIO 6DoF camera pose to the exact RGB shutter timestamp.
                val photoPose=if(external.isNotEmpty())
                    ExternalVioTrajectory.interpolate(external,ts) else null
                val online=photoPose?.onlineCamera
                    ?:if(position>=0 && external.isNotEmpty())
                        external[position].onlineCamera else null
                val jpegBounds=android.graphics.BitmapFactory.Options().apply{
                    inJustDecodeBounds=true
                }
                android.graphics.BitmapFactory.decodeByteArray(
                    jpeg,0,jpeg.size,jpegBounds)
                val sourceWidth=jpegBounds.outWidth
                val sourceHeight=jpegBounds.outHeight
                // The uploaded 22:03 Pixel run was almost black and VIO
                // dropped to ~15 Hz. Measure original sensor JPEG quality
                // BEFORE AI crop/model brightness normalization.
                val rgbQuality=try{
                    OpenVinsRgbCaptureQuality.inspectJpeg(jpeg)
                }catch(_:Exception){null}
                if(rgbQuality?.usableForMultiView==false)
                    insufficientRgbForGeometry++
                if(rgbQuality!=null)
                    minimumRgbMedian=minOf(minimumRgbMedian,
                        rgbQuality.medianLuma)
                val correctedRotation=online?.rotationCamFromImu()
                    ?:rotationMatrix
                // Online temporal calibration relates the clocks by
                // t_IMU=t_CAMERA+dt; querying raw IMU at the camera clock
                // silently causes a different scene-upright rotation.
                val imuQueryTs=if(online!=null)
                    OpenVinsImageOrientation.imuTimestampNs(
                        ts,online.timeOffsetSeconds) else ts
                val imageOrientation=if(imuQueryTs!=null)
                    correctedRotation?.let{
                        OpenVinsImageOrientation.estimate(imu,imuQueryTs,it)
                    } else null
                // OpenVINS optimizes K, R_ItoC and sensor time offset
                // AFTER startup. Use its SAME-POSE state, not a stale
                // session-start Camera2 seed, to project the RGB keyframe.
                val baseCalibration=cameraCalibration ?: previewCalibration
                val frameCalibration=if(baseCalibration!=null &&
                    online!=null && kotlin.math.abs(online.timeOffsetSeconds)<.10)
                    online.cameraCalibration(baseCalibration)
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
                if(photoPose!=null || (external.isEmpty() && position>=0))paired++
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
                val geometryGateReason=when{
                    external.isEmpty() -> "Keine native OpenVINS-Kamerapose"
                    motionQuality?.acceptableForExperimentalGeometry!=true ->
                        "VIO-Trajektorie physikalisch unplausibel"
                    photoPose==null -> "Keine interpolierbare VIO-Pose zum Belichtungszeitpunkt"
                    rgbQuality?.usableForMultiView==false ->
                        "Zu dunkles oder unstrukturiertes RGB für zuverlässige Mehrbildmerkmale"
                    cameraCalibration==null && previewCalibration==null ->
                        "Kamera-IMU-Kalibrierung nicht hinreichend bestätigt"
                    frameCalibration==null -> "Online-Kamera-Kalibrierung fehlt"
                    imageOrientation?.usableForFusion!=true ->
                        "Geräteausrichtung/IMU-Schwerkraft unsicher"
                    inference.cropPercent>=.70f ->
                        "Zu viel Kamerabild im DA3-Crop verloren"
                    good.size<100 -> "KI-Tiefenkarte enthält zu wenige Werte"
                    else -> "Geometrische ORB-Merkmale werden geprüft"
                }
                // Cache each real-colored 2.5D DA3 preview on disk. This
                // supports scrubbing through ALL images without rerunning
                // a heavyweight on-device neural network.
                val cloudName="relative_%04d.rgbxyz".format(i)
                var relativeCount=0
                var planeAudit:OpenVinsPlanarityAudit.Report?=null
                if(good.size>=100)try{
                    val points=OpenVinsDa3RelativePreview.make(
                        inference,frameCalibration ?:initialCalibration,
                        sourceWidth,sourceHeight)
                    if(points.size>=50){
                        planeAudit=OpenVinsPlanarityAudit.inspect(points)
                        OpenVinsRgbCloudCache.write(folder,cloudName,points)
                        relativeCount=points.size
                        if(relativePreview.isEmpty()){
                            relativePreview=points
                            onRelativePreview(points)
                        }
                    }
                }catch(t:Throwable){
                    warnings+="RGB-Tiefenvorschau Foto "+(i+1)+": "+
                        (t.message?:t.javaClass.simpleName)
                }
                // For research purposes, back-project each real RGB image
                // with its own nearest native VIO camera pose and Camera2
                // intrinsics, EVEN IF the gyro baseline cannot be certified.
                // It is a visible, nonmetric diagnostic, NOT a stored map.
                val approximateCamera=frameCalibration ?:initialCalibration
                if(motionQuality?.acceptableForExperimentalGeometry==true &&
                    approximateCamera!=null && photoPose!=null &&
                    good.size>=100)try{
                    if(approximateCamera.compatibleWith(
                            sourceWidth,sourceHeight)){
                        val rays=OpenVinsDa3Fusion.sampleUnscaled(
                            inference,photoPose,approximateCamera,
                            sourceWidth,sourceHeight,lo,hi)
                        if(rays.size>=50)
                            diagnosticWorldFrames.add(
                                OpenVinsDa3DiagnosticWorld.Frame(
                                    i,position,rays,lo,hi,
                                    imageOrientation?.usableForFusion==true))
                    }
                }catch(t:Throwable){
                    warnings+="OpenVINS-Projektion Foto "+(i+1)+": "+
                        (t.message?:t.javaClass.simpleName)
                }
                // Keep sparse ORB features plus ~2k true RGB camera rays
                // per frame. Do NOT apply an invented median depth scale.
                // Fit DIRECT relative-depth scale/shift against actual optical Z
                // from triangulated native VIO camera baselines.
                if(frameCalibration!=null && photoPose!=null &&
                    rgbQuality?.usableForMultiView!=false &&
                    imageOrientation?.usableForFusion==true &&
                    inference.cropPercent<.70f && good.size>=100){
                    try {
                        if(frameCalibration.compatibleWith(
                                sourceWidth,sourceHeight)){
                            val frame=OpenVinsDa3MultiView.extract(i,
                                inference.image,inference.relativeDepth,
                                photoPose,frameCalibration,
                                sourceWidth,sourceHeight,
                                inference.rotationDegrees)
                            if(frame!=null){
                                candidateFeatures.add(frame)
                                // Do not use uncalibrated ORB previews as DA3
                                // RGB-D room maps. This gate remains strict.
                                if(cameraCalibration!=null ||
                                    previewCalibration!=null){
                                    val rays=OpenVinsDa3Fusion.sampleUnscaled(
                                        inference,photoPose,frameCalibration,
                                        sourceWidth,sourceHeight,lo,hi)
                                    if(rays.isNotEmpty())candidateRays[i]=rays
                                }
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
                    .put("da3_output_kind","affine_invariant_relative_depth")
                    .put("raw_depth_reciprocal_applied",false)
                    .put("rgb_median_luma_0_255",
                        rgbQuality?.medianLuma?:JSONObject.NULL)
                    .put("rgb_near_black_fraction",
                        rgbQuality?.nearBlackFraction?:JSONObject.NULL)
                    .put("rgb_gradient_contrast",
                        rgbQuality?.gradientContrast?:JSONObject.NULL)
                    .put("rgb_usable_for_multiview",
                        rgbQuality?.usableForMultiView?:false)
                    .put("rgb_quality_warnings",
                        JSONArray(rgbQuality?.warnings?:emptyList<String>()))
                    .put("effective_camera_intrinsics_used_for_preview",
                        (frameCalibration?:initialCalibration)?.compatibleWith(
                            sourceWidth,sourceHeight)?:false)
                    .put("pose_interpolated_to_shutter_timestamp",
                        photoPose!=null)
                    .put("relative_rgb_cloud_file",
                        if(relativeCount>0)cloudName else JSONObject.NULL)
                    .put("relative_rgb_cloud_points",relativeCount)
                    .put("dominant_plane_support_fraction",
                        planeAudit?.dominantPlaneFraction?:JSONObject.NULL)
                    .put("dominant_plane_median_relative_residual",
                        planeAudit?.medianResidualToSpan?.takeIf{it.isFinite()}
                            ?:JSONObject.NULL)
                    .put("dominant_plane_is_reference_ground_truth",false)
                    .put("diagnostic_world_pose_projected",
                        diagnosticWorldFrames.any{it.photoIndex==i})
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
                    .put("imu_gravity_query_timestamp_ns",
                        imuQueryTs ?: JSONObject.NULL)
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
                    .put("geometry_gate_reason",geometryGateReason)
                    .put("orb_multiview_candidate",
                        candidateFeatures.any{it.photoIndex==i})
                    .put("unscaled_rgb_rays",
                        candidateRays[i]?.size?:0)
                    .put("optical_pose_index",position)
                    .put("optical_pose_valid",
                        if(external.isNotEmpty()) position>=0
                        else matched?.accepted?:false)
                    .put("pose_source",if(external.isNotEmpty())
                        "OpenVINS native MSCKF, unvalidated"
                        else if (nativeCapture) "unavailable"
                        else "OpenCV relative monocular VO")
                    .put("external_camera_position_unvalidated",
                        photoPose?.let{JSONArray(it.center().toList())}
                            ?:JSONObject.NULL)
                    .put("pose_metric",false))
                last?.recycle()
                last=inference.depthVisual.copy(
                    android.graphics.Bitmap.Config.ARGB_8888,false)
                inference.depthVisual.recycle()
                inference.image.recycle()
                done++
            }
        }finally{ai?.close()}
        if(insufficientRgbForGeometry>0)
            warnings+=insufficientRgbForGeometry.toString()+
                " RGB-Fotos unterbelichtet/strukturschwach: "+
                "nur Einzelbild-DA3, keine ORB-Kartenfusion. "+
                "Bitte heller aufnehmen (schlechtester Median "+
                minimumRgbMedian+"/255)."
        var fusedPoints=0
        var alignedFrames=0
        var matchedFeatures=0
        var triangulatedFeatures=0
        var pairCandidates=0
        var sparsePreview=emptyList<SurveyDepth>()
        val fits=try{
            if(candidateFeatures.size>=2){
                update("3/3 · ORB-Mehrbildmerkmale triangulieren und DA3 "+
                    "Skala/Offset an OpenVINS ausrichten …")
                val alignment=OpenVinsDa3MultiView.align(candidateFeatures)
                matchedFeatures=alignment.matchedFeatures
                triangulatedFeatures=alignment.triangulatedFeatures
                pairCandidates=alignment.pairsConsidered
                sparsePreview=alignment.diagnosticPoints
                alignment.fits
            }else emptyMap()
        }catch(t:Throwable){
            warnings+="Geometrischer Tiefenabgleich fehlgeschlagen: "+
                (t.message?:t.javaClass.simpleName)
            emptyMap()
        }finally{
            candidateFeatures.forEach{it.close()}
        }
        val worldDiagnostic=OpenVinsDa3DiagnosticWorld.assemble(
            diagnosticWorldFrames,fits,
            useGeometricFits=motionQuality?.acceptableForExperimentalGeometry==true)
        val worldDiagnosticPoints=worldDiagnostic.points
        if(worldDiagnosticPoints.isNotEmpty()){
            OpenVinsRgbCloudCache.write(folder,
                "vio_world_diagnostic.rgbxyz",worldDiagnosticPoints)
            warnings+="Gesamtansicht: "+worldDiagnostic.frames+
                " RGB-Aufnahmen per OpenVINS-Pose verortet; "+
                worldDiagnostic.geometryAlignedFrames+
                " geometrisch tiefenangepasst, "+
                worldDiagnostic.approximateFrames+
                " mit NUR approximativer Tiefe (kein Maßstab)"
        }else if(motionQuality?.acceptableForExperimentalGeometry==true)
            warnings+="Keine RGB-Bilder zeitlich/geometrisch zur "+
                "OpenVINS-Trajektorie zuordnungsfähig"
        val mapSamples=ArrayList<SurveyDepth>()
        val alignedFrameClouds=ArrayList<OpenVinsMultiViewAgreement.Input>()
        var alignedRgbCaches=0
        var multiViewResearchPreview=emptyList<SurveyDepth>()
        val quality=JSONArray()
        for((index,rays) in candidateRays.toSortedMap()){
            val fit=fits[index] ?: continue
            val fused=OpenVinsDa3Fusion.align(rays,fit)
            if(fused.size>=50){
                alignedFrameClouds.add(
                    OpenVinsMultiViewAgreement.Input(index,fused))
                alignedFrames++
                // Keep the *real* ORB triangulation-calibrated world points
                // for per-frame before/after inspection, even if the strict
                // room-map gate later rejects the run. No fake depths.
                val alignedName="aligned_%04d.rgbxyz".format(index)
                try {
                    OpenVinsRgbCloudCache.write(folder,alignedName,fused)
                    alignedRgbCaches++
                    val alignedPlane=OpenVinsPlanarityAudit.inspect(fused)
                    for(manifestIdx in 0 until manifest.length()){
                        val entry=manifest.optJSONObject(manifestIdx)?:continue
                        if(entry.optInt("source_photo_index",-1)==index){
                            entry.put("aligned_rgb_cloud_file",alignedName)
                                .put("aligned_rgb_cloud_points",fused.size)
                                .put("aligned_rgb_cloud_is_metric_verified",false)
                                .put("aligned_plane_support_fraction",
                                    alignedPlane.dominantPlaneFraction)
                                .put("aligned_plane_median_relative_residual",
                                    alignedPlane.medianResidualToSpan
                                        .takeIf{it.isFinite()}
                                        ?:JSONObject.NULL)
                            break
                        }
                    }
                }catch(t:Throwable){
                    warnings+="ORB RGB-Einzelbildwolke "+(index+1)+
                        " nicht zwischenspeicherbar: "+
                        (t.message?:t.javaClass.simpleName)
                }
            }
            quality.put(JSONObject().put("photo_index",index)
                .put("matched_triangular_correspondences",fit.observations)
                .put("inliers",fit.inliers)
                .put("median_relative_error",fit.medianRelativeError)
                .put("relative_depth_scale_vio",fit.scale)
                .put("relative_depth_shift_vio",fit.shift)
                .put("coverage_quadrants",fit.coverageQuadrants)
                .put("aligned_3d_points",fused.size))
            for(manifestIdx in 0 until manifest.length()){
                val entry=manifest.optJSONObject(manifestIdx) ?: continue
                if(entry.optInt("source_photo_index",-1)==index)
                    entry.put("direct_relative_depth_alignment",JSONObject()
                        .put("scale",fit.scale).put("shift",fit.shift)
                        .put("inliers",fit.inliers)
                        .put("median_relative_error",fit.medianRelativeError))
            }
        }
        // A world point must be supported by at least TWO DISTINCT photos.
        // Mere concatenation of individually aligned maps is not fusion.
        val overlapAgreement=OpenVinsMultiViewAgreement.verify(
            alignedFrameClouds)
        if(overlapAgreement.accepted){
            mapSamples.addAll(overlapAgreement.points)
            fusedFrames=overlapAgreement.supportedFrames
        }else if(alignedFrameClouds.isNotEmpty()){
            warnings+="Mehrbild-Punktwolke wegen fehlender räumlicher "+
                "Überdeckung gesperrt: "+overlapAgreement.supported+
                "/"+overlapAgreement.tested+" geometrisch konsistente RGB-Punkte"
        }
        if(mapSamples.size>=150 && overlapAgreement.accepted &&
            alignedFrames>=2 && external.isNotEmpty()){
            if(cameraCalibration==null){
                // Only a transient diagnostic cloud from real multi-view
                // DA3 correspondences, never a stored calibrated room scan.
                multiViewResearchPreview=mapSamples.take(60_000)
                warnings+="Mehrbild-DA3-Farbpunkte als UNKALIBRIERTE "+
                    "3D-Forschungsvorschau (nicht gespeichert)"
            }else try{
                update("3/3 · Verifizierte OpenVINS/DA3-Punkte fusionieren …")
                val output=RoomScanStore(context,"rflab_openvins_scan_v1.json")
                output.start("OpenVINS + DA3 " + suffix)
                output.poseSource="OpenVINS native MSCKF + DA3 aligned relative "+
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
        if(sparsePreview.isNotEmpty() && cameraCalibration==null)
            warnings+="ORB-Farbmerkmale trianguliert: "+
                sparsePreview.size+" nur in unkalibrierter 3D-Diagnose, "+
                "nicht in der persistenten DA3-Raumkarte gespeichert"
        // Track exact processing-stage rejection frequencies; users should
        // see why their RGB/depth was not promoted into a room cloud.
        val stageRejections=JSONObject()
        for(index in 0 until manifest.length()){
            val reason=manifest.optJSONObject(index)
                ?.optString("geometry_gate_reason") ?:continue
            stageRejections.put(reason,stageRejections.optInt(reason,0)+1)
        }
        File(folder,"openvins_da3_map_summary.json").writeText(
            JSONObject().put("source","Native OpenVINS + DA3 Small")
                .put("per_frame_geometry_gate_counts",stageRejections)
                .put("orb_registered_keyframes",candidateRays.size)
                .put("individual_orb_aligned_rgb_clouds",alignedRgbCaches)
                .put("individual_orb_aligned_clouds_metric_verified",false)
                .put("multiview_overlap_support_fraction",
                    overlapAgreement.fraction)
                .put("multiview_overlap_tested_points",
                    overlapAgreement.tested)
                .put("multiview_overlap_supported_points",
                    overlapAgreement.supported)
                .put("multiview_overlap_accepted",
                    overlapAgreement.accepted)
                .put("sparse_orb_preview_points",sparsePreview.size)
                .put("relative_single_image_preview_points",
                    relativePreview.size)
                .put("cached_individual_rgb_clouds",done)
                .put("world_diagnostic_file",
                    if(worldDiagnosticPoints.isNotEmpty())
                        "vio_world_diagnostic.rgbxyz" else JSONObject.NULL)
                .put("world_diagnostic_frames",worldDiagnostic.frames)
                .put("world_diagnostic_points",worldDiagnosticPoints.size)
                .put("world_diagnostic_aligned_frames",
                    worldDiagnostic.geometryAlignedFrames)
                .put("world_diagnostic_approximate_frames",
                    worldDiagnostic.approximateFrames)
                .put("world_diagnostic_unreliable_rotation_frames",
                    worldDiagnostic.unreliableRotationFrames)
                .put("world_diagnostic_nonmetric",true)
                .put("multiview_research_preview_points",
                    multiViewResearchPreview.size)
                .put("vio_motion_gate_passed",
                    motionQuality?.acceptableForExperimentalGeometry?:false)
                .put("vio_motion_rejection_reasons",
                    JSONArray(motionQuality?.reasons?:emptyList<String>()))
                .put("sparse_preview_unvalidated_not_persisted",true)
                .put("sparse_preview_gate_passed",
                    previewCalibration!=null)
                .put("namespace","rflab_openvins_scan_v1.json")
                .put("method","reciprocal ORB, native VIO ray triangulation, "+
                    "robust direct relative-depth scale/shift, RGB voxel fusion")
                .put("selected_da3_rgb_frames",selected.size)
                .put("rgb_frames_rejected_for_low_light_or_no_structure",
                    insufficientRgbForGeometry)
                .put("rgb_median_luma_minimum",
                    if(minimumRgbMedian<255)minimumRgbMedian else JSONObject.NULL)
                .put("rgb_frames_with_associated_vio_pose",paired)
                .put("registered_keyframes",alignedFrames)
                .put("colored_points",fusedPoints)
                .put("matches_checked",matchedFeatures)
                .put("triangulated_correspondences",triangulatedFeatures)
                .put("pairs_considered",pairCandidates)
                .put("aligned_keyframe_diagnostics",quality)
                .put("depth_kind","affine-invariant relative depth; direct affine scale+shift")
                .put("multiview_scale_shift_aligned",alignedFrames>=2)
                .put("native_pose_calibration_verified",false)
                .put("camera_geometry_capture_result_gate_passed",
                    cameraEvidence?.allowedForExperimentalFusion?:false)
                .put("online_calibration_stable",
                    onlineQuality?.acceptedForExperimentalFusion?:false)
                .put("metric_accuracy_verified",false)
                .put("loop_closure",false)
                .put("mesh",false)
                .toString(2))
        File(folder,"manifest.json").writeText(JSONObject()
            .put("schema","rflab-offline-da3-pose-bundle-v1")
            .put("app_version",BuildConfig.VERSION_NAME)
            .put("app_version_code",BuildConfig.VERSION_CODE)
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
            .put("multiview_overlap_support_fraction",
                overlapAgreement.fraction)
            .put("multiview_overlap_supported_points",
                overlapAgreement.supported)
            .put("multiview_overlap_accepted",
                overlapAgreement.accepted)
            .put("sparse_orb_preview_points",sparsePreview.size)
            .put("per_frame_geometry_gate_counts",stageRejections)
            .put("relative_single_image_preview_points",relativePreview.size)
            .put("world_diagnostic_frames",worldDiagnostic.frames)
            .put("world_diagnostic_points",worldDiagnosticPoints.size)
            .put("world_diagnostic_aligned_frames",
                worldDiagnostic.geometryAlignedFrames)
            .put("world_diagnostic_approximate_frames",
                worldDiagnostic.approximateFrames)
            .put("world_diagnostic_nonmetric",true)
            .put("multiview_research_preview_points",
                multiViewResearchPreview.size)
            .put("vio_motion_gate_passed",
                motionQuality?.acceptableForExperimentalGeometry?:false)
            .put("sparse_preview_unvalidated_not_persisted",true)
            .put("camera_geometry_gate_passed",
                cameraEvidence?.allowedForExperimentalFusion?:false)
            .put("online_calibration_stable",
                onlineQuality?.acceptedForExperimentalFusion?:false)
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
        update("Fertig: "+done+" DA3-Bilder einzeln abrufbar · "+
            worldDiagnostic.frames+" im VIO-Weltraum · "+
            worldDiagnosticPoints.size+" Forschungs-Farbpunkte · "+
            fusedPoints+" qualitätsgeprüfte Farbpunkte")
        return Summary(folder,
            if(external.isNotEmpty()) external.size else poses?.poses?.size?:0,
            poses?.inliers?:0,done,paired,poses?.rejected?:0,
            last,poses,warnings,external,fusedPoints,fusedFrames,
            sparsePreview,relativePreview,multiViewResearchPreview,motionQuality,
            worldDiagnosticPoints,worldDiagnostic.frames,
            worldDiagnostic.geometryAlignedFrames,
            worldDiagnostic.approximateFrames)
    }
}
