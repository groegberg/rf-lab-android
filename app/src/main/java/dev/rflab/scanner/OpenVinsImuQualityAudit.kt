package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Evidence-based IMU input health, NOT a Camera<-IMU calibration.
 * Offline readings distinguish missing/irregular IMU events from
 * stationary bias/rate measurements; full time/extrinsic calibration still
 * needs a synchronized visual target and excitation in all 6DoF.
 */
internal object OpenVinsImuQualityAudit {
    data class Report(
        val samples:Int,val medianHz:Double,val maxGapMs:Double,
        val stationaryWindows:Int,val checkedWindows:Int,
        val stationaryGyroBiasRadS:List<Double>,
        val stationaryAccelerationMagnitude:Double?,
        val warnings:List<String>
    )
    fun inspect(imu:List<OpenVinsImageOrientation.Imu>):Report {
        val warning=ArrayList<String>()
        val dt=imu.zipWithNext{a,b->b.timestampNs-a.timestampNs}
            .filter{it>0}.sorted()
        val medianDt=if(dt.isNotEmpty())dt[dt.size/2] else 0L
        val hz=if(medianDt>0L)1e9/medianDt else 0.0
        val maxGapMs=if(dt.isNotEmpty())(dt.last()/1e6)else 0.0
        if(imu.size<400)warning+="Zu wenige IMU-Samples für stationäre Beurteilung"
        if(hz<180 || hz>900)warning+="Auffällige IMU-Abtastrate"
        if(maxGapMs>80.0)warning+="IMU-Zeitlücken >80 ms"
        var checked=0
        var stationary=0
        val gyroTotals=DoubleArray(3)
        var accelTotal=0.0
        var totalReadings=0
        // Non-overlapping 0.75-second blocks; very short steady stretches
        // should not be confused with a calibrated full bias model.
        val window=maxOf(80,(hz*.75).toInt())
        if(window>0){
            for(start in imu.indices step window){
                if(start+window>imu.size)break
                val segment=imu.subList(start,start+window)
                val duration=(segment.last().timestampNs-
                    segment.first().timestampNs)/1e9
                if(duration<.4 || duration>2.0)continue
                checked++
                val mags=segment.map{
                    sqrt(it.ax*it.ax+it.ay*it.ay+it.az*it.az)
                }
                val ma=mags.average()
                val std=sqrt(mags.sumOf{(it-ma)*(it-ma)}/mags.size)
                val meanSpin=segment.map{
                    sqrt(it.wx*it.wx+it.wy*it.wy+it.wz*it.wz)
                }.average()
                if(meanSpin<.065 && std<.17 && ma in 8.7..10.9){
                    stationary++
                    segment.forEach{
                        gyroTotals[0]+=it.wx
                        gyroTotals[1]+=it.wy
                        gyroTotals[2]+=it.wz
                        accelTotal+=sqrt(it.ax*it.ax+it.ay*it.ay+
                            it.az*it.az)
                    }
                    totalReadings+=segment.size
                }
            }
        }
        if(stationary==0)
            warning+="Keine ausreichend ruhige IMU-Phase für Bias-Schätzung"
        val bias=if(totalReadings>0)gyroTotals.map{it/totalReadings}
            else emptyList()
        return Report(imu.size,hz,maxGapMs,stationary,checked,bias,
            if(totalReadings>0)accelTotal/totalReadings else null,
            warning)
    }
}
