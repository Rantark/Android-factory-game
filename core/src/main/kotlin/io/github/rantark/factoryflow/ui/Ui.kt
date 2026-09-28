package io.github.rantark.factoryflow.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.HdpiUtils
import com.badlogic.gdx.math.MathUtils
import io.github.rantark.factoryflow.gfx.Col
import io.github.rantark.factoryflow.gfx.Font
import io.github.rantark.factoryflow.gfx.VectorBatch
import io.github.rantark.factoryflow.render.Painter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Colours of the interface: warm, readable, high contrast on dark panels. */
object Theme {
    const val PANEL = 0x1D2026
    const val PANEL_EDGE = 0x3A3F4A
    const val BUTTON = 0x2B3038
    const val BUTTON_HI = 0x3B4350
    const val ACCENT = 0xFFB703
    const val TEXT = 0xF1EADF
    const val DIM = 0x9C978F
    const val GOOD = 0x7BD389
    const val BAD = 0xFF6B6B
    const val WARN = 0xFFC145
    const val INFO = 0x8EC5FF
}

/**
 * Minimal immediate-mode UI. Every frame the HUD re-declares its widgets; taps
 * collected by [UiInput] are matched against them. Rectangles registered with [block]
 * swallow touches so they never reach the world underneath.
 *
 * Coordinates are screen pixels with y pointing up; [u] is the size unit that keeps
 * everything physically large on phones.
 */
class Ui(val b: VectorBatch, val font: Font, val painter: Painter) {
    var u = 1f
    var w = 0f
    var h = 0f

    private val taps = ArrayList<FloatArray>()
    private var blockers = ArrayList<FloatArray>()
    private var prevBlockers = ArrayList<FloatArray>()

    /** Scrollable regions: id → rect + axis + limits. */
    class Scroll(val id: String) {
        var x = 0f; var y = 0f; var w = 0f; var h = 0f
        var offX = 0f; var offY = 0f
        var maxX = 0f; var maxY = 0f
        var horizontal = false; var vertical = false
        var dismissible = false
        var velX = 0f; var velY = 0f
        var alive = false
    }
    private val scrolls = HashMap<String, Scroll>()
    /** Set when the user swipes down on a dismissible panel. */
    var dismissed: String? = null

    fun beginFrame(width: Float, height: Float) {
        w = width; h = height
        u = max(1f, min(w, h) / 360f)
        val t = prevBlockers; prevBlockers = blockers; blockers = t; blockers.clear()
        for (s in scrolls.values) {
            // Gentle inertia after a flick.
            if (s.alive && (abs(s.velX) > 1f || abs(s.velY) > 1f)) {
                s.offX = (s.offX + s.velX * Gdx.graphics.deltaTime).coerceIn(0f, s.maxX)
                s.offY = (s.offY + s.velY * Gdx.graphics.deltaTime).coerceIn(0f, s.maxY)
                s.velX *= 0.9f; s.velY *= 0.9f
            }
            s.alive = false
        }
    }

    fun endFrame() { taps.clear() }

    fun addTap(x: Float, y: Float) { taps.add(floatArrayOf(x, y)) }

    /** True (and consumes the tap) if a tap landed in the rectangle this frame. */
    /** Position of the most recently consumed tap. */
    var lastTapX = 0f
    var lastTapY = 0f

    fun tapped(x: Float, y: Float, w: Float, h: Float): Boolean {
        val it = taps.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t[0] >= x && t[0] <= x + w && t[1] >= y && t[1] <= y + h) {
                it.remove(); lastTapX = t[0]; lastTapY = t[1]; return true
            }
        }
        return false
    }

    /** Swallow any remaining taps (used by modal overlays). */
    fun eatTaps() = taps.clear()

    fun block(x: Float, y: Float, w: Float, h: Float) { blockers.add(floatArrayOf(x, y, w, h)) }

    fun blocks(px: Float, py: Float): Boolean =
        prevBlockers.any { px >= it[0] && px <= it[0] + it[2] && py >= it[1] && py <= it[1] + it[3] }

    fun scroll(id: String, x: Float, y: Float, w: Float, h: Float, contentW: Float, contentH: Float,
               horizontal: Boolean = false, vertical: Boolean = false, dismissible: Boolean = false): Scroll {
        val s = scrolls.getOrPut(id) { Scroll(id) }
        s.x = x; s.y = y; s.w = w; s.h = h
        s.maxX = max(0f, contentW - w); s.maxY = max(0f, contentH - h)
        s.horizontal = horizontal; s.vertical = vertical; s.dismissible = dismissible
        s.offX = s.offX.coerceIn(0f, s.maxX); s.offY = s.offY.coerceIn(0f, s.maxY)
        s.alive = true
        return s
    }

    fun resetScroll(id: String) { scrolls[id]?.let { it.offX = 0f; it.offY = 0f; it.velX = 0f; it.velY = 0f } }

    internal fun scrollAt(px: Float, py: Float): Scroll? =
        scrolls.values.lastOrNull { it.alive && px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h }
            ?: scrolls.values.firstOrNull { it.dismissible && px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h }

    // ---- Clipping -------------------------------------------------------------------

    fun clip(x: Float, y: Float, w: Float, h: Float, block: () -> Unit) {
        b.flush()
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
        HdpiUtils.glScissor(x.toInt(), y.toInt(), max(0, w.toInt()), max(0, h.toInt()))
        block()
        b.flush()
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
    }

    // ---- Drawing helpers ------------------------------------------------------------

    fun panel(x: Float, y: Float, w: Float, h: Float, alpha: Float = 0.94f, radius: Float = 14f) {
        b.roundRect(x + 2 * u, y - 3 * u, w, h, radius * u, Col.pack(0x000000, 0.35f * alpha))
        b.roundRect(x, y, w, h, radius * u, Col.pack(Theme.PANEL, alpha), Col.pack(Col.lighten(Theme.PANEL, 0.06f), alpha))
        b.roundRectOutline(x, y, w, h, radius * u, 1.2f * u, Col.pack(Theme.PANEL_EDGE, alpha))
        block(x, y, w, h)
    }

    fun text(s: CharSequence, x: Float, y: Float, size: Float, color: Int = Theme.TEXT, align: Int = -1, alpha: Float = 1f) =
        font.draw(b, s, x, y, size * u, Col.pack(color, alpha), align)

    fun textW(s: CharSequence, size: Float) = font.width(s, size * u)

    /**
     * Button with optional label and custom icon. Returns true when tapped.
     * [icon] receives (cx, cy, size) to draw itself.
     */
    fun button(x: Float, y: Float, w: Float, h: Float, label: String? = null, selected: Boolean = false,
               enabled: Boolean = true, accent: Int = Theme.ACCENT, labelSize: Float = 11f,
               icon: ((Float, Float, Float) -> Unit)? = null): Boolean {
        val base = if (selected) Col.lighten(Theme.BUTTON_HI, 0.05f) else Theme.BUTTON
        b.roundRect(x, y - 1.5f * u, w, h, 10 * u, Col.pack(0x000000, 0.3f))
        b.roundRect(x, y, w, h, 10 * u, Col.pack(Col.darken(base, 0.15f)), Col.pack(Col.lighten(base, 0.06f)))
        if (selected) b.roundRectOutline(x, y, w, h, 10 * u, 2f * u, Col.pack(accent))
        val a = if (enabled) 1f else 0.4f
        if (icon != null) {
            val isz = min(w, h - (if (label != null) 14 * u else 0f)) * 0.62f
            val icy = if (label != null) y + h - (h - 14 * u) / 2f - 1f * u else y + h / 2
            icon(x + w / 2, icy, isz)
            if (label != null) text(label, x + w / 2, y + 5 * u, labelSize * 0.8f, if (selected) accent else Theme.TEXT, 0, a)
        } else if (label != null) {
            text(label, x + w / 2, y + h / 2 - labelSize * u / 2, labelSize, if (selected) accent else Theme.TEXT, 0, a)
        }
        block(x, y, w, h)
        return enabled && tapped(x, y, w, h)
    }

    fun progressBar(x: Float, y: Float, w: Float, h: Float, t: Float, color: Int) {
        b.roundRect(x, y, w, h, h / 2, Col.pack(0x0E0F12, 0.9f))
        if (t > 0.001f) b.roundRect(x, y, max(h, w * t.coerceIn(0f, 1f)), h, h / 2, Col.pack(Col.darken(color, 0.2f)), Col.pack(Col.lighten(color, 0.15f)))
    }

    // ---- Vector icons for buttons ---------------------------------------------------

    fun iconUndo(cx: Float, cy: Float, s: Float, c: Int = Theme.TEXT) {
        val col = Col.pack(c)
        b.arc(cx, cy - s * 0.05f, s * 0.26f, s * 0.38f, MathUtils.PI * 0.95f, -MathUtils.PI * 1.25f, col)
        b.arrowHead(cx - s * 0.33f, cy + s * 0.05f, s * 0.16f, MathUtils.PI * 1.5f + 0.3f, col)
    }

    fun iconRotate(cx: Float, cy: Float, s: Float, c: Int = Theme.TEXT) {
        val col = Col.pack(c)
        b.arc(cx, cy, s * 0.26f, s * 0.38f, MathUtils.PI * 0.6f, -MathUtils.PI * 1.5f, col)
        b.arrowHead(cx - s * 0.3f, cy + s * 0.2f, s * 0.16f, MathUtils.PI * 0.4f + MathUtils.PI, col)
    }

    fun iconCross(cx: Float, cy: Float, s: Float, c: Int = Theme.TEXT) {
        val col = Col.pack(c)
        b.line(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f, s * 0.12f, col)
        b.line(cx - s * 0.3f, cy + s * 0.3f, cx + s * 0.3f, cy - s * 0.3f, s * 0.12f, col)
    }

    fun iconCheck(cx: Float, cy: Float, s: Float, c: Int = Theme.GOOD) {
        val col = Col.pack(c)
        b.capsule(cx - s * 0.32f, cy, cx - s * 0.08f, cy - s * 0.25f, s * 0.12f, col)
        b.capsule(cx - s * 0.08f, cy - s * 0.25f, cx + s * 0.35f, cy + s * 0.3f, s * 0.12f, col)
    }

    fun iconBulldoze(cx: Float, cy: Float, s: Float, c: Int = Theme.TEXT) {
        val col = Col.pack(c)
        b.roundRect(cx - s * 0.32f, cy - s * 0.1f, s * 0.44f, s * 0.3f, s * 0.05f, col)
        b.roundRect(cx - s * 0.2f, cy + s * 0.2f, s * 0.2f, s * 0.14f, s * 0.03f, col)
        b.quad(cx + s * 0.18f, cy - s * 0.25f, cx + s * 0.4f, cy - s * 0.3f, cx + s * 0.4f, cy + s * 0.22f, cx + s * 0.18f, cy + s * 0.1f, col)
        b.capsule(cx - s * 0.36f, cy - s * 0.24f, cx + s * 0.12f, cy - s * 0.24f, s * 0.14f, Col.pack(Col.darken(c, 0.3f)))
    }

    fun iconFlask(cx: Float, cy: Float, s: Float, c: Int = 0xB388FF) {
        b.rect(cx - s * 0.08f, cy + s * 0.05f, s * 0.16f, s * 0.3f, Col.pack(Theme.TEXT))
        b.tri(cx - s * 0.32f, cy - s * 0.32f, cx + s * 0.32f, cy - s * 0.32f, cx, cy + s * 0.12f, Col.pack(Theme.TEXT))
        b.tri(cx - s * 0.24f, cy - s * 0.27f, cx + s * 0.24f, cy - s * 0.27f, cx, cy + s * 0.02f, Col.pack(c))
    }

    fun iconStats(cx: Float, cy: Float, s: Float) {
        val cols = intArrayOf(Theme.GOOD, Theme.ACCENT, Theme.INFO)
        val hs = floatArrayOf(0.35f, 0.6f, 0.45f)
        for (k in 0 until 3) {
            b.roundRect(cx - s * 0.33f + k * s * 0.24f, cy - s * 0.32f, s * 0.18f, s * hs[k], s * 0.04f, Col.pack(cols[k]))
        }
    }

    fun iconMenu(cx: Float, cy: Float, s: Float) {
        for (k in -1..1) b.capsule(cx - s * 0.28f, cy + k * s * 0.22f, cx + s * 0.28f, cy + k * s * 0.22f, s * 0.1f, Col.pack(Theme.TEXT))
    }

    fun iconSpeed(cx: Float, cy: Float, s: Float, n: Int) {
        val col = Col.pack(Theme.TEXT)
        val count = when (n) { 1 -> 1; 2 -> 2; else -> 3 }
        val w = s * 0.24f
        val start = cx - (count * w) / 2f
        for (k in 0 until count) {
            val x0 = start + k * w
            b.tri(x0, cy - s * 0.22f, x0, cy + s * 0.22f, x0 + w * 1.1f, cy, col)
        }
    }

    fun iconSave(cx: Float, cy: Float, s: Float) {
        b.roundRect(cx - s * 0.3f, cy - s * 0.3f, s * 0.6f, s * 0.6f, s * 0.06f, Col.pack(Theme.INFO))
        b.rect(cx - s * 0.18f, cy + s * 0.08f, s * 0.36f, s * 0.2f, Col.pack(Theme.PANEL))
        b.rect(cx - s * 0.16f, cy - s * 0.26f, s * 0.32f, s * 0.22f, Col.pack(Theme.TEXT))
    }

    fun iconSun(cx: Float, cy: Float, s: Float, daylight: Float) {
        if (daylight > 0.4f) {
            b.glow(cx, cy, s * 0.6f, 0xFFD166, 0.35f)
            b.circle(cx, cy, s * 0.22f, Col.pack(0xFFD166))
            for (k in 0 until 8) {
                val a = k * MathUtils.PI2 / 8
                b.line(cx + MathUtils.cos(a) * s * 0.3f, cy + MathUtils.sin(a) * s * 0.3f,
                    cx + MathUtils.cos(a) * s * 0.42f, cy + MathUtils.sin(a) * s * 0.42f, s * 0.06f, Col.pack(0xFFD166))
            }
        } else {
            b.circle(cx, cy, s * 0.3f, Col.pack(0xDDE6F5))
            b.circle(cx + s * 0.13f, cy + s * 0.1f, s * 0.26f, Col.pack(Theme.BUTTON))
        }
    }
}

/**
 * Routes raw touches to the UI when they start on a panel. Short touches become taps,
 * drags scroll the panel underneath, and a downward swipe dismisses a panel.
 */
class UiInput(private val ui: Ui) : InputAdapter() {
    private class Track(val sx: Float, val sy: Float, val scroll: Ui.Scroll?) {
        var lx = sx; var ly = sy; var moved = false; var lastT = System.nanoTime()
    }
    private val tracks = HashMap<Int, Track>()

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val x = screenX.toFloat(); val y = ui.h - screenY
        if (!ui.blocks(x, y)) return false
        val sc = ui.scrollAt(x, y)
        sc?.let { it.velX = 0f; it.velY = 0f }
        tracks[pointer] = Track(x, y, sc)
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        val t = tracks[pointer] ?: return false
        val x = screenX.toFloat(); val y = ui.h - screenY
        if (abs(x - t.sx) > SLOP * ui.u || abs(y - t.sy) > SLOP * ui.u) t.moved = true
        val s = t.scroll
        if (s != null && t.moved) {
            val dx = x - t.lx; val dy = y - t.ly
            val now = System.nanoTime(); val dtS = max(1e-3f, (now - t.lastT) / 1e9f)
            if (s.horizontal) { s.offX = (s.offX - dx).coerceIn(0f, s.maxX); s.velX = -dx / dtS * 0.5f }
            if (s.vertical) { s.offY = (s.offY + dy).coerceIn(0f, s.maxY); s.velY = dy / dtS * 0.5f }
            t.lastT = now
            // A mostly-vertical downward swipe dismisses bottom sheets.
            if (s.dismissible && !s.vertical && (t.sy - y) > 60 * ui.u && abs(x - t.sx) < (t.sy - y)) {
                ui.dismissed = s.id
                tracks.remove(pointer)
            }
        }
        t.lx = x; t.ly = y
        return true
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val t = tracks.remove(pointer) ?: return false
        if (!t.moved) ui.addTap(t.sx, t.sy)
        return true
    }

    companion object { const val SLOP = 9f }
}
