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
    /** OpenVINS's ACTUAL online state at the exported camera pose time.
     *  Values are estimates, NOT independently calibrated. qIToC is JPL.
     */
    data class CameraEstimate(
        val intrinsics:DoubleArray, // fx fy cx cy k1 k2 p1 p2
        val qIToC:DoubleArray, // JPL qx qy qz qw
        val pIInC:DoubleArray, // p_IinC in OpenVINS camera coordinates
        val timeOffsetSeconds:Double
    ) {
        fun cameraCalibration(seed:OpenVinsDa3Geometry.Calibration):
                OpenVinsDa3Geometry.Calibration? {
            val k=intrinsics
            if(k.size!=8 || !k.all{it.isFinite()} ||
                k[0] !in 20.0..10000.0 || k[1] !in 20.0..10000.0 ||
                kotlin.math.abs(k[2])>seed.width*2.0 ||
                kotlin.math.abs(k[3])>seed.height*2.0 ||
                k.drop(4).any{kotlin.math.abs(it)>3.0})return null
            return seed.copy(fx=k[0],fy=k[1],cx=k[2],cy=k[3],
                k1=k[4],k2=k[5],p1=k[6],p2=k[7])
        }
        fun rotationCamFromImu():DoubleArray?{
            if(qIToC.size!=4 || qIToC.any{!it.isFinite()})return null
            val squared=qIToC.sumOf{it*it}
            if(squared !in .81..1.21)return null
            val n=kotlin.math.sqrt(squared)
            val x=qIToC[0]/n;val y=qIToC[1]/n
            val z=qIToC[2]/n;val w=qIToC[3]/n
            // The JPL matrix maps IMU to camera. Hamilton has the opposite
            // off-diagonal skew; do not use Android's Hamilton R here.
            return doubleArrayOf(
                1-2*(y*y+z*z),2*(x*y+z*w),2*(x*z-y*w),
                2*(x*y-z*w),1-2*(x*x+z*z),2*(y*z+x*w),
                2*(x*z+y*w),2*(y*z-x*w),1-2*(x*x+y*y))
        }
    }

    data class Sample(
        val timestampNs: Long,
        val x: Double, val y: Double, val z: Double,
        val qw: Double, val qx: Double, val qy: Double, val qz: Double,
        val onlineCamera:CameraEstimate?=null
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
            // Extended native CSV appends current OpenVINS online
            // intrinsics/extrinsics/time offset at the same camera timestamp.
            // Legacy eight-column CSVs still supply poses, but cannot claim
            // calibration-consistent RGB-D projection.
            val calibrated=if(p.size>=24){
                val e=DoubleArray(16){i->
                    p[i+8].toDoubleOrNull()?:Double.NaN
                }
                val q=e.sliceArray(8..11)
                val pI=e.sliceArray(12..14)
                val dt=e[15]
                if(e.all{it.isFinite()} &&
                    e[0]>20.0 && e[1]>20.0 &&
                    q.sumOf{it*it} in .81..1.21 &&
                    pI.all{kotlin.math.abs(it)<5.0} &&
                    kotlin.math.abs(dt)<.5)
                    CameraEstimate(e.sliceArray(0..7),q,pI,dt)
                else null
            } else null
            result.add(Sample(t,nums[0],nums[1],nums[2],
                nums[3],nums[4],nums[5],nums[6],calibrated))
            previous=t
        }
        return result
    }

    /**
     * Pose at the *camera exposure timestamp*, with interpolation between
     * surrounding initialized native VIO states. Nearest-sample assignment
     * at 15 Hz introduces 20-35 ms rotation/translation misregistration.
     *
     * Reject time gaps rather than inventing extrapolated camera poses.
     * Quaternion interpolation uses the shortest arc (JPL component order
     * remains unchanged). Online K/extrinsics use the nearer state.
     */
    fun interpolate(samples:List<Sample>,cameraTimestampNs:Long,
                    maxBracketNs:Long=180_000_000L,
                    endpointToleranceNs:Long=20_000_000L):Sample? {
        if(samples.isEmpty() || cameraTimestampNs<=0L ||
            maxBracketNs<=0 || endpointToleranceNs<0)return null
        var lo=0
        var hi=samples.lastIndex
        while(lo<hi){
            val mid=(lo+hi)/2
            if(samples[mid].timestampNs<cameraTimestampNs)lo=mid+1
            else hi=mid
        }
        val b=samples[lo]
        if(b.timestampNs==cameraTimestampNs)return b
        if(lo==0 || b.timestampNs<cameraTimestampNs){
            val edge=if(lo==0)b else samples.last()
            return edge.takeIf{
                kotlin.math.abs(it.timestampNs-cameraTimestampNs)<=
                    endpointToleranceNs
            }
        }
        val a=samples[lo-1]
        val span=b.timestampNs-a.timestampNs
        if(span<=0 || span>maxBracketNs)return null
        val u=(cameraTimestampNs-a.timestampNs).toDouble()/span
        if(u !in 0.0..1.0)return null
        // Normalized linear interpolation is accurate for small camera frame
        // intervals; inverse-sign handling avoids discontinuities at q~-q.
        val qa=doubleArrayOf(a.qw,a.qx,a.qy,a.qz)
        val qb=doubleArrayOf(b.qw,b.qx,b.qy,b.qz)
        val dot=qa.indices.sumOf{qa[it]*qb[it]}
        val sign=if(dot<0.0)-1.0 else 1.0
        val q=DoubleArray(4){(1-u)*qa[it]+u*qb[it]*sign}
        val norm=kotlin.math.sqrt(q.sumOf{it*it})
        if(!norm.isFinite() || norm<.85)return null
        val closest=if(u<=.5)a else b
        return Sample(cameraTimestampNs,
            a.x+(b.x-a.x)*u,a.y+(b.y-a.y)*u,a.z+(b.z-a.z)*u,
            q[0]/norm,q[1]/norm,q[2]/norm,q[3]/norm,
            closest.onlineCamera)
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
