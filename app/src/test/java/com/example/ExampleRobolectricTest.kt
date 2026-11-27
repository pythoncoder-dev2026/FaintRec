package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.analysis.FftProcessor
import com.example.analysis.FftWindowType
import com.example.util.AudioMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches FaintRec`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("FaintRec", appName)
    }

    @Test
    fun `fft detects 1000 Hz sine wave accurately`() {
        val sampleRate = 48000
        val n = 2048
        val samples = FloatArray(n) { i ->
            sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat()
        }
        val fft = FftProcessor(n, FftWindowType.HANN)
        val result = fft.analyze(samples, sampleRate)
        assertTrue(
            "Expected peak near 1000 Hz, got ${result.peakFrequencyHz}",
            kotlin.math.abs(result.peakFrequencyHz - 1000.0) < 15.0
        )
    }

    @Test
    fun `soft limiter prevents clipping above ceiling`() {
        val limited = AudioMath.softLimit(10.0f, ceiling = 0.98f)
        assertTrue(limited <= 0.98f)
        assertTrue(limited > 0.9f)
    }
}
