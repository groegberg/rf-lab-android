package dev.rflab.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialLocatorTest {
    private val now = 1_900_000_000_000L
    private val rooms = listOf(
        HomeRoom("living", "Wohnzimmer", 0f, 0f, 4f, 4f),
        HomeRoom("kitchen", "Küche", 5f, 0f, 4f, 4f)
    )
    private fun point(id: String, room: String, offset: Int): HomeAnchor {
        val base = if (room == "living") listOf(-40, -52, -68, -78)
            else listOf(-79, -75, -44, -48)
        val a = HomeAnchor(id, room, id, 1f, 1f)
        a.sweeps.add(RfSweep(now,
            (0..3).associate { "bssid-" + it to (base[it] + offset) }, emptyMap()))
        return a
    }
    private val anchors = listOf(
        point("L1", "living", 0), point("L2", "living", 2),
        point("K1", "kitchen", 0), point("K2", "kitchen", 2)
    )

    @Test fun recognizesSeparatedRoomFingerprints() {
        val scan = RfSweep(now, (0..3).associate { "bssid-" + it to
            listOf(-41, -54, -67, -80)[it] }, emptyMap())
        val result = SpatialLocator().estimate(rooms, anchors, scan, now)
        assertEquals("living", result.roomId)
        assertEquals("OK", result.quality)
        assertTrue(result.confidence >= .8)
    }
    @Test fun rejectsStaleAndSparseScans() {
        val current = RfSweep(now, mapOf("a" to -45, "b" to -55), emptyMap())
        assertEquals("ZU_WENIGE_APS", SpatialLocator().estimate(rooms, anchors, current, now).quality)
        val old = RfSweep(now - 150_000, anchors.first().median(), emptyMap())
        assertEquals("SCAN_VERALTET", SpatialLocator().estimate(rooms, anchors, old, now).quality)
    }
    @Test fun requiresDistinctCalibratedRooms() {
        val scan = RfSweep(now, anchors[0].median(), emptyMap())
        val value = SpatialLocator().estimate(rooms, anchors.take(2), scan, now)
        assertEquals("NUR_EIN_RAUM_KALIBRIERT", value.quality)
        assertFalse(value.usable(now))
    }
    @Test fun holdoutValidationDoesNotTrainOnTestedPosition() {
        val value = SpatialValidator().validate(rooms, anchors)
        assertEquals(4, value.tested)
        assertEquals(4, value.correct)
        assertEquals(0, value.wrong)
    }
}
