package dev.rflab.scanner

import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Independent offline VISUAL odometry, with measured gyro consistency.
 * Real ORB correspondences and 5-point Essential-Matrix RANSAC recover
 * relative 6DoF poses. Translation is UP-TO-UNKNOWN SCALE, not metres.
 *
 * This is a necessary VO building block, NOT yet calibrated full visual-
 * inertial SLAM / joint optimization / bundle adjustment / loop closure.
 */
internal class OfflineVisualOdometry {
    data class Intrinsics(val fx:Double,val fy:Double,val cx:Double,
                          val cy:Double,val approximate:Boolean)
    data class Pose(val timestampNs:Long,val rotation:DoubleArray,
                    val translation:DoubleArray,val accepted:Boolean,
                    val inliers:Int,val matches:Int,val gyroRad:Double?,
                    val opticalRad:Double?,val note:String){
        fun center():DoubleArray{
            val r=rotation;val t=translation
            return doubleArrayOf(
                -(r[0]*t[0]+r[3]*t[1]+r[6]*t[2]),
                -(r[1]*t[0]+r[4]*t[1]+r[7]*t[2]),
                -(r[2]*t[0]+r[5]*t[1]+r[8]*t[2]))
        }
    }
    data class Result(val poses:List<Pose>,val inliers:Int,val rejected:Int,
                      val gyroPairs:Int,val gyroFusedPairs:Int,
                      val calibration:Intrinsics,
                      val loopCandidates:Int,val warnings:List<String>){
        fun json():JSONObject{
            val o=JSONObject()
                .put("schema","rflab-offline-visual-odometry-v1")
                .put("source","Camera2 grayscale + OpenCV ORB/RANSAC")
                .put("gyro_angular_check_pairs",gyroPairs)
                .put("gyro_rotation_prior_applied_pairs",gyroFusedPairs)
                .put("valid_motion_pairs",inliers)
                .put("rejected_motion_pairs",rejected)
                .put("loop_candidates_unoptimized",loopCandidates)
                .put("metric_scale",false)
                .put("bundle_adjustment",false)
                .put("loop_closure_optimized",false)
                .put("camera_imu_extrinsic_calibrated",false)
                .put("position_units","relative direction steps; NOT metres")
                .put("not_safe_for_metric_mesh",true)
                .put("intrinsics_approximate",calibration.approximate)
                .put("camera_matrix",JSONArray().put(calibration.fx)
                    .put(calibration.fy).put(calibration.cx).put(calibration.cy))
                .put("warnings",JSONArray(warnings))
            val out=JSONArray()
            poses.forEach{p->
                out.put(JSONObject().put("timestamp_ns",p.timestampNs)
                    .put("rotation_cw",JSONArray(p.rotation.toList()))
                    .put("translation_cw_relative",JSONArray(p.translation.toList()))
                    .put("center_relative",JSONArray(p.center().toList()))
                    .put("accepted",p.accepted)
                    .put("feature_matches",p.matches).put("inliers",p.inliers)
                    .put("gyro_angle_rad",p.gyroRad)
                    .put("visual_angle_rad",p.opticalRad).put("status",p.note))
            }
            return o.put("poses",out)
        }
    }
    companion object {
        fun intrinsics(dir:File,w:Int,h:Int):Intrinsics{
            val data=runCatching{JSONObject(File(dir,"camera.json").readText())}
                .getOrNull()
            val active=data?.optJSONArray("pre_correction_active_array")
                ?:data?.optJSONArray("active_array")
            val lens=data?.optJSONArray("lens_intrinsics_active_array_px")
            if(active!=null && lens!=null && lens.length()>=4 &&
                active.length()>=4){
                val k=CameraIntrinsicsCrop.approximate(
                    DoubleArray(4){lens.optDouble(it)},
                    DoubleArray(4){active.optDouble(it)},w,h)
                if(k!=null){
                    return Intrinsics(k.fx,k.fy,k.cx,k.cy,true)
                }
            }
            return Intrinsics(w*.85,h*1.13,w/2.0,h/2.0,true)
        }
    }

    private fun id()=doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
    private fun product(a:DoubleArray,b:DoubleArray):DoubleArray {
        val out=DoubleArray(9)
        for(i in 0..2)for(j in 0..2)for(k in 0..2)
            out[i*3+j]+=a[i*3+k]*b[k*3+j]
        return out
    }
    private fun rotate(r:DoubleArray,t:DoubleArray)=DoubleArray(3){i->
        r[i*3]*t[0]+r[i*3+1]*t[1]+r[i*3+2]*t[2]
    }
    private fun toArray(m:Mat)=DoubleArray(9){m.get(it/3,it%3)[0]}
    private fun angle(r:DoubleArray)=acos(
        ((r[0]+r[4]+r[8]-1)/2).coerceIn(-1.0,1.0))

    fun estimate(dir:File, progress:(String)->Unit = {}):Result {
        check(OpenCVLoader.initDebug()){"Native OpenCV library unavailable"}
        val frames=MotionFrameStore.read(dir,step=3,limit=350)
        require(frames.size>=4){
            "Mindestens einige Sekunden Kamerabildstrom aufnehmen"
        }
        val calibration=intrinsics(dir,frames[0].width,frames[0].height)
        val k=Mat.eye(3,3,CvType.CV_64F)
        k.put(0,0,calibration.fx);k.put(1,1,calibration.fy)
        k.put(0,2,calibration.cx);k.put(1,2,calibration.cy)
        val orb=ORB.create(900)
        val matcher=BFMatcher.create(Core.NORM_HAMMING,false)
        val comparable=runCatching {
            JSONObject(File(dir,"camera.json").readText())
                .optString("camera_timestamp_source")=="REALTIME"
        }.getOrDefault(false)
        val gyro=if(comparable)GyroAngleStream.fromSession(dir)
            else GyroAngleStream.empty()
        val metadata=runCatching {
            JSONObject(File(dir,"camera.json").readText())
        }.getOrNull()
        val poseReference=metadata?.optInt("lens_pose_reference",-1)?:-1
        val quaternion=metadata?.optJSONArray(
            "lens_pose_sensor_to_camera_quaternion_xyzw")
        val calibratedLens=if(poseReference>=0 &&
            poseReference!=
                android.hardware.camera2.CameraMetadata.LENS_POSE_REFERENCE_UNDEFINED &&
            quaternion!=null&&quaternion.length()==4)
            DoubleArray(4){quaternion.optDouble(it)} else null
        var R=id()
        var t=doubleArrayOf(0.0,0.0,0.0)
        var prev:MotionFrameStore.Frame?=null
        var prevKp:MatOfKeyPoint?=null
        var prevDesc:Mat?=null
        var accepted=0;var rejected=0;var gyroPairs=0
        var gyroFusedPairs=0
        var loops=0
        val keyDescriptors=ArrayList<Mat>()
        val poses=ArrayList<Pose>(frames.size)
        try {
            for((idx,frame) in frames.withIndex()){
                val gray=Mat(frame.height,frame.width,CvType.CV_8UC1)
                gray.put(0,0,frame.gray)
                val points=MatOfKeyPoint()
                val desc=Mat()
                try{orb.detectAndCompute(gray,Mat(),points,desc)}
                finally{gray.release()}
                var count=0;var good=0;var ok=false
                var observedAngle:Double?=null
                val inertial=prev?.let {
                    gyro.angleBetween(it.timestampNs,frame.timestampNs)
                }
                var status="First reference"
                val previous=prev
                if(previous!=null && prevDesc!=null &&
                    !prevDesc!!.empty() && !desc.empty()){
                    val knn=ArrayList<MatOfDMatch>()
                    matcher.knnMatch(prevDesc,desc,knn,2)
                    val from=prevKp!!.toArray();val to=points.toArray()
                    val p=ArrayList<Point>();val q=ArrayList<Point>()
                    for(group in knn) {
                        val pair=group.toArray()
                        if(pair.size>=2 && pair[0].distance<.72f*pair[1].distance &&
                            pair[0].queryIdx in from.indices &&
                            pair[0].trainIdx in to.indices){
                            p.add(from[pair[0].queryIdx].pt)
                            q.add(to[pair[0].trainIdx].pt)
                        }
                        group.release()
                    }
                    count=p.size
                    if(count>=18 &&
                        frame.timestampNs-previous.timestampNs in
                            1L..2_000_000_000L){
                        val p1=MatOfPoint2f(*p.toTypedArray())
                        val p2=MatOfPoint2f(*q.toTypedArray())
                        val mask=Mat()
                        val E=Calib3d.findEssentialMat(p1,p2,k,
                            Calib3d.RANSAC,.999,1.5,mask)
                        if(!E.empty()&&E.rows()==3){
                            val relR=Mat();val relT=Mat()
                            try{
                                good=Calib3d.recoverPose(E,p1,p2,k,
                                    relR,relT,mask)
                                val visualR=toArray(relR)
                                observedAngle=angle(visualR)
                                val gyroR=if(calibratedLens!=null)
                                    gyro.cameraRotationBetween(
                                        previous.timestampNs,frame.timestampNs,
                                        calibratedLens) else null
                                // Apply calibrated IMU rotation only when visual
                                // essential RANSAC agrees within 9 degrees.
                                // If they disagree, trust the optical estimate
                                // and retain the disagreement for diagnostics.
                                val consistency=gyroR?.let { g ->
                                    val delta=DoubleArray(9)
                                    for(row in 0..2)for(col in 0..2)
                                        for(k2 in 0..2)
                                            delta[row*3+col]+=
                                                visualR[row*3+k2]*g[col*3+k2]
                                    angle(delta)
                                }
                                val useGyro=gyroR!=null &&
                                    consistency!=null && consistency<.16
                                val rot=if(useGyro)gyroR!! else visualR
                                val displacements=p.indices.map {
                                    hypot(p[it].x-q[it].x,p[it].y-q[it].y)
                                }.sorted()
                                val median=displacements[displacements.size/2]
                                if(good>=15&&good.toDouble()/count>=.38 &&
                                    median in 1.5..110.0){
                                    R=product(rot,R)
                                    if(useGyro)gyroFusedPairs++
                                    val shifted=rotate(rot,t)
                                    t=DoubleArray(3){i->
                                        shifted[i]+relT.get(i,0)[0]
                                    }
                                    ok=true;accepted++
                                    status="Valid relative pose; scale unknown"
                                    if(inertial!=null)gyroPairs++
                                }else {rejected++;status="Low parallax/inliers"}
                            }finally{relR.release();relT.release()}
                        } else {rejected++;status="Essential matrix failed"}
                        E.release();p1.release();p2.release();mask.release()
                    } else {rejected++;status="Too few RGB matches / frame gap"}
                }
                poses.add(Pose(frame.timestampNs,R.copyOf(),t.copyOf(),
                    ok,good,count,inertial,observedAngle,status))
                // Match far-apart keyframes as loop candidates only. Without
                // global BA no correction is applied or claimed.
                if(idx%12==0&&!desc.empty()){
                    if(idx>36)for(old in keyDescriptors.dropLast(2)){
                        val candidates=ArrayList<MatOfDMatch>()
                        matcher.knnMatch(old,desc,candidates,2)
                        var similar=0
                        candidates.forEach{c->
                            val v=c.toArray()
                            if(v.size>=2&&v[0].distance<.67f*v[1].distance)
                                similar++
                            c.release()
                        }
                        if(similar>65) {loops++;break}
                    }
                    keyDescriptors.add(desc.clone())
                }
                prevDesc?.release()
                prevKp?.release()
                prev=frame;prevDesc=desc;prevKp=points
                if(idx%15==0)progress("VO "+idx+"/"+frames.size+
                    " · "+accepted+" gültig · "+rejected+" verworfen")
            }
        }finally{
            prevDesc?.release();prevKp?.release()
            keyDescriptors.forEach{it.release()}
            k.release()
        }
        val warnings=listOf(
            "OpenCV 5-point optical odometry. Translation unit steps are not metres.",
            "A calibrated lens quaternion permits gyro rotation priors only when visually consistent. IMU bias/extrinsic timing must still be validated on Pixel.",
            "No IMU preintegration, loop closure optimization or bundle adjustment yet.",
            "Pose drift and unknown depth scale prohibit accurate room reconstruction."
        )
        return Result(poses,accepted,rejected,gyroPairs,
            gyroFusedPairs,calibration,loops,warnings)
    }
}
