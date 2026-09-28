package io.github.rantark.factoryflow.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import io.github.rantark.factoryflow.GlyphBitmap
import io.github.rantark.factoryflow.Platform
import io.github.rantark.factoryflow.game.FactoryGame

/** Rasterises glyphs from the system font with Android's Canvas – no font files shipped. */
class AndroidPlatform(override val versionLabel: String) : Platform {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.WHITE
        isSubpixelText = true
    }
    private val bounds = Rect()

    override fun rasterizeGlyph(ch: Char, px: Int): GlyphBitmap {
        paint.textSize = px.toFloat()
        val s = ch.toString()
        val adv = paint.measureText(s)
        paint.getTextBounds(s, 0, 1, bounds)
        val pad = 3
        val w = maxOf(1, bounds.width() + pad * 2)
        val h = maxOf(1, bounds.height() + pad * 2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(s, (-bounds.left + pad).toFloat(), (-bounds.top + pad).toFloat(), paint)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        val alpha = ByteArray(w * h) { (pixels[it] ushr 24).toByte() }
        return GlyphBitmap(w, h, alpha, adv, bounds.left - pad, -bounds.top + pad)
    }
}

class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            numSamples = 4          // MSAA for smooth vector edges
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
        }
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val label = "${info.versionName} (build ${info.versionCode})"
        initialize(FactoryGame(AndroidPlatform(label)), config)
    }
}
