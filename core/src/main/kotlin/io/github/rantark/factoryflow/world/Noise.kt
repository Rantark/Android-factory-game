package io.github.rantark.factoryflow.world

import kotlin.math.floor

/**
 * Seeded 2D gradient ("Perlin-style") noise with fractal octaves.
 * Returns values roughly in [0, 1].
 */
class Noise(seed: Long) {
    private val perm = IntArray(512)
    private val gx = FloatArray(256)
    private val gy = FloatArray(256)

    init {
        val rnd = java.util.Random(seed)
        val p = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
        for (i in 0 until 256) {
            val a = rnd.nextDouble() * Math.PI * 2
            gx[i] = Math.cos(a).toFloat(); gy[i] = Math.sin(a).toFloat()
        }
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun grad(ix: Int, iy: Int, x: Float, y: Float): Float {
        val h = perm[perm[ix and 255] + (iy and 255)]
        return gx[h] * x + gy[h] * y
    }

    /** Single octave, output approximately in [-0.7, 0.7]. */
    fun raw(x: Float, y: Float): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val u = fade(fx); val v = fade(fy)
        val n00 = grad(x0, y0, fx, fy)
        val n10 = grad(x0 + 1, y0, fx - 1, fy)
        val n01 = grad(x0, y0 + 1, fx, fy - 1)
        val n11 = grad(x0 + 1, y0 + 1, fx - 1, fy - 1)
        val nx0 = n00 + (n10 - n00) * u
        val nx1 = n01 + (n11 - n01) * u
        return nx0 + (nx1 - nx0) * v
    }

    /** Fractal Brownian motion normalised to about [0, 1]. */
    fun fbm(x: Float, y: Float, octaves: Int = 4, lacunarity: Float = 2f, gain: Float = 0.5f): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
        for (i in 0 until octaves) {
            sum += raw(x * freq + i * 17.3f, y * freq - i * 9.1f) * amp
            norm += amp; amp *= gain; freq *= lacunarity
        }
        return (sum / norm * 1.4f + 0.5f).coerceIn(0f, 1f)
    }
}
