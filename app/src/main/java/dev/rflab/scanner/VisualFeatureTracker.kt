package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Independent image-only feature tracking diagnostic. A small Shi-Tomasi-style
 * corner detector with 64-bit binary patch descriptors and mutual Hamming
 * matching. It deliberately does NOT pretend to estimate a 6-DoF pose.
 */
internal object VisualFeatureTracker {
    data class Feature(val x: Int, val y: Int, val bits: Long, val strength: Float)
    data class Frame(val width: Int, val height: Int, val features: List<Feature>,
                     val sharpness: Float, val textureCoverage: Float)
    data class Match(val count: Int, val medianMotionPx: Float,
                     val coverage: Float, val ratio: Float)

    private val offsets = Array(64) { i ->
        // Deterministic, compact BRIEF-style patch pairs within radius 7.
        val a = i * 1103515245L + 12345L
        val b = a * 1664525L + 1013904223L
        intArrayOf(((a ushr 9) % 13).toInt() - 6,
            ((a ushr 17) % 13).toInt() - 6,
            ((b ushr 13) % 13).toInt() - 6,
            ((b ushr 23) % 13).toInt() - 6)
    }

    fun analyze(gray: ByteArray, width: Int, height: Int): Frame {
        require(width >= 32 && height >= 32 && gray.size == width * height)
        fun px(x: Int, y: Int) = gray[y * width + x].toInt() and 255
        var edgeSum = 0.0
        var edgeCount = 0
        for (y in 2 until height - 2 step 3) for (x in 2 until width - 2 step 3) {
            val lap = abs(4 * px(x, y) - px(x-1, y) - px(x+1, y) -
                px(x, y-1) - px(x, y+1))
            edgeSum += lap
            edgeCount++
        }
        val sharpness = (edgeSum / edgeCount.coerceAtLeast(1)).toFloat()
        val cellSize = 13
        val cols = (width - 16) / cellSize
        val rows = (height - 16) / cellSize
        val features = ArrayList<Feature>(cols * rows)
        var strongCells = 0
        for (cy in 0 until rows) for (cx in 0 until cols) {
            val left = 8 + cx * cellSize
            val top = 8 + cy * cellSize
            var bestX = -1
            var bestY = -1
            var bestScore = 0f
            for (y in top until min(height - 8, top + cellSize) step 2)
                for (x in left until min(width - 8, left + cellSize) step 2) {
                    var xx = 0.0
                    var yy = 0.0
                    var xy = 0.0
                    for (dy in -1..1) for (dx in -1..1) {
                        val gx = px(x+dx+1,y+dy) - px(x+dx-1,y+dy)
                        val gy = px(x+dx,y+dy+1) - px(x+dx,y+dy-1)
                        xx += gx.toDouble()*gx; yy += gy.toDouble()*gy
                        xy += gx.toDouble()*gy
                    }
                    val determinant = xx * yy - xy * xy
                    val trace = xx + yy
                    val smaller = if (trace > 0.0)
                        (determinant / trace).toFloat() else 0f
                    if (smaller > bestScore) {
                        bestX = x; bestY = y; bestScore = smaller
                    }
                }
            if (bestScore < 130f || bestX < 0) continue
            strongCells++
            var code = 0L
            offsets.forEachIndexed { i, o ->
                if (px(bestX+o[0],bestY+o[1]) <
                    px(bestX+o[2],bestY+o[3]))
                    code = code or (1L shl i)
            }
            features.add(Feature(bestX,bestY,code,bestScore))
        }
        return Frame(width,height,features,sharpness,
            strongCells.toFloat() / (cols * rows).coerceAtLeast(1))
    }

    fun compare(previous: Frame, current: Frame): Match {
        if (previous.width != current.width || previous.height != current.height ||
            previous.features.isEmpty() || current.features.isEmpty())
            return Match(0,0f,0f,0f)
        data class Candidate(val from: Int,val to: Int,val distance: Int)
        val proposals = ArrayList<Candidate>()
        val backwards = IntArray(current.features.size) { -1 }
        for (i in current.features.indices) {
            val b = current.features[i]
            var distance = 65
            var index = -1
            for (j in previous.features.indices) {
                val a = previous.features[j]
                val d = java.lang.Long.bitCount(a.bits xor b.bits)
                if (d < distance) { distance = d; index = j }
            }
            backwards[i] = index
        }
        for (j in previous.features.indices) {
            val a = previous.features[j]
            var best = 65
            var runnerUp = 65
            var index = -1
            for (i in current.features.indices) {
                val b = current.features[i]
                val d = java.lang.Long.bitCount(a.bits xor b.bits)
                if (d < best) {
                    runnerUp = best; best = d; index = i
                } else if (d < runnerUp) runnerUp = d
            }
            if (index < 0 || best >= 22 || best.toFloat() >= runnerUp * .78f ||
                backwards[index] != j) continue
            proposals.add(Candidate(j,index,best))
        }
        if (proposals.isEmpty()) return Match(0,0f,0f,0f)
        val displacements = proposals.map { p ->
            val a = previous.features[p.from]
            val b = current.features[p.to]
            sqrt(((a.x-b.x)*(a.x-b.x) + (a.y-b.y)*(a.y-b.y)).toFloat())
        }.sorted()
        val median = displacements[displacements.size / 2]
        val spatialCells = proposals.map { p ->
            val a = previous.features[p.from]
            (a.y / 24) * 100 + (a.x / 24)
        }.toSet()
        return Match(proposals.size,median,
            spatialCells.size.toFloat() /
                (max(1,previous.width / 24) * max(1,previous.height / 24)),
            proposals.size.toFloat()/previous.features.size)
    }

    /** Only a capture recommendation. Never interprets 2D pixel motion as metres. */
    fun autoEligible(frame: Frame, match: Match?, isFirst: Boolean): Boolean {
        if (frame.sharpness < 9f || frame.features.size < 24 ||
            frame.textureCoverage < .22f) return false
        if (isFirst) return true
        return match != null && match.count >= 14 &&
            match.medianMotionPx in 3.0f..45f && match.ratio >= .15f
    }
}
