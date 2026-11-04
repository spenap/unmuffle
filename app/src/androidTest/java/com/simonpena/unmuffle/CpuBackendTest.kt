package com.simonpena.unmuffle

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** whisper uses the CPU features the phone has (docs/measurements.md). */
@RunWith(AndroidJUnit4::class)
class CpuBackendTest {

    private val features = File("/proc/cpuinfo").readLines()
        .first { it.startsWith("Features") }.substringAfter(':').trim().split(' ')

    @Test
    fun usesDotProductAndFp16WhereAvailable() {
        assumeTrue("asimddp" in features && "asimdhp" in features)
        val info = Whisper.systemInfo()
        assertTrue(info, "DOTPROD = 1" in info)
        assertTrue(info, "FP16_VA = 1" in info)
    }
}
