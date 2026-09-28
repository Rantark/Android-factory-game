package io.github.rantark.factoryflow

import io.github.rantark.factoryflow.audio.AudioEngine
import io.github.rantark.factoryflow.audio.Hum
import io.github.rantark.factoryflow.audio.Sfx
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Renders the synthesiser offline and checks it is audible but never harsh. */
class AudioTest {
    private fun render(a: AudioEngine, seconds: Float): ShortArray {
        val n = (seconds * AudioEngine.RATE).toInt() / AudioEngine.BUF * AudioEngine.BUF
        val out = ShortArray(n)
        val buf = ShortArray(AudioEngine.BUF)
        var i = 0
        while (i < n) { a.fill(buf); System.arraycopy(buf, 0, out, i, buf.size); i += buf.size }
        return out
    }

    private fun stats(s: ShortArray, from: Int = s.size / 2): Pair<Float, Float> {
        var peak = 0f; var sum = 0.0
        for (k in from until s.size) { val v = s[k] / 32768f; peak = maxOf(peak, abs(v)); sum += v * v }
        return peak to sqrt(sum / (s.size - from)).toFloat()
    }

    @Test
    fun ambienceIsAudibleAndSoft() {
        for (biome in 0..4) for (day in listOf(1f, 0f)) {
            val a = AudioEngine(true)
            a.biome = biome; a.daylight = day
            val (peak, rms) = stats(render(a, 6f))
            println("biome $biome day $day: peak=$peak rms=$rms")
            assertTrue("ambience too quiet ($biome/$day): $rms", rms > 0.003f)
            assertTrue("ambience too loud ($biome/$day): $peak", peak < 0.6f)
        }
    }

    @Test
    fun busyFactoryStaysBelowClipping() {
        val a = AudioEngine(true)
        for (h in Hum.entries) a.hums[h.ordinal] = 1f
        for (s in Sfx.entries) a.play(s)
        val (peak, rms) = stats(render(a, 8f), 0)
        println("full factory: peak=$peak rms=$rms")
        assertTrue(peak < 0.85f)
        assertTrue(rms > 0.01f)
    }

    @Test
    fun uiSoundsAreHeard() {
        // Two identical (deterministically seeded) synths; only one plays the sound.
        // The difference between them is exactly the sound effect.
        for (s in Sfx.entries) {
            val a = AudioEngine(true); val b = AudioEngine(true)
            render(a, 1f); render(b, 1f) // past the start-up fade-in
            a.play(s)
            val x = render(a, 0.6f); val y = render(b, 0.6f)
            var peak = 0f; var sum = 0.0
            for (k in x.indices) { val d = (x[k] - y[k]) / 32768f; peak = maxOf(peak, abs(d)); sum += d * d }
            val rms = sqrt(sum / x.size).toFloat()
            println("$s: peak=$peak rms=$rms")
            assertTrue("$s inaudible", peak > 0.03f)
            assertTrue("$s too loud", peak < 0.7f)
        }
    }
}
