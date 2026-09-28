package io.github.rantark.factoryflow.gfx

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import io.github.rantark.factoryflow.GlyphBitmap
import io.github.rantark.factoryflow.Platform

/**
 * Runtime-built font atlas. Glyph coverage bitmaps are produced by the platform's own
 * text rasteriser (Android Canvas / Java2D) from the system font – no font files ship
 * with the game. The atlas also contains an opaque white block used for every shape.
 */
class Font(platform: Platform) : Disposable {
    class Glyph(val u1: Float, val v1: Float, val u2: Float, val v2: Float,
                val w: Int, val h: Int, val left: Int, val top: Int, val advance: Float)

    val texture: Texture
    private val glyphs = arrayOfNulls<Glyph>(256)
    /** Glyphs outside Latin-1 (dashes, arrows, ellipsis). */
    private val extra = HashMap<Char, Glyph>()
    val basePx = 56
    val ascent: Float
    val whiteU: Float
    val whiteV: Float

    init {
        val size = 1024
        // Build the atlas in a plain int array (0xRRGGBBAA) and upload it in one go.
        val px = IntArray(size * size)
        // White block in the top-left corner for untextured geometry.
        for (y in 0 until 32) for (x in 0 until 32) px[y * size + x] = -1
        whiteU = 16f / size; whiteV = 16f / size

        var penX = 40; var penY = 2; var rowH = 0
        var asc = basePx * 0.75f
        val chars = (32..126).map { it.toChar() } + listOf('×', '·', '°', '–', '—', '→', '…')
        for (ch in chars) {
            val g: GlyphBitmap = platform.rasterizeGlyph(ch, basePx)
            if (ch == 'H') asc = g.top.toFloat()
            if (penX + g.w + 4 > size) { penX = 2; penY += rowH + 4; rowH = 0 }
            if (penY < 36 && penX < 36) penX = 40
            for (yy in 0 until g.h) for (xx in 0 until g.w) {
                val a = g.alpha[yy * g.w + xx].toInt() and 0xFF
                if (a != 0 && penX + xx < size && penY + yy < size) px[(penY + yy) * size + penX + xx] = (0xFFFFFF00.toInt()) or a
            }
            val glyph = Glyph(
                penX.toFloat() / size, penY.toFloat() / size,
                (penX + g.w).toFloat() / size, (penY + g.h).toFloat() / size,
                g.w, g.h, g.left, g.top, g.advance)
            if (ch.code < 256) glyphs[ch.code] = glyph else extra[ch] = glyph
            penX += g.w + 4
            rowH = maxOf(rowH, g.h)
        }
        ascent = asc
        val pm = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val buf = pm.pixels.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.position(0)
        buf.asIntBuffer().put(px)
        texture = Texture(pm, true)
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        pm.dispose()
    }

    private fun glyph(c: Char): Glyph? = if (c.code < 256) glyphs[c.code] else extra[c] ?: glyphs['?'.code]

    /** Width of [text] rendered at [size] (cap-height based size in world/screen units). */
    fun width(text: CharSequence, size: Float): Float {
        val s = size / ascent
        var w = 0f
        for (c in text) w += (glyph(c)?.advance ?: 0f) * s
        return w
    }

    /**
     * Draw [text] with its baseline at [y]. [size] is the cap height. [align] -1 left,
     * 0 centre, 1 right. Returns the drawn width.
     */
    fun draw(b: Shapes, text: CharSequence, x: Float, y: Float, size: Float, color: Float, align: Int = -1): Float {
        val s = size / ascent
        val w = width(text, size)
        var pen = when (align) { 0 -> x - w / 2; 1 -> x - w; else -> x }
        for (c in text) {
            val g = glyph(c) ?: continue
            if (g.w > 0 && c != ' ') {
                b.texQuad(pen + g.left * s, y + (g.top - g.h) * s, g.w * s, g.h * s, g.u1, g.v1, g.u2, g.v2, color)
            }
            pen += g.advance * s
        }
        return w
    }

    /** Draw text with a soft dark drop shadow for readability over the world. */
    fun drawShadow(b: Shapes, text: CharSequence, x: Float, y: Float, size: Float, color: Float, align: Int = -1): Float {
        val o = size * 0.08f
        draw(b, text, x + o, y - o, size, SHADOW, align)
        return draw(b, text, x, y, size, color, align)
    }

    /** Word-wrap [text] into lines no wider than [maxW]. */
    fun wrap(text: String, size: Float, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = StringBuilder()
            for (word in para.split(' ')) {
                val trial = if (line.isEmpty()) word else "$line $word"
                if (width(trial, size) > maxW && line.isNotEmpty()) {
                    out.add(line.toString()); line = StringBuilder(word)
                } else { line.clear(); line.append(trial) }
            }
            out.add(line.toString())
        }
        return out
    }

    override fun dispose() = texture.dispose()

    companion object {
        val SHADOW = Col.pack(0x000000, 0.55f)
    }
}
