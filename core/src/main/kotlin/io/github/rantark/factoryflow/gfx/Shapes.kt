package io.github.rantark.factoryflow.gfx

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.NumberUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Colour helpers. Colours are handled as ints in 0xRRGGBB form plus a separate alpha,
 * and converted to libGDX "packed float" colours (ABGR bits in a float) for vertices.
 */
object Col {
    fun pack(rgb: Int, a: Float = 1f): Float {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val ai = (a.coerceIn(0f, 1f) * 255f).toInt()
        return NumberUtils.intToFloatColor((ai shl 24) or (b shl 16) or (g shl 8) or r)
    }

    fun pack(r: Float, g: Float, b: Float, a: Float): Float {
        val ri = (r.coerceIn(0f, 1f) * 255f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).toInt()
        val ai = (a.coerceIn(0f, 1f) * 255f).toInt()
        return NumberUtils.intToFloatColor((ai shl 24) or (bi shl 16) or (gi shl 8) or ri)
    }

    /** Linear blend between two rgb ints. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = ((a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * tt).toInt()
        val g = ((a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * tt).toInt()
        val bb = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * tt).toInt()
        return (r shl 16) or (g shl 8) or bb
    }

    fun lighten(c: Int, t: Float) = mix(c, 0xFFFFFF, t)
    fun darken(c: Int, t: Float) = mix(c, 0x000000, t)
}

/**
 * Procedural vector geometry writer. Every primitive is emitted as coloured triangles
 * (x, y, packedColour, u, v) so that shapes and text share one vertex format, one
 * shader and one texture – letting a whole frame's buildings draw in a handful of calls.
 *
 * Subclasses decide what happens when the buffer fills: [VectorBatch] flushes to the
 * GPU, [MeshBuilder] grows so it can bake static chunk meshes.
 */
abstract class Shapes(initialFloats: Int) {
    protected var verts = FloatArray(initialFloats)
    protected var idx = 0

    /** UV of an opaque white texel in the bound atlas – used for all untextured shapes. */
    var whiteU = 0f
    var whiteV = 0f

    /** On-screen pixels per world unit; used to pick curve tessellation levels. */
    var pixelScale = 1f

    /** Make room for [floats] more floats. */
    protected abstract fun ensure(floats: Int)

    private val scratch = FloatArray(512)

    @Suppress("NOTHING_TO_INLINE")
    protected inline fun v(x: Float, y: Float, c: Float) {
        val a = verts; val i = idx
        a[i] = x; a[i + 1] = y; a[i + 2] = c; a[i + 3] = whiteU; a[i + 4] = whiteV
        idx = i + 5
    }

    @Suppress("NOTHING_TO_INLINE")
    protected inline fun vt(x: Float, y: Float, c: Float, u: Float, vv: Float) {
        val a = verts; val i = idx
        a[i] = x; a[i + 1] = y; a[i + 2] = c; a[i + 3] = u; a[i + 4] = vv
        idx = i + 5
    }

    fun tri(x1: Float, y1: Float, c1: Float, x2: Float, y2: Float, c2: Float, x3: Float, y3: Float, c3: Float) {
        ensure(15)
        v(x1, y1, c1); v(x2, y2, c2); v(x3, y3, c3)
    }

    fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, c: Float) =
        tri(x1, y1, c, x2, y2, c, x3, y3, c)

    /** Quad given as four corners in order (convex). */
    fun quad(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float,
             c1: Float, c2: Float = c1, c3: Float = c1, c4: Float = c1) {
        ensure(30)
        v(x1, y1, c1); v(x2, y2, c2); v(x3, y3, c3)
        v(x1, y1, c1); v(x3, y3, c3); v(x4, y4, c4)
    }

    /** Textured quad (used by text). */
    fun texQuad(x: Float, y: Float, w: Float, h: Float, u1: Float, v1: Float, u2: Float, v2: Float, c: Float) {
        ensure(30)
        vt(x, y, c, u1, v2); vt(x + w, y, c, u2, v2); vt(x + w, y + h, c, u2, v1)
        vt(x, y, c, u1, v2); vt(x + w, y + h, c, u2, v1); vt(x, y + h, c, u1, v1)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, c: Float) =
        quad(x, y, x + w, y, x + w, y + h, x, y + h, c)

    /** Rectangle with a vertical gradient (bottom colour → top colour). */
    fun rectV(x: Float, y: Float, w: Float, h: Float, bottom: Float, top: Float) =
        quad(x, y, x + w, y, x + w, y + h, x, y + h, bottom, bottom, top, top)

    /** Rectangle with a horizontal gradient (left colour → right colour). */
    fun rectH(x: Float, y: Float, w: Float, h: Float, left: Float, right: Float) =
        quad(x, y, x + w, y, x + w, y + h, x, y + h, left, right, right, left)

    fun segs(r: Float): Int = MathUtils.clamp((r * pixelScale * 0.6f).toInt(), 6, 40)

    fun circle(cx: Float, cy: Float, r: Float, c: Float, n: Int = segs(r)) = circleGrad(cx, cy, r, c, c, n)

    /** Filled circle with a radial gradient from [inner] (centre) to [outer] (rim). */
    fun circleGrad(cx: Float, cy: Float, r: Float, inner: Float, outer: Float, n: Int = segs(r)) {
        ensure(n * 15)
        var px = cx + r; var py = cy
        for (i in 1..n) {
            val a = i * MathUtils.PI2 / n
            val nx = cx + MathUtils.cos(a) * r
            val ny = cy + MathUtils.sin(a) * r
            v(cx, cy, inner); v(px, py, outer); v(nx, ny, outer)
            px = nx; py = ny
        }
    }

    /** Soft light blob: colour fades to transparent at the rim. */
    fun glow(cx: Float, cy: Float, r: Float, rgb: Int, a: Float) =
        circleGrad(cx, cy, r, Col.pack(rgb, a), Col.pack(rgb, 0f), max(12, segs(r) / 2))

    fun ring(cx: Float, cy: Float, r1: Float, r2: Float, c: Float, n: Int = segs(r2)) =
        arc(cx, cy, r1, r2, 0f, MathUtils.PI2, c, n)

    /** Thick arc between radii r1..r2 from angle a0 spanning [sweep] radians. */
    fun arc(cx: Float, cy: Float, r1: Float, r2: Float, a0: Float, sweep: Float, c: Float, n0: Int = segs(r2)) {
        val n = max(2, (n0 * abs(sweep) / MathUtils.PI2).toInt() + 1)
        ensure(n * 30)
        var ca = MathUtils.cos(a0); var sa = MathUtils.sin(a0)
        for (i in 1..n) {
            val a = a0 + sweep * i / n
            val cb = MathUtils.cos(a); val sb = MathUtils.sin(a)
            val x1 = cx + ca * r1; val y1 = cy + sa * r1
            val x2 = cx + ca * r2; val y2 = cy + sa * r2
            val x3 = cx + cb * r2; val y3 = cy + sb * r2
            val x4 = cx + cb * r1; val y4 = cy + sb * r1
            v(x1, y1, c); v(x2, y2, c); v(x3, y3, c)
            v(x1, y1, c); v(x3, y3, c); v(x4, y4, c)
            ca = cb; sa = sb
        }
    }

    /** Pie slice (used for progress dials). */
    fun pie(cx: Float, cy: Float, r: Float, a0: Float, sweep: Float, c: Float) {
        val n = max(2, (segs(r) * abs(sweep) / MathUtils.PI2).toInt() + 1)
        ensure(n * 15)
        var px = cx + MathUtils.cos(a0) * r; var py = cy + MathUtils.sin(a0) * r
        for (i in 1..n) {
            val a = a0 + sweep * i / n
            val nx = cx + MathUtils.cos(a) * r; val ny = cy + MathUtils.sin(a) * r
            v(cx, cy, c); v(px, py, c); v(nx, ny, c)
            px = nx; py = ny
        }
    }

    /** Thick line segment with square ends. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, c: Float, c2: Float = c) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-5f) return
        val nx = -dy / len * w * 0.5f; val ny = dx / len * w * 0.5f
        quad(x1 + nx, y1 + ny, x1 - nx, y1 - ny, x2 - nx, y2 - ny, x2 + nx, y2 + ny, c, c, c2, c2)
    }

    /** Line with rounded caps. */
    fun capsule(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, c: Float) {
        line(x1, y1, x2, y2, w, c)
        val n = max(6, segs(w) / 2)
        circle(x1, y1, w * 0.5f, c, n); circle(x2, y2, w * 0.5f, c, n)
    }

    /** Rounded rectangle with a vertical gradient (bottom → top). */
    fun roundRect(x: Float, y: Float, w: Float, h: Float, r0: Float, bottom: Float, top: Float = bottom) {
        val r = min(r0, min(w, h) * 0.5f)
        if (r <= 0.0001f) { rectV(x, y, w, h, bottom, top); return }
        val cs = max(2, segs(r) / 4)
        var n = 0
        val p = scratch
        fun corner(cx: Float, cy: Float, a0: Float) {
            for (i in 0..cs) {
                val a = a0 + MathUtils.HALF_PI * i / cs
                p[n++] = cx + MathUtils.cos(a) * r
                p[n++] = cy + MathUtils.sin(a) * r
            }
        }
        corner(x + w - r, y + r, -MathUtils.HALF_PI)
        corner(x + w - r, y + h - r, 0f)
        corner(x + r, y + h - r, MathUtils.HALF_PI)
        corner(x + r, y + r, MathUtils.PI)
        val cx = x + w * 0.5f; val cy = y + h * 0.5f
        val cc = lerpPacked(bottom, top, 0.5f)
        val count = n / 2
        ensure(count * 15)
        for (i in 0 until count) {
            val j = (i + 1) % count
            val ax = p[i * 2]; val ay = p[i * 2 + 1]
            val bx = p[j * 2]; val by = p[j * 2 + 1]
            v(cx, cy, cc)
            v(ax, ay, if (bottom == top) bottom else lerpPacked(bottom, top, (ay - y) / h))
            v(bx, by, if (bottom == top) bottom else lerpPacked(bottom, top, (by - y) / h))
        }
    }

    /** Rounded-rectangle outline of thickness [t]. */
    fun roundRectOutline(x: Float, y: Float, w: Float, h: Float, r0: Float, t: Float, c: Float) {
        val r = min(r0, min(w, h) * 0.5f)
        rect(x + r, y, w - 2 * r, t, c)
        rect(x + r, y + h - t, w - 2 * r, t, c)
        rect(x, y + r, t, h - 2 * r, c)
        rect(x + w - t, y + r, t, h - 2 * r, c)
        if (r > 0f) {
            val n = max(8, segs(r))
            arc(x + w - r, y + r, r - t, r, -MathUtils.HALF_PI, MathUtils.HALF_PI, c, n)
            arc(x + w - r, y + h - r, r - t, r, 0f, MathUtils.HALF_PI, c, n)
            arc(x + r, y + h - r, r - t, r, MathUtils.HALF_PI, MathUtils.HALF_PI, c, n)
            arc(x + r, y + r, r - t, r, MathUtils.PI, MathUtils.HALF_PI, c, n)
        }
    }

    /** Regular polygon (hexagons, diamonds, …) rotated by [rot]. */
    fun ngon(cx: Float, cy: Float, r: Float, sides: Int, rot: Float, c: Float, rim: Float = c) {
        ensure(sides * 15)
        for (i in 0 until sides) {
            val a1 = rot + i * MathUtils.PI2 / sides
            val a2 = rot + (i + 1) * MathUtils.PI2 / sides
            v(cx, cy, c)
            v(cx + MathUtils.cos(a1) * r, cy + MathUtils.sin(a1) * r, rim)
            v(cx + MathUtils.cos(a2) * r, cy + MathUtils.sin(a2) * r, rim)
        }
    }

    /** Gear: [teeth] trapezoid teeth around a disc, with a hub hole drawn by the caller. */
    fun gear(cx: Float, cy: Float, r: Float, teeth: Int, rot: Float, c: Float) {
        val inner = r * 0.78f
        circle(cx, cy, inner, c)
        val step = MathUtils.PI2 / teeth
        ensure(teeth * 30)
        for (i in 0 until teeth) {
            val a = rot + i * step
            val a1 = a - step * 0.22f; val a2 = a + step * 0.22f
            val b1 = a - step * 0.14f; val b2 = a + step * 0.14f
            val x1 = cx + MathUtils.cos(a1) * inner * 0.98f; val y1 = cy + MathUtils.sin(a1) * inner * 0.98f
            val x2 = cx + MathUtils.cos(b1) * r; val y2 = cy + MathUtils.sin(b1) * r
            val x3 = cx + MathUtils.cos(b2) * r; val y3 = cy + MathUtils.sin(b2) * r
            val x4 = cx + MathUtils.cos(a2) * inner * 0.98f; val y4 = cy + MathUtils.sin(a2) * inner * 0.98f
            v(x1, y1, c); v(x2, y2, c); v(x3, y3, c)
            v(x1, y1, c); v(x3, y3, c); v(x4, y4, c)
        }
    }

    /** Convex polygon from interleaved xy points, fanned from the first vertex. */
    fun poly(pts: FloatArray, count: Int, c: Float) {
        if (count < 3) return
        ensure((count - 2) * 15)
        for (i in 1 until count - 1) {
            v(pts[0], pts[1], c)
            v(pts[i * 2], pts[i * 2 + 1], c)
            v(pts[i * 2 + 2], pts[i * 2 + 3], c)
        }
    }

    /** Arrow head pointing at angle [ang]. */
    fun arrowHead(cx: Float, cy: Float, size: Float, ang: Float, c: Float) {
        val ca = MathUtils.cos(ang); val sa = MathUtils.sin(ang)
        val tipX = cx + ca * size; val tipY = cy + sa * size
        val bx = cx - ca * size * 0.6f; val by = cy - sa * size * 0.6f
        tri(tipX, tipY, bx - sa * size * 0.8f, by + ca * size * 0.8f, bx + sa * size * 0.8f, by - ca * size * 0.8f, c)
    }

    /** Chevron (">" shape) pointing at [ang] – used for belt flow marks. */
    fun chevron(cx: Float, cy: Float, size: Float, thick: Float, ang: Float, c: Float) {
        val ca = MathUtils.cos(ang); val sa = MathUtils.sin(ang)
        val tipX = cx + ca * size * 0.5f; val tipY = cy + sa * size * 0.5f
        val lx = cx - ca * size * 0.5f - sa * size * 0.6f; val ly = cy - sa * size * 0.5f + ca * size * 0.6f
        val rx = cx - ca * size * 0.5f + sa * size * 0.6f; val ry = cy - sa * size * 0.5f - ca * size * 0.6f
        line(lx, ly, tipX, tipY, thick, c)
        line(rx, ry, tipX, tipY, thick, c)
    }

    companion object {
        /** Interpolate between two packed colours. */
        fun lerpPacked(a: Float, b: Float, t: Float): Float {
            val ia = NumberUtils.floatToIntColor(a); val ib = NumberUtils.floatToIntColor(b)
            val tt = t.coerceIn(0f, 1f)
            fun ch(s: Int) = (((ia ushr s) and 0xFF) + ((((ib ushr s) and 0xFF) - ((ia ushr s) and 0xFF)) * tt)).toInt()
            return NumberUtils.intToFloatColor((ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
        }
    }
}

/** Grows without limit – used to bake static geometry (terrain chunks) into meshes. */
class MeshBuilder(initial: Int = 1 shl 15) : Shapes(initial) {
    override fun ensure(floats: Int) {
        if (idx + floats > verts.size) verts = verts.copyOf(max(verts.size * 2, idx + floats))
    }
    fun reset() { idx = 0 }
    val floatCount get() = idx
    val data get() = verts
}
