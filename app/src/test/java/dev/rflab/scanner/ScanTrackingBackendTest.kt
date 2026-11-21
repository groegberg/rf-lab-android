package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanTrackingBackendTest {
    @Test fun optionsHaveDistinctSavedKeysAndUserLabels() {
        assertEquals(3, ScanTrackingBackend.entries.size)
        assertEquals(3, ScanTrackingBackend.entries.map {it.name}.toSet().size)
        assertEquals("OPENVINS",ScanTrackingBackend.OPENVINS.name)
        assertTrue(ScanTrackingBackend.OPENVINS.hint.contains("MSCKF"))
    }
}
