package dev.rflab.scanner

import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Offline CAMERA-ONLY calibration from 10x7 printed chessboard squares
 * (9x6 inner corners), in the SAME lens, focus, zoom, image resolution and
 * distortion mode as OpenVINS capture. 2D reprojection RMS is diagnostic.
 *
 * Square size is one unit: intrinsics and distortion can be estimated,
 * but camera/IMU translation, clock offsets and metric dimensions CANNOT.
 * Nothing here automatically modifies the native OpenVINS YAML seed.
 */
internal object OpenVinsCheckerboardCalibration {
    private val board=Size(9.0,6.0)
    data class Result(
        val detected:Int,val tested:Int,
        val width:Int,val height:Int,
        val rmsReprojectionPx:Double?,
        val intrinsics:DoubleArray?,
        val distortion:DoubleArray?,
        val sufficientForCameraIntrinsics:Boolean,
        val issues:List<String>,
        val viewDiversity:OpenVinsChessboardDiversity.Report?=null
    ) {
        fun json():JSONObject=JSONObject()
            .put("source","OpenCV chessboard 9x6 inner corners")
            .put("camera_only_calibration",true)
            .put("camera_imu_rotation_verified",false)
            .put("camera_imu_translation_verified",false)
            .put("camera_imu_time_offset_verified",false)
            .put("metric_accuracy_verified",false)
            .put("board_square_size_known",false)
            .put("tested",tested)
            .put("detected",detected)
            .put("image_width",width)
            .put("image_height",height)
            .put("rms_reprojection_pixels",
                rmsReprojectionPx?:JSONObject.NULL)
            .put("intrinsics_fx_fy_cx_cy",
                intrinsics?.let{JSONArray(it.toList())}?:JSONObject.NULL)
            .put("radtan_k1_k2_p1_p2_k3",
                distortion?.let{JSONArray(it.toList())}?:JSONObject.NULL)
            .put("camera_intrinsics_candidate_valid",
                sufficientForCameraIntrinsics)
            .put("view_centroid_regions",viewDiversity?.occupiedRegions
                ?:JSONObject.NULL)
            .put("board_area_variation_ratio",
                viewDiversity?.areaVariationRatio?:JSONObject.NULL)
            .put("oblique_checkerboard_views",
                viewDiversity?.obliqueViews?:JSONObject.NULL)
            .put("checkerboard_view_diversity_adequate",
                viewDiversity?.sufficientlyDiverse?:false)
            .put("warnings",JSONArray(issues))
    }

    fun estimate(images:List<File>):Result {
        check(OpenCVLoader.initDebug()){"OpenCV native library unavailable"}
        val issues=ArrayList<String>()
        val objectPoints=ArrayList<Mat>()
        val imagePoints=ArrayList<Mat>()
        val boardViews=ArrayList<OpenVinsChessboardDiversity.View>()
        val expected=ArrayList<Point3>()
        for(y in 0 until 6)for(x in 0 until 9)
            expected.add(Point3(x.toDouble(),y.toDouble(),0.0))
        var width=0
        var height=0
        var tested=0
        var detected=0
        val K=Mat.eye(3,3,CvType.CV_64F)
        val dist=Mat()
        val rvecs=ArrayList<Mat>()
        val tvecs=ArrayList<Mat>()
        try{
            // Processing is bounded, each JPEG read strictly once.
            for(file in images.take(100)){
                if(!file.isFile || file.length() !in 20_000..20_000_000)continue
                val gray=Imgcodecs.imread(file.path,Imgcodecs.IMREAD_GRAYSCALE)
                if(gray.empty()){
                    gray.release()
                    continue
                }
                try{
                    if(width==0){width=gray.cols();height=gray.rows()}
                    if(gray.cols()!=width || gray.rows()!=height){
                        if(!issues.contains("Unterschiedliche Kameraauflösungen"))
                            issues+="Unterschiedliche Kameraauflösungen"
                        continue
                    }
                    tested++
                    val corners=MatOfPoint2f()
                    try{
                        val found=Calib3d.findChessboardCorners(gray,board,
                            corners,Calib3d.CALIB_CB_ADAPTIVE_THRESH or
                                Calib3d.CALIB_CB_NORMALIZE_IMAGE)
                        if(!found || corners.rows()*corners.cols()!=54)continue
                        Imgproc.cornerSubPix(gray,corners,Size(7.0,7.0),
                            Size(-1.0,-1.0),
                            TermCriteria(TermCriteria.EPS+
                                TermCriteria.MAX_ITER,40,.001))
                        OpenVinsChessboardDiversity.view(
                            corners.toArray().map{it.x to it.y},
                            width,height)?.let{boardViews.add(it)}
                        val obj=MatOfPoint3f()
                        obj.fromList(expected)
                        objectPoints.add(obj)
                        // Keep owned MatOfPoint2f alive until solve ends.
                        val stored=MatOfPoint2f()
                        stored.fromArray(*corners.toArray())
                        imagePoints.add(stored)
                        detected++
                    }finally{corners.release()}
                }finally{gray.release()}
            }
            if(detected<10){
                issues+="Mindestens 10 erfolgreiche Gitteransichten mit "+
                    "unterschiedlicher Neigung und Bildposition nötig"
                return Result(detected,tested,width,height,null,null,null,
                    false,issues,
                    OpenVinsChessboardDiversity.inspect(boardViews))
            }
            // Intrinsics estimable even if chessboard square size is
            // unknown; metric camera->IMU translation is NOT estimable here.
            val rms=Calib3d.calibrateCamera(objectPoints,imagePoints,
                Size(width.toDouble(),height.toDouble()),K,dist,rvecs,tvecs)
            val intr=doubleArrayOf(K.get(0,0)[0],K.get(1,1)[0],
                K.get(0,2)[0],K.get(1,2)[0])
            val d=DoubleArray(5){i->
                val row=if(dist.rows()==1)0 else i
                val col=if(dist.rows()==1)i else 0
                dist.get(row,col)?.getOrNull(0)?:0.0
            }
            if(rms>1.0)issues+="Reprojektionsfehler > 1 px"
            if(intr[0] !in width*.2..width*5.0 ||
                intr[1] !in height*.2..height*5.0)
                issues+="Unplausible Kamera-Brennweite"
            if(kotlin.math.abs(d[4])>.08)
                issues+="Starker k3-Verzeichnungsanteil: native 4-Parameter "+
                    "radtan-Kalibrierung nicht ohne Prüfung übernehmen"
            val diversity=OpenVinsChessboardDiversity.inspect(boardViews)
            if(!diversity.sufficientlyDiverse)
                issues+="Schachbrett-Ansichten zu ähnlich: mindestens 12 "+
                    "erkannte Gitter, 3 Bildbereiche, wechselnde Abstände "+
                    "und mehrere schräg gehaltene Perspektiven erforderlich"
            val valid=diversity.sufficientlyDiverse &&
                rms.isFinite() && rms<=1.0 &&
                intr.all{it.isFinite()} &&
                intr[0] in width*.2..width*5.0 &&
                intr[1] in height*.2..height*5.0 &&
                d.all{it.isFinite()} &&
                kotlin.math.abs(d[4])<=.08
            return Result(detected,tested,width,height,
                if(rms.isFinite())rms else null,intr,d,valid,issues,diversity)
        }finally{
            objectPoints.forEach{it.release()}
            imagePoints.forEach{it.release()}
            rvecs.forEach{it.release()}
            tvecs.forEach{it.release()}
            K.release();dist.release()
        }
    }
}
