package dev.rflab.scanner

/**
 * OpenVINS uses a gravity-aligned Z-up world (X/Y horizontal).
 * The RF Lab map renderer expects a right-handed Y-up world (X/Z floor).
 * This fixed -90 degree X-axis rotation only changes the visualization axes:
 * it does NOT register OpenVINS against ARCore or validate metric scale.
 */
internal object OpenVinsPoseTransform {
    fun toMap(position: DoubleArray): FloatArray? {
        if (position.size != 3 || !position.all { it.isFinite() }) return null
        // (x,y,z) Z-up -> (x,z,-y) Y-up, det(rotation)=+1.
        val converted = floatArrayOf(position[0].toFloat(),
            position[2].toFloat(), (-position[1]).toFloat())
        return if (converted.all { it.isFinite() }) converted else null
    }

    /** Does not invent orientation when native VIO has not initialized. */
    fun validQuaternion(quaternion: DoubleArray): Boolean {
        if (quaternion.size != 4 || !quaternion.all { it.isFinite() })
            return false
        val squared = quaternion.sumOf { it * it }
        return squared in .81..1.21
    }
}
