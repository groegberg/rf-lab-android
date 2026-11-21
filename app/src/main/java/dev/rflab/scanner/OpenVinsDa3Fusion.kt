package dev.rflab.scanner

import java.io.File
import kotlin.math.abs

/**
 * Actual DA3-Small raw relative DEPTH RGB samples represented as native camera rays until sparse
 * multi-view feature triangulation has fitted the depth scale AND offset.
 * Uncalibrated/unsupported frames never produce 3D points.
 */
internal object OpenVinsDa3Fusion {
    private const val SAMPLING_STEP=12

    data class RaySample(val center:FloatArray, val worldAtAxialUnit:FloatArray,
                         val relativeDepth:Float, val argb:Int)

    fun calibration(session:File):OpenVinsDa3Geometry.Calibration? {
        val source=File(session,"external_calibration/kalibr_imucam_chain.yaml")
        if(!source.isFile || source.length() !in 100..(256*1024))return null
        return OpenVinsDa3Geometry.calibration(source.readText())
    }

    fun sampleUnscaled(
        prediction:Da3DepthEngine.Prediction,
        pose:ExternalVioTrajectory.Sample,
        calibration:OpenVinsDa3Geometry.Calibration,
        sourceWidth:Int,sourceHeight:Int,
        lowDepth:Float,highDepth:Float
    ):List<RaySample> {
        if(!calibration.compatibleWith(sourceWidth,sourceHeight) ||
            !lowDepth.isFinite() || !highDepth.isFinite() ||
            highDepth<=lowDepth*1.025f)return emptyList()
        val w=Da3DepthEngine.WIDTH
        val h=Da3DepthEngine.HEIGHT
        val rawDepth=prediction.relativeDepth
        if(rawDepth.size!=w*h || prediction.image.width!=w ||
            prediction.image.height!=h)return emptyList()
        val center=OpenVinsPoseTransform.toMap(pose.center())?:return emptyList()
        val results=ArrayList<RaySample>(w*h/(SAMPLING_STEP*SAMPLING_STEP))
        for(v in (h*.04).toInt() until (h*.96).toInt() step SAMPLING_STEP)
            for(u in (w*.04).toInt() until (w*.96).toInt() step SAMPLING_STEP){
                val d=rawDepth[v*w+u]
                if(!d.isFinite() || d<=lowDepth || d>=highDepth)continue
                val p=OpenVinsDa3Geometry.sourcePixel(
                    u.toDouble(),v.toDouble(),sourceWidth,sourceHeight,
                    w,h,prediction.rotationDegrees)
                    ?:continue
                val world=OpenVinsDa3Geometry.mapPoint(pose,calibration,
                    p[0],p[1],sourceWidth,sourceHeight,1.0)?:continue
                results.add(RaySample(center,world,d,
                    prediction.image.getPixel(u,v) or (255 shl 24)))
            }
        return results
    }

    fun align(samples:List<RaySample>,
              fit:OpenVinsDa3Alignment.Fit):List<SurveyDepth> {
        val out=ArrayList<SurveyDepth>(samples.size)
        for(point in samples){
            val distance=fit.depthAt(point.relativeDepth.toDouble()) ?: continue
            val world=point.worldAtAxialUnit
            val origin=point.center
            val x=origin[0]+(world[0]-origin[0])*distance
            val y=origin[1]+(world[1]-origin[1])*distance
            val z=origin[2]+(world[2]-origin[2])*distance
            if(!x.isFinite() || !y.isFinite() || !z.isFinite() ||
                maxOf(abs(x),abs(y),abs(z))>40)continue
            out.add(SurveyDepth(x.toFloat(),y.toFloat(),z.toFloat(),
                confidence=.55f,argb=point.argb,rawDepth=false))
        }
        return out
    }
}
