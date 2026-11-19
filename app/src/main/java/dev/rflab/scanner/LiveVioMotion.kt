package dev.rflab.scanner

import kotlin.math.sqrt

/** Monitors camera timestamps and indoor translation in unvalidated VIO units. */
internal class LiveVioMotion {
    enum class Result { ACCEPT, REPEAT, REJECT }

    private var lastNs = 0L
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var startNs = 0L
    private var distance = 0.0
    private var fastWindows = 0
    var rejected = false
        private set

    fun observe(ns: Long, x: Double, y: Double, z: Double): Result {
        if (rejected) return Result.REJECT
        if (ns == lastNs && lastNs > 0L) return Result.REPEAT
        if (ns <= lastNs || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            rejected = true
            return Result.REJECT
        }
        val dt = (ns - lastNs) * 1e-9
        if (lastNs == 0L || dt > .5) {
            // A delayed UI poll can skip camera frames. The full-rate offline audit
            // and the native worker watchdog independently detect real capture gaps.
            startNs = ns
            distance = 0.0
            fastWindows = 0
        } else {
            val dx = x-lastX
            val dy = y-lastY
            val dz = z-lastZ
            val step = sqrt(dx*dx + dy*dy + dz*dz)
            if (step > MAX_STEP_SPEED * dt) rejected = true
            distance += step
            val duration = (ns-startNs) * 1e-9
            if (duration >= .5) {
                fastWindows = if (distance > MAX_SUSTAINED_SPEED * duration) fastWindows+1 else 0
                if (fastWindows >= 2) rejected = true
                startNs = ns
                distance = 0.0
            }
        }
        lastNs = ns
        lastX = x
        lastY = y
        lastZ = z
        return if (rejected) Result.REJECT else Result.ACCEPT
    }

    companion object {
        const val MAX_SUSTAINED_SPEED = 4.5
        const val MAX_STEP_SPEED = 12.0
    }
}
