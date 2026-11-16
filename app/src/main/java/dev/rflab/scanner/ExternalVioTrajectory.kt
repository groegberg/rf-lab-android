package dev.rflab.scanner

import java.io.File
import kotlin.math.abs

/**
 * Native OpenVINS Mobile research CSV. The 6DoF estimates are OUTPUTS from
 * its actual MSCKF/VIO; they are *not* fabricated from RF Lab optical flows.
 *
 * Pixel-specific camera/IMU calibration still has to be checked against
 * known distances; coordinates are therefore "VIO units, unvalidated".
 */
internal object ExternalVioTrajectory {
    data class Sample(
        val timestampNs: Long,
        val x: Double, val y: Double, val z: Double,
        val qw: Double, val qx: Double, val qy: Double, val qz: Double
    ) {
        fun center() = doubleArrayOf(x,y,z)
    }

    fun parse(lines: Sequence<String>, maxSamples: Int = 24_000): List<Sample> {
        val result=ArrayList<Sample>()
        var previous=0L
        for (line in lines.drop(1)) {
            val p=line.trim().split(',')
            if(p.size<8 || result.size>=maxSamples)continue
            val t=p[0].toLongOrNull()?:continue
            val nums=DoubleArray(7) { i ->
                p[i+1].toDoubleOrNull()?:Double.NaN
            }
            if(t<=previous || nums.any{!it.isFinite()})continue
            // Quaternion in released native app: [qw,qx,qy,qz].
            val norm=nums.sliceArray(3..6).sumOf{it*it}
            if(norm !in .80..1.20)continue
            result.add(Sample(t,nums[0],nums[1],nums[2],
                nums[3],nums[4],nums[5],nums[6]))
            previous=t
        }
        return result
    }

    fun read(session: File): List<Sample> {
        val csv=File(session,"external_vio_trajectory.csv")
        if(!csv.isFile || csv.length()>24L*1024L*1024L)return emptyList()
        return csv.useLines { parse(it).toList() }
    }

    fun closestIndex(samples:List<Sample>, timestampNs:Long,
                     maxGapNs:Long=180_000_000L):Int {
        if(samples.isEmpty()||timestampNs<=0L)return -1
        var lo=0;var hi=samples.lastIndex
        while(lo<hi){
            val mid=(lo+hi)/2
            if(samples[mid].timestampNs<timestampNs)lo=mid+1
            else hi=mid
        }
        val before=(lo-1).coerceAtLeast(0)
        val idx=if(abs(samples[before].timestampNs-timestampNs)<
            abs(samples[lo].timestampNs-timestampNs))before else lo
        return if(abs(samples[idx].timestampNs-timestampNs)<=maxGapNs)
            idx else -1
    }
}
