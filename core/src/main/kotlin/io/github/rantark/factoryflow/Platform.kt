package io.github.rantark.factoryflow

/** Coverage bitmap of one rasterised glyph, as produced by the platform's font engine. */
class GlyphBitmap(
    val w: Int,
    val h: Int,
    /** w*h alpha coverage values, row-major, top row first. */
    val alpha: ByteArray,
    /** Horizontal pen advance in pixels. */
    val advance: Float,
    /** Offset of the bitmap's left edge from the pen position. */
    val left: Int,
    /** Distance from the baseline up to the bitmap's top row. */
    val top: Int,
)

/**
 * Services provided by the platform launcher (Android or desktop) to the shared core.
 * Kept tiny: only things that genuinely need native APIs.
 */
interface Platform {
    /** Rasterise a single character from the system sans-serif font at [px] pixels. */
    fun rasterizeGlyph(ch: Char, px: Int): GlyphBitmap

    /** Whether to try opening an audio device (false on headless test machines). */
    val audioEnabled: Boolean get() = true
}
