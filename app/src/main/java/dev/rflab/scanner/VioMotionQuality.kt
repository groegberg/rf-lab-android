package dev.rflab.scanner

import kotlin.math.sqrt
import java.util.Locale

/**
 * An initialized native filter does not prove physically plausible motion.
 * Detect catastrophic indoor translation drift before world-space RGB-D
 * fusion. Passing these coarse limits is NOT a metric calibration.
 */
internal object VioMotionQuality {
    data class Report(
        val acceptableForExperimentalGeometry:Boolean,
        val durationSeconds:Double,
        val pathLengthUnvalidated:Double,
        val displacementUnvalidated:Double,
        val medianSpeedUnitsPerSecond:Double,
        val p90SpeedUnitsPerSecond:Double,
        val maxSpeedUnitsPerSecond:Double,
        val samples:Int,
        val reasons:List<String>
    )

    private fun length(dx:Double,dy:Double,dz:Double):Double =
        sqrt(dx*dx+dy*dy+dz*dz)

    fun inspect(poses:List<ExternalVioTrajectory.Sample>):Report {
        val speeds=ArrayList<Double>()
        val reasons=ArrayList<String>()
        var path=0.0
        var badInterval=0
        for(i in 1 until poses.size) {
            val a=poses[i-1]
            val b=poses[i]
            val dt=(b.timestampNs-a.timestampNs)/1e9
            if(dt<=0.0 || !dt.isFinite()) {
                badInterval++
                continue
            }
            val step=length(b.x-a.x,b.y-a.y,b.z-a.z)
            if(!step.isFinite()) {
                badInterval++
                continue
            }
            path+=step
            if(dt>.5) {
                badInterval++
                continue
            }
            speeds.add(step/dt)
        }
        val duration=if(poses.size>=2)
            (poses.last().timestampNs-poses.first().timestampNs)/1e9 else 0.0
        val displacement=if(poses.size>=2)
            length(poses.last().x-poses.first().x,
                poses.last().y-poses.first().y,
                poses.last().z-poses.first().z) else 0.0
        val sorted=speeds.sorted()
        fun percentile(p:Double)=if(sorted.isEmpty())0.0 else
            sorted[((sorted.size-1)*p).toInt()]
        val median=percentile(.5)
        val p90=percentile(.9)
        val maximum=sorted.lastOrNull()?:0.0
        if(poses.size<20 || sorted.size<15)
            reasons+="Zu wenige zusammenhängende native VIO-Posen"
        if(badInterval>0)
            reasons+="VIO-Zeitlücke oder ungültiger Zeitstempel: "+
                badInterval+" Intervalle"
        if(!duration.isFinite() || duration<=0.0)
            reasons+="Ungültige Dauer der VIO-Aufnahme"
        if(!path.isFinite() || !displacement.isFinite())
            reasons+="Ungültige VIO-Positionswerte"
        if(p90>4.5)
            reasons+="90%-VIO-Geschwindigkeit "+
                String.format(Locale.US,"%.1f",p90)+
                " Einheiten/s: unplausible Raumbewegung"
        if(maximum>12.0)
            reasons+="VIO-Sprung: "+
                String.format(Locale.US,"%.1f",maximum)+" Einheiten/s"
        if(duration in 1.0..30.0 && displacement>25.0)
            reasons+="In "+String.format(Locale.US,"%.1f",duration)+
                " s "+String.format(Locale.US,"%.1f",displacement)+
                " VIO-Einheiten verschoben; vermutlich Filterdrift"
        return Report(reasons.isEmpty(),duration,path,displacement,median,
            p90,maximum,poses.size,reasons)
    }
}
