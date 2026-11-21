package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-numeric checkerboard calibration observability gate. Repeated views
 * of the same fronto-parallel board may yield an artificially tiny reprojection
 * RMS but ill-conditioned camera intrinsics. Report insufficient diversity.
 *
 * Only measures visual coverage. Does not certify intrinsic parameters or
 * estimate camera-to-gyro extrinsics.
 */
internal object OpenVinsChessboardDiversity {
    data class View(val centerX:Double,val centerY:Double,
                    val boardAreaFraction:Double,
                    val obliqueness:Double)
    data class Report(val detectedViews:Int,val occupiedRegions:Int,
                      val areaVariationRatio:Double,val obliqueViews:Int,
                      val sufficientlyDiverse:Boolean)

    fun inspect(views:List<View>):Report {
        val valid=views.filter{
            it.centerX.isFinite() && it.centerY.isFinite() &&
            it.centerX in 0.0..1.0 && it.centerY in 0.0..1.0 &&
            it.boardAreaFraction.isFinite() &&
            it.boardAreaFraction in .0001..1.0 &&
            it.obliqueness.isFinite() && it.obliqueness in 0.0..1.0
        }
        if(valid.isEmpty())return Report(0,0,0.0,0,false)
        val regions=valid.map{
            val x=min(2,floor(it.centerX*3).toInt())
            val y=min(2,floor(it.centerY*3).toInt())
            x+3*y
        }.toSet().size
        val areas=valid.map{it.boardAreaFraction}.sorted()
        val small=areas[(areas.lastIndex*.10).toInt()]
        val large=areas[(areas.lastIndex*.90).toInt()]
        val ratio=large/max(.0001,small)
        val oblique=valid.count{it.obliqueness>.15}
        // At least 12 detections, multiple image quadrants, differing board
        // scale and at least two angled views; do not use RMS alone.
        val accepted=valid.size>=12 && regions>=3 &&
            ratio>=1.25 && oblique>=2
        return Report(valid.size,regions,ratio,oblique,accepted)
    }

    /**
     * Estimate board centroid, view size, obliqueness from a 9x6 inner-corner
     * rectangular grid ordered row-major, in original sensor pixels.
     */
    fun view(corners:List<Pair<Double,Double>>,
             width:Int,height:Int):View? {
        if(corners.size!=54 || width<=0 || height<=0)return null
        val tl=corners[0]
        val tr=corners[8]
        val bl=corners[45]
        val br=corners[53]
        if(corners.any{!it.first.isFinite() || !it.second.isFinite()})
            return null
        val cx=corners.sumOf{it.first}/corners.size/width
        val cy=corners.sumOf{it.second}/corners.size/height
        val ux=tr.first-tl.first
        val uy=tr.second-tl.second
        val vx=bl.first-tl.first
        val vy=bl.second-tl.second
        val un=kotlin.math.hypot(ux,uy)
        val vn=kotlin.math.hypot(vx,vy)
        if(un<2.0 || vn<2.0)return null
        val area=abs(
            tl.first*tr.second-tr.first*tl.second +
            tr.first*br.second-br.first*tr.second +
            br.first*bl.second-bl.first*br.second +
            bl.first*tl.second-tl.first*bl.second
        )*.5/(width.toDouble()*height)
        // A genuine oblique view can also keep the corner axes
        // perpendicular (tilt around the checkerboard's horizontal or
        // vertical axis). Capture perspective foreshortening through
        // opposing edge length differences instead of demanding only skew.
        val bottom=kotlin.math.hypot(br.first-bl.first,br.second-bl.second)
        val right=kotlin.math.hypot(br.first-tr.first,br.second-tr.second)
        if(bottom<2.0 || right<2.0)return null
        val skew=abs((ux*vx+uy*vy)/(un*vn))
        val vanishing=kotlin.math.max(
            abs(kotlin.math.ln(un/bottom)),
            abs(kotlin.math.ln(vn/right)))
        val obliqueness=kotlin.math.max(skew,vanishing)
            .coerceIn(0.0,1.0)
        return View(cx,cy,area,obliqueness)
    }
}
