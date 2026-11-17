package dev.rflab.scanner

import android.graphics.Bitmap
import java.io.File

/**
 * Experimental RGB VIO + monocular DA3 point samples (NOT a metric map).
 *
 * The one-image DA3-Small model produces relative inverse depth and lacks
 * shared scale/shift between frames. Normalize each valid keyframe about
 * its own median inverse depth, with a deliberately explicit reference
 * distance (in UNVALIDATED VIO units). This makes a first colored 3D preview
 * possible, but must not be presented as room dimensions or a watertight mesh.
 *
 * A subsequent milestone can solve scale/shift per overlapping frame using
 * OpenVINS triangulated landmarks or robust multi-view reprojection.
 */
internal object OpenVinsDa3Fusion {
    const val REFERENCE_DISTANCE_VIO = 2.0
    private const val MIN_DEPTH_VIO = .35
    private const val MAX_DEPTH_VIO = 7.0
    private const val SAMPLING_STEP = 12

    fun calibration(session: File): OpenVinsDa3Geometry.Calibration? {
        val source = File(session, "external_calibration/kalibr_imucam_chain.yaml")
        if (!source.isFile || source.length() !in 100..(256 * 1024)) return null
        return OpenVinsDa3Geometry.calibration(source.readText())
    }

    fun sample(
        result: Da3DepthEngine.Prediction,
        pose: ExternalVioTrajectory.Sample,
        calibration: OpenVinsDa3Geometry.Calibration,
        sourceWidth: Int, sourceHeight: Int,
        lowInverse: Float, highInverse: Float, medianInverse: Float
    ): List<SurveyDepth> {
        if (!calibration.compatibleWith(sourceWidth, sourceHeight) ||
            !lowInverse.isFinite() || !highInverse.isFinite() ||
            !medianInverse.isFinite() || medianInverse <= 0f ||
            highInverse <= lowInverse * 1.025f) return emptyList()
        val w = Da3DepthEngine.WIDTH
        val h = Da3DepthEngine.HEIGHT
        val inverse = result.relativeDepth
        if (inverse.size != w * h || result.image.width != w ||
            result.image.height != h) return emptyList()
        val points = ArrayList<SurveyDepth>(w * h / (SAMPLING_STEP * SAMPLING_STEP))
        val edgeX = (w * .04).toInt()
        val edgeY = (h * .04).toInt()
        for (v in edgeY until h - edgeY step SAMPLING_STEP) {
            for (u in edgeX until w - edgeX step SAMPLING_STEP) {
                val d = inverse[v * w + u]
                // Percentile clipping keeps highly uncertain/hallucinated
                // distant and near surfaces out of the preliminary cloud.
                if (!d.isFinite() || d < lowInverse || d > highInverse ||
                    d <= 0f) continue
                val distance = REFERENCE_DISTANCE_VIO * medianInverse / d
                if (distance !in MIN_DEPTH_VIO..MAX_DEPTH_VIO) continue
                val pixel = OpenVinsDa3Geometry.sourcePixel(u.toDouble(),v.toDouble(),
                    sourceWidth,sourceHeight,w,h) ?: continue
                val xyz = OpenVinsDa3Geometry.mapPoint(pose,calibration,
                    pixel[0],pixel[1],sourceWidth,sourceHeight,distance) ?: continue
                val argb = result.image.getPixel(u,v) or (255 shl 24)
                points.add(SurveyDepth(xyz[0],xyz[1],xyz[2],
                    confidence=.34f,argb=argb,rawDepth=false))
            }
        }
        return points
    }
}
