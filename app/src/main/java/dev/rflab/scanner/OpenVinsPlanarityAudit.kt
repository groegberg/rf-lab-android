package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Robust, dimensionless planarity audit of ONE RGB-D cloud.
 * RANSAC looks for a dominant plane among ceiling, floor, wall or tabletop.
 * Results are diagnostics; it cannot tell whether a plane is really present
 * without a known reference target or calibrated depth scale.
 */
internal object OpenVinsPlanarityAudit {
    data class Report(val samples:Int,val dominantPlaneFraction:Double,
                      val medianResidualToSpan:Double,
                      val diagnosticOnly:Boolean=true)

    fun inspect(points:List<SurveyDepth>):Report {
        if(points.size<90)return Report(points.size,0.0,Double.NaN)
        val step=max(1,points.size/650)
        val sampled=points.indices.step(step).map{points[it]}.filter{
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
        }
        if(sampled.size<90)return Report(sampled.size,0.0,Double.NaN)
        val span=maxOf(
            sampled.maxOf{it.x}-sampled.minOf{it.x},
            sampled.maxOf{it.y}-sampled.minOf{it.y},
            sampled.maxOf{it.z}-sampled.minOf{it.z}).toDouble()
        if(!span.isFinite() || span<1e-5)
            return Report(sampled.size,0.0,Double.NaN)
        val tolerance=max(1e-5,span*.008)
        val random=Random(4809)
        var bestInliers=0
        var bestMedian=Double.NaN
        repeat(105) {
            val a=sampled[random.nextInt(sampled.size)]
            val b=sampled[random.nextInt(sampled.size)]
            val c=sampled[random.nextInt(sampled.size)]
            val ax=(b.x-a.x).toDouble()
            val ay=(b.y-a.y).toDouble()
            val az=(b.z-a.z).toDouble()
            val bx=(c.x-a.x).toDouble()
            val by=(c.y-a.y).toDouble()
            val bz=(c.z-a.z).toDouble()
            val nx=ay*bz-az*by
            val ny=az*bx-ax*bz
            val nz=ax*by-ay*bx
            val length=sqrt(nx*nx+ny*ny+nz*nz)
            if(length<1e-8 || !length.isFinite())return@repeat
            val distances=sampled.map{
                abs((nx*(it.x-a.x)+ny*(it.y-a.y)+nz*(it.z-a.z))/
                    length)
            }.filter{it<=tolerance}.sorted()
            if(distances.size>bestInliers){
                bestInliers=distances.size
                bestMedian=if(distances.isEmpty())Double.NaN
                    else distances[distances.size/2]/span
            }
        }
        return Report(sampled.size,
            bestInliers.toDouble()/sampled.size,
            bestMedian)
    }
}
