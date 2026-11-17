package dev.rflab.scanner

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sparse ORB correspondences from the EXACT DA3 RGB model input; triangulate
 * with native OpenVINS timestamped camera poses. Reject unmatched, low-
 * parallax and non-intersecting rays. No synthetic trajectory or depth.
 */
internal object OpenVinsDa3MultiView {
    data class Feature(
        val relativeInverse: Double,
        val normalizedX: Double,
        val normalizedY: Double,
        val ray: OpenVinsDa3Alignment.Ray
    )

    class Frame(val photoIndex: Int,
                val pose: ExternalVioTrajectory.Sample,
                val descriptors: Mat,
                val features: List<Feature?>) : AutoCloseable {
        override fun close() { descriptors.release() }
    }

    data class Result(val fits: Map<Int,OpenVinsDa3Alignment.Fit>,
                      val matchedFeatures: Int,
                      val triangulatedFeatures: Int,
                      val pairsConsidered: Int) 

    private fun norm(v:DoubleArray):Double =
        sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])

    fun extract(
        photoIndex:Int,
        modelInput:Bitmap, modelDepth:FloatArray,
        sample:ExternalVioTrajectory.Sample,
        calibration:OpenVinsDa3Geometry.Calibration,
        sourceWidth:Int,sourceHeight:Int,
        rotationDegrees:Int
    ):Frame? {
        if(!calibration.compatibleWith(sourceWidth,sourceHeight) ||
            modelInput.width!=Da3DepthEngine.WIDTH ||
            modelInput.height!=Da3DepthEngine.HEIGHT ||
            modelDepth.size!=Da3DepthEngine.WIDTH*Da3DepthEngine.HEIGHT)
            return null
        check(OpenCVLoader.initDebug()){"OpenCV unavailable for VIO/DA3 correspondence"}
        val center=OpenVinsPoseTransform.toMap(sample.center()) ?: return null
        val origin=doubleArrayOf(
            center[0].toDouble(),center[1].toDouble(),center[2].toDouble())
        val rgba=Mat()
        val gray=Mat()
        val kp=MatOfKeyPoint()
        val descriptors=Mat()
        val orb=ORB.create(1100)
        val mask=Mat()
        try {
            Utils.bitmapToMat(modelInput,rgba)
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            orb.detectAndCompute(gray,mask,kp,descriptors)
            if(descriptors.empty()||kp.empty()){
                descriptors.release()
                return null
            }
            val features=kp.toArray().map { point ->
                val u=point.pt.x
                val v=point.pt.y
                val ix=u.toInt()
                val iy=v.toInt()
                if(ix !in 0 until modelInput.width ||
                   iy !in 0 until modelInput.height) null
                else {
                    val inverse=modelDepth[iy*modelInput.width+ix].toDouble()
                    if(!inverse.isFinite() || inverse<=0.0)null
                    else {
                        val pixel=OpenVinsDa3Geometry.sourcePixel(
                            u,v,sourceWidth,sourceHeight,modelInput.width,
                            modelInput.height,rotationDegrees)
                        val projected=pixel?.let{
                            OpenVinsDa3Geometry.mapPoint(sample,calibration,
                                it[0],it[1],sourceWidth,sourceHeight,1.0)
                        }
                        if(projected==null)null else {
                            val direction=DoubleArray(3){i->
                                projected[i]-origin[i]
                            }
                            val distance=norm(direction)
                            if(distance<.98 || !distance.isFinite())null
                            else Feature(inverse,
                                (u+.5)/modelInput.width,
                                (v+.5)/modelInput.height,
                                OpenVinsDa3Alignment.Ray(origin,
                                    DoubleArray(3){direction[it]/distance},
                                    distance))
                        }
                    }
                }
            }
            return Frame(photoIndex,sample,descriptors,features)
        }catch(t:Throwable){
            descriptors.release()
            throw t
        }finally{
            rgba.release();gray.release();kp.release()
            mask.release();orb.clear()
        }
    }

    fun align(frames:List<Frame>):Result {
        if(frames.size<2)return Result(emptyMap(),0,0,0)
        val samples=HashMap<Int,MutableList<OpenVinsDa3Alignment.Observation>>()
        frames.forEach{samples[it.photoIndex]=ArrayList()}
        check(OpenCVLoader.initDebug()){"OpenCV unavailable for VIO/DA3 alignment"}
        val matcher=BFMatcher.create(Core.NORM_HAMMING,false)
        var matched=0
        var triangulated=0
        var tried=0
        try {
            for(i in 0 until frames.lastIndex) {
                val a=frames[i]
                // Consecutive and nearby captures compensate for slow motion
                // and maintain overlap without quadratic all-to-all matching.
                for(j in i+1..minOf(frames.lastIndex,i+3)){
                    val b=frames[j]
                    val ca=a.features.firstOrNull{it!=null}?.ray?.origin ?:continue
                    val cb=b.features.firstOrNull{it!=null}?.ray?.origin ?:continue
                    val baseline=norm(DoubleArray(3){cb[it]-ca[it]})
                    if(baseline !in .06..3.0)continue
                    if(a.descriptors.empty()||b.descriptors.empty())continue
                    tried++
                    val forward=ArrayList<MatOfDMatch>()
                    val reverse=ArrayList<MatOfDMatch>()
                    try{
                        matcher.knnMatch(a.descriptors,b.descriptors,forward,2)
                        matcher.knnMatch(b.descriptors,a.descriptors,reverse,2)
                        val reciprocal=HashMap<Int,Int>()
                        reverse.forEach{group->
                            val m=group.toArray()
                            if(m.size>=2 && m[0].distance<.72f*m[1].distance)
                                reciprocal[m[0].queryIdx]=m[0].trainIdx
                        }
                        forward.forEach{group->
                            val m=group.toArray()
                            if(m.size<2 || m[0].distance>=.72f*m[1].distance)
                                return@forEach
                            val first=m[0]
                            if(reciprocal[first.trainIdx]!=first.queryIdx)
                                return@forEach
                            val fa=a.features.getOrNull(first.queryIdx)
                                ?:return@forEach
                            val fb=b.features.getOrNull(first.trainIdx)
                                ?:return@forEach
                            matched++
                            val depth=OpenVinsDa3Alignment.triangulate(
                                fa.ray,fb.ray) ?:return@forEach
                            triangulated++
                            val aa=samples.getValue(a.photoIndex)
                            val bb=samples.getValue(b.photoIndex)
                            if(aa.size<3000) aa.add(OpenVinsDa3Alignment.Observation(
                                fa.relativeInverse,depth.inverseA,
                                fa.normalizedX,fa.normalizedY))
                            if(bb.size<3000) bb.add(OpenVinsDa3Alignment.Observation(
                                fb.relativeInverse,depth.inverseB,
                                fb.normalizedX,fb.normalizedY))
                        }
                    }finally{
                        forward.forEach{it.release()}
                        reverse.forEach{it.release()}
                    }
                }
            }
        }finally{matcher.clear()}
        val fits=samples.mapNotNull { (index,observations) ->
            OpenVinsDa3Alignment.fit(observations)?.let{index to it}
        }.toMap()
        return Result(fits,matched,triangulated,tried)
    }
}
