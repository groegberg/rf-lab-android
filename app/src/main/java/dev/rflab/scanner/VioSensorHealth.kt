package dev.rflab.scanner

/**
 * Real-time capture health telemetry only. This class never invents a
 * camera pose or assumes Camera2/IMU timestamp epochs are identical.
 * Camera and gyro cadence are estimated independently from sensor timestamps.
 */
internal class VioSensorHealth {
    data class Report(val cameraHz: Float, val gyroHz: Float,
                      val frameGaps: Int, val cameraSamples: Int,
                      val gyroSamples: Int)

    private val camera = ArrayDeque<Long>(90)
    private val gyro = ArrayDeque<Long>(300)
    private var gaps = 0

    @Synchronized fun cameraFrame(ns: Long) {
        if (ns <= 0L || (camera.isNotEmpty() && ns <= camera.last())) return
        if (camera.isNotEmpty() && ns - camera.last() > 75_000_000L) gaps++
        camera.addLast(ns)
        if (camera.size > 90) camera.removeFirst()
    }

    @Synchronized fun gyroscope(ns: Long) {
        if (ns <= 0L || (gyro.isNotEmpty() && ns <= gyro.last())) return
        gyro.addLast(ns)
        if (gyro.size > 300) gyro.removeFirst()
    }

    private fun frequency(values: ArrayDeque<Long>): Float {
        if (values.size < 3) return 0f
        val duration = values.last() - values.first()
        if (duration <= 0L) return 0f
        return ((values.size - 1).toDouble() * 1e9 / duration).toFloat()
    }

    @Synchronized fun report() = Report(
        frequency(camera), frequency(gyro), gaps, camera.size, gyro.size)
}
