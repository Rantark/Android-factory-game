package io.github.rantark.factoryflow.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.rantark.factoryflow.GlyphBitmap
import io.github.rantark.factoryflow.Platform
import io.github.rantark.factoryflow.game.DevOptions
import io.github.rantark.factoryflow.game.FactoryGame
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Rasterises glyphs with Java2D (the desktop equivalent of Android's Canvas text). */
class DesktopPlatform(private val audio: Boolean) : Platform {
    private val font = Font(Font.SANS_SERIF, Font.BOLD, 56)
    private val metricsImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

    override val audioEnabled get() = audio

    override fun rasterizeGlyph(ch: Char, px: Int): GlyphBitmap {
        val f = font.deriveFont(px.toFloat())
        val g0 = metricsImg.createGraphics()
        g0.font = f
        val fm = g0.fontMetrics
        val adv = fm.charWidth(ch).toFloat()
        val gv = f.createGlyphVector(g0.fontRenderContext, ch.toString())
        val bounds = gv.visualBounds
        g0.dispose()
        val pad = 3
        val w = maxOf(1, Math.ceil(bounds.width).toInt() + pad * 2)
        val h = maxOf(1, Math.ceil(bounds.height).toInt() + pad * 2)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.font = f
        g.color = java.awt.Color.WHITE
        val ox = (-bounds.x).toFloat() + pad
        val oy = (-bounds.y).toFloat() + pad
        g.drawString(ch.toString(), ox, oy)
        g.dispose()
        val alpha = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) alpha[y * w + x] = (img.getRGB(x, y) ushr 24).toByte()
        return GlyphBitmap(w, h, alpha, adv, Math.floor(bounds.x).toInt() - pad, Math.ceil(-bounds.y).toInt() + pad)
    }
}

/**
 * Desktop launcher for development. Flags:
 *   --demo            build a showcase factory      --stress   add 240 assemblers
 *   --shot FILE       save a screenshot and exit    --frames N frame to capture
 *   --zoom Z  --night  --panel tech|stats|menu|info|place|build:CATEGORY
 *   --size WxH        window size                   --mute     no audio
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val size = arg("--size")?.split("x")?.map { it.toInt() } ?: listOf(1280, 720)
    val dev = DevOptions(
        demo = "--demo" in args,
        screenshot = arg("--shot"),
        frames = arg("--frames")?.toInt() ?: 90,
        zoom = arg("--zoom")?.toFloat() ?: 0f,
        night = "--night" in args,
        panel = arg("--panel"),
        seed = arg("--seed")?.toLong() ?: 0L,
        fresh = "--fresh" in args,
        stress = "--stress" in args,
        atX = arg("--at")?.split(",")?.get(0)?.toFloat() ?: 0f,
        atY = arg("--at")?.split(",")?.get(1)?.toFloat() ?: 0f,
    )
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Factory Flow (desktop dev build)")
        setWindowedMode(size[0], size[1])
        useVsync(true)
        setForegroundFPS(60)
        setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
        if ("--mute" in args || dev.screenshot != null) disableAudio(true)
    }
    Lwjgl3Application(FactoryGame(DesktopPlatform(!("--mute" in args || dev.screenshot != null)), dev), config)
}
