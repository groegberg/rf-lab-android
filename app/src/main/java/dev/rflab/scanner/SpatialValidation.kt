package dev.rflab.scanner

/**
 * Evaluation with whole reference positions held out. Testing against a
 * measurement from the same anchor would dramatically overstate accuracy.
 */
data class SpatialValidation(
    val tested: Int, val correct: Int, val wrong: Int,
    val unknown: Int, val confusion: Map<Pair<String, String>, Int>
) {
    val accuracy: Double get() = if (tested == 0) 0.0 else correct.toDouble() / tested
    val coverage: Double get() = if (tested == 0) 0.0 else (correct + wrong).toDouble() / tested
}

class SpatialValidator(private val engine: SpatialLocator = SpatialLocator()) {
    fun validate(rooms: List<HomeRoom>, anchors: List<HomeAnchor>): SpatialValidation {
        val confusion = linkedMapOf<Pair<String, String>, Int>()
        var tested = 0
        var correct = 0
        var wrong = 0
        var unknown = 0
        val now = System.currentTimeMillis()
        for (heldOut in anchors) {
            if (heldOut.sweeps.isEmpty() || heldOut.median().size < 3) continue
            if (anchors.none { it.id != heldOut.id && it.roomId == heldOut.roomId &&
                    it.sweeps.isNotEmpty() }) continue
            val training = anchors.filter { it.id != heldOut.id }
            if (training.map { it.roomId }.distinct().size < 2) continue
            val sample = RfSweep(now, heldOut.median(), emptyMap())
            val result = engine.estimate(rooms, training, sample, now)
            tested++
            when {
                result.roomId == null -> unknown++
                result.roomId == heldOut.roomId -> correct++
                else -> wrong++
            }
            val key = Pair(heldOut.roomId, result.roomId ?: "UNKNOWN")
            confusion[key] = (confusion[key] ?: 0) + 1
        }
        return SpatialValidation(tested, correct, wrong, unknown, confusion)
    }
}
