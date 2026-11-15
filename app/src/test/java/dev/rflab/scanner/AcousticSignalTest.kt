package dev.rflab.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticSignalTest {
    @Test fun generateAudibleBoundedChirp() {
        val sound = AcousticSignal.chirp(48_000, .10)
        assertEquals(4800, sound.size)
        assertTrue(sound.any { it != 0.toShort() })
        assertTrue(sound.maxOf { kotlin.math.abs(it.toInt()) } < 12000)
    }

    @Test fun matchedFilterRecognizesControlledSyntheticEcho() {
        val rate = 48_000
        val emitted = AcousticSignal.chirp(rate, .08)
        val received = ShortArray(48_000)
        val start = 5_500
        val echoLag = 1_440 // 30 ms
        for (i in emitted.indices) {
            received[start + i] =
                (received[start + i] + emitted[i].toInt()).toShort()
            received[start + echoLag + i] =
                (received[start + echoLag + i] +
                    emitted[i] / 2).toShort()
        }
        val result = AcousticSignal.analyze(received, emitted, rate)
        assertTrue(result.directArrivalMs in 110.0..120.0)
        assertTrue(result.strongestEchoAfterMs in 24.0..36.0)
        assertEquals(64, result.envelope.size)
        assertTrue(result.relativeEchoStrength > .2)
    }
}
