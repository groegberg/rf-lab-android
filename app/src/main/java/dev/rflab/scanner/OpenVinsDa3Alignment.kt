package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure numerical multi-view depth alignment. The camera baseline is provided
 * by initialized *native* OpenVINS, not guessed from the DA3 image. DA3-Small
 * produces affine-invariant inverse depth; fit 1/z = scale * da3 + shift using
 * sparse optically matched rays triangulated against the native VIO poses.
 *
 * Distances remain in UNVALIDATED VIO units. A poor fit produces NO geometry.
 */
internal object OpenVinsDa3Alignment {
    data class Ray(val origin: DoubleArray, val unit: DoubleArray,
                   val lengthAtAxialUnit: Double) {
        init {
            require(origin.size==3 && unit.size==3)
        }
    }
    data class PairDepth(val inverseA: Double, val inverseB: Double,
                         val rayGap: Double)
    data class Observation(val predictedInverse: Double,
                           val measuredInverse: Double,
                           val imageX: Double, val imageY: Double)
    data class Fit(val scale: Double, val shift: Double,
                   val inliers: Int, val observations: Int,
                   val medianRelativeError: Double,
                   val predictedMin: Double, val predictedMax: Double,
                   val coverageQuadrants: Int) {
        fun inverseAt(predicted: Double): Double = scale * predicted + shift
        fun depthAt(predicted: Double): Double? {
            if(!predicted.isFinite() ||
                predicted !in (predictedMin-.25*(predictedMax-predictedMin))..
                              (predictedMax+.25*(predictedMax-predictedMin))) return null
            val inverse = inverseAt(predicted)
            return if(inverse.isFinite() && inverse in (1.0/12.0)..(1.0/.3))
                1.0/inverse else null
        }
    }
    private fun dot(a:DoubleArray,b:DoubleArray):Double =
        a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
    private fun minus(a:DoubleArray,b:DoubleArray) =
        doubleArrayOf(a[0]-b[0],a[1]-b[1],a[2]-b[2])
    private fun length(a:DoubleArray) = sqrt(dot(a,a))

    /** Closest 3D point between two camera rays with parallax/cheirality gate. */
    fun triangulate(a: Ray,b: Ray): PairDepth? {
        if(!a.origin.all{it.isFinite()} || !b.origin.all{it.isFinite()} ||
            !a.unit.all{it.isFinite()} || !b.unit.all{it.isFinite()} ||
            a.lengthAtAxialUnit !in 1.0..4.0 ||
            b.lengthAtAxialUnit !in 1.0..4.0) return null
        val baseline=length(minus(a.origin,b.origin))
        if(baseline !in .06..3.0) return null
        if(abs(length(a.unit)-1.0)>.01 ||
            abs(length(b.unit)-1.0)>.01) return null
        val cos=dot(a.unit,b.unit).coerceIn(-1.0,1.0)
        val determinant=1-cos*cos
        // Must have at least ~1.5° parallax, not a pure rotation.
        if(determin<.00069) return null
        val w=minus(b.origin,a.origin)
        val aw=dot(a.unit,w)
        val bw=dot(b.unit,w)
        val ta=(aw-cos*bw)/determinant
        val tb=(cos*aw-bw)/determinant
        if(ta<=0 || tb<=0 || !ta.isFinite() || !tb.isFinite()) return null
        val axialA=ta/a.lengthAtAxialUnit
        val axialB=tb/b.lengthAtAxialUnit
        if(axialA !in .35..12.0 || axialB !in .35..12.0) return null
        val dx=a.origin[0]+ta*a.unit[0]-b.origin[0]-tb*b.unit[0]
        val dy=a.origin[1]+ta*a.unit[1]-b.origin[1]-tb*b.unit[1]
        val dz=a.origin[2]+ta*a.unit[2]-b.origin[2]-tb*b.unit[2]
        val gap=sqrt(dx*dx+dy*dy+dz*dz)
        if(gap>max(.035,.018*min(axialA,axialB))) return null
        return PairDepth(1/axialA,1/axialB,gap)
    }

    /**
     * Deterministic robust two-point RANSAC followed by inlier least squares.
     * High inverse-depth dynamic range, positive scale, spatial coverage
     * and actual inlier error are required to accept the fit.
     */
    fun fit(raw:List<Observation>):Fit? {
        val observations=raw.filter{ o->
            o.predictedInverse.isFinite() && o.predictedInverse>0 &&
            o.measuredInverse.isFinite() &&
            o.measuredInverse in (1.0/12.0)..(1.0/.35) &&
            o.imageX in 0.0..1.0 && o.imageY in 0.0..1.0
        }.let{if(it.size>1400) it.shuffled(Random(1149)).take(1400) else it}
        if(observations.size<16) return null
        val predicted=observations.map{it.predictedInverse}.sorted()
        val spread=predicted[(predicted.lastIndex*.9).toInt()] -
            predicted[(predicted.lastIndex*.1).toInt()]
        if(spread<=max(1e-5,predicted[predicted.size/2]*.02)) return null
        fun inlier(o:Observation,s:Double,t:Double):Boolean {
            val model=s*o.predictedInverse+t
            return model>0 && abs(model-o.measuredInverse) <=
                max(.045,.10*o.measuredInverse)
        }
        val random=Random(2103)
        var bestCount=0
        var bestScale=0.0
        var bestShift=0.0
        repeat(550) {
            val a=observations[random.nextInt(observations.size)]
            val b=observations[random.nextInt(observations.size)]
            val span=b.predictedInverse-a.predictedInverse
            if(abs(span)<spread*.1) return@repeat
            val scale=(b.measuredInverse-a.measuredInverse)/span
            val shift=a.measuredInverse-scale*a.predictedInverse
            if(scale<=0 || !scale.isFinite() ||
                !shift.isFinite())return@repeat
            val accepted=observations.count{inlier(it,scale,shift)}
            if(accepted>bestCount){
                bestCount=accepted;bestScale=scale;bestShift=shift
            }
        }
        if(bestCount<16 || bestCount<observations.size*.36) return null
        var slope=bestScale
        var intercept=bestShift
        repeat(3){
            val points=observations.filter{inlier(it,slope,intercept)}
            if(points.size<16)return null
            val mx=points.sumOf{it.predictedInverse}/points.size
            val my=points.sumOf{it.measuredInverse}/points.size
            val variance=points.sumOf{
                val d=it.predictedInverse-mx;d*d
            }
            if(variance<1e-10)return null
            val covariance=points.sumOf{
                (it.predictedInverse-mx)*(it.measuredInverse-my)
            }
            slope=covariance/variance
            intercept=my-slope*mx
            if(slope<=0 || !slope.isFinite() || !intercept.isFinite())return null
        }
        val accepted=observations.filter{inlier(it,slope,intercept)}
        if(accepted.size<16 || accepted.size<observations.size*.36)return null
        val errors=accepted.map {
            abs((slope*it.predictedInverse+intercept)-
                it.measuredInverse)/it.measuredInverse
        }.sorted()
        val medianError=errors[errors.size/2]
        if(medianError>.11)return null
        val supported=accepted.map{it.predictedInverse}.sorted()
        val minValue=supported[(supported.lastIndex*.05).toInt()]
        val maxValue=supported[(supported.lastIndex*.95).toInt()]
        if(maxValue-minValue<spread*.20) return null
        val quadrants=accepted.map{
            (if(it.imageX>=.5)1 else 0) + (if(it.imageY>=.5)2 else 0)
        }.toSet().size
        if(quadrants<2)return null
        return Fit(slope,intercept,accepted.size,observations.size,
            medianError,minValue,maxValue,quadrants)
    }
}
