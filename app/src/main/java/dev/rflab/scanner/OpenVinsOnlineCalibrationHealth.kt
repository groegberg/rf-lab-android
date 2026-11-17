package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Checks if OpenVINS's native online camera calibration actually settled
 * during a recorded 6DoF trajectory. Bounded parameter change is necessary,
 * but NOT sufficient for true calibration accuracy (requires image reproj,
 * independent rig calibration and metric ground truth).
 */
internal object OpenVinsOnlineCalibrationHealth {
    data class Report(
        val acceptedForExperimentalFusion:Boolean,
        val samples:Int,
        val maxFocalVariationPercent:Double,
        val maxExtrinsicRotationVariationDegrees:Double,
        val maxExtrinsicTranslationVariation:Double,
        val maxTimeOffsetVariationMs:Double,
        val medianTimeOffsetMs:Double,
        val reasons:List<String>
    )

    private fun median(values:List<Double>):Double {
        val sorted=values.sorted()
        return sorted[sorted.size/2]
    }
    private fun norm(x:DoubleArray):Double =
        kotlin.math.sqrt(x.sumOf{it*it})
    private fun distance(a:DoubleArray,b:DoubleArray)=
        norm(DoubleArray(3){a[it]-b[it]})

    fun inspect(poses:List<ExternalVioTrajectory.Sample>,
                seed:OpenVinsDa3Geometry.Calibration?):Report {
        val records=poses.mapNotNull{it.onlineCamera}.filter{
            it.intrinsics.size==8 &&
                it.intrinsics.all{v->v.isFinite()} &&
                it.qIToC.size==4 &&
                it.qIToC.all{v->v.isFinite()} &&
                it.pIInC.size==3 &&
                it.pIInC.all{v->v.isFinite()} &&
                it.timeOffsetSeconds.isFinite()
        }
        if(records.size<20 || seed==null)return Report(false,records.size,
            Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,
            listOf("Zu wenige native OpenVINS-Online-Kalibrierungen "+
                "oder keine ursprüngliche Camera2-Kalibrierung"))
        val problems=ArrayList<String>()
        if(records.size<poses.size*.85)
            problems+="Mehr als 15% der VIO-Posen ohne Online-Kalibrierung"
        // First 10% of a scan is often a transient initialization phase.
        // Evaluate the late 75% for settled behavior.
        val stable=records.drop(records.size/4)
        val fx=stable.map{it.intrinsics[0]}
        val fy=stable.map{it.intrinsics[1]}
        val fxMedian=median(fx)
        val fyMedian=median(fy)
        if(fxMedian<=1.0 || fyMedian<=1.0)
            problems+="VIO-Kamera-Brennweite ungültig"
        val focal=maxOf(
            (fx.maxOrNull()!!-fx.minOrNull()!!)/max(1.0,fxMedian),
            (fy.maxOrNull()!!-fy.minOrNull()!!)/max(1.0,fyMedian))*100.0
        if(!focal.isFinite() || focal>4.0)
            problems+="Online-Kamerabrennweite schwankt um mehr als 4%"
        // Reject obviously divergent online K even when the filter has
        // converged to a stable but wildly different set of intrinsics.
        if(abs(fxMedian/seed.fx-1.0)>.20 ||
            abs(fyMedian/seed.fy-1.0)>.20)
            problems+="OpenVINS-Brennweite weicht >20% vom Camera2-Seed ab"
        val q0=stable.first().qIToC
        val n0=norm(q0)
        val rotations=stable.map{c->
            val q=c.qIToC
            val denom=n0*norm(q)
            if(denom<.8)180.0 else {
                val dot=(0..3).sumOf{i->q[i]*q0[i]}/denom
                2*acos(abs(dot).coerceIn(0.0,1.0))*180/Math.PI
            }
        }
        val rotation=rotations.maxOrNull()?:180.0
        if(rotation>7.0)problems+="Kamera-IMU-Extrinsik rotiert im "+
            "letzten Scanabschnitt um mehr als 7 Grad"
        val firstPosition=stable.first().pIInC
        val position=stable.maxOf{distance(it.pIInC,firstPosition)}
        if(position>.035)problems+="Kamera-IMU-Translation schwankt "+
            "um mehr als 3,5 cm in VIO-Einheiten"
        val shifts=stable.map{it.timeOffsetSeconds}
        val offset=median(shifts)*1000.0
        val offsetRange=(shifts.maxOrNull()!!-shifts.minOrNull()!!)*1000.0
        if(abs(offset)>80.0 || offsetRange>15.0)
            problems+="Kamera-IMU-Zeitversatz groß oder instabil"
        return Report(problems.isEmpty(),records.size,focal,rotation,
            position,offsetRange,offset,problems)
    }
}
