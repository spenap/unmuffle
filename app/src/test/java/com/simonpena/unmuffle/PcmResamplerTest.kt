package com.simonpena.unmuffle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmResamplerTest {
    @Test fun emptyInputIsNull() {
        assertNull(PcmResampler.toWhisperFormat(DecodedAudio(ShortArray(0), 16000, 1)))
    }

    @Test fun mono16kOnlyScales() {
        val out = PcmResampler.toWhisperFormat(
            DecodedAudio(shortArrayOf(0, 16384, -32768), 16000, 1)
        )!!
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f), out, 0f)
    }

    @Test fun stereoAveragesChannels() {
        val out = PcmResampler.toWhisperFormat(
            DecodedAudio(shortArrayOf(16384, 0, -16384, -16384), 16000, 2)
        )!!
        assertArrayEquals(floatArrayOf(0.25f, -0.5f), out, 0f)
    }

    @Test fun opus48kBecomes16k() {
        val second = ShortArray(48000) { 1000 }
        val out = PcmResampler.toWhisperFormat(DecodedAudio(second, 48000, 1))!!
        assertEquals(16000, out.size)
        assertEquals(1000 / 32768f, out[8000], 1e-6f)
    }

    @Test fun cd44kBecomes16k() {
        val out = PcmResampler.toWhisperFormat(DecodedAudio(ShortArray(44100), 44100, 1))!!
        assertEquals(16000, out.size)
    }
}
