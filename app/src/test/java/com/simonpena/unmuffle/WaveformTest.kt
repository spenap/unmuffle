package com.simonpena.unmuffle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformTest {
    @Test fun oneBarPerBucketScaledToTheLoudest() {
        val samples = floatArrayOf(0.1f, -0.2f, 0.5f, -0.4f, 0f, 0.05f, -1f, 0.2f)
        assertArrayEquals(floatArrayOf(0.2f, 0.5f, 0.05f, 1f), Waveform.bars(samples, 4), 1e-6f)
    }

    @Test fun silenceIsFlat() {
        assertArrayEquals(FloatArray(3), Waveform.bars(FloatArray(300), 3), 0f)
    }

    @Test fun shortAudioStillFillsEveryBar() {
        assertEquals(40, Waveform.bars(floatArrayOf(0.5f, 1f), 40).size)
    }

    @Test fun timesAsMinutesAndSeconds() {
        assertEquals("0:00", Waveform.time(0f))
        assertEquals("0:38", Waveform.time(38.9f))
        assertEquals("1:10", Waveform.time(70.4f))
    }
}
