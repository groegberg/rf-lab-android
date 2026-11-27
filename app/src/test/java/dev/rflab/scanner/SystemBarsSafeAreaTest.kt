package dev.rflab.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemBarsSafeAreaTest {
    @Test fun preservesOriginalPaddingAndAddsStatusNavigationBars() {
        val original = SystemBarsSafeArea.Padding(12, 7, 12, 9)
        val system = SystemBarsSafeArea.Padding(0, 80, 0, 64)
        assertEquals(SystemBarsSafeArea.Padding(12,87,12,73),
            original.plus(system))
    }

    @Test fun landscapeCutoutsRespectBothSides() {
        val original = SystemBarsSafeArea.Padding(6,6,6,6)
        val cutout = SystemBarsSafeArea.Padding(70,0,38,22)
        assertEquals(SystemBarsSafeArea.Padding(76,6,44,28),
            original.plus(cutout))
    }

    @Test fun repeatedInsetDispatchDoesNotAccumulate() {
        val original = SystemBarsSafeArea.Padding(10,10,10,10)
        val insets = SystemBarsSafeArea.Padding(0,45,0,30)
        val first = original.plus(insets)
        val second = original.plus(insets)
        assertEquals(first,second)
        assertEquals(55,second.top)
        assertEquals(40,second.bottom)
    }
}
