package io.github.rantark.factoryflow.render

import com.badlogic.gdx.math.MathUtils
import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Fluid
import io.github.rantark.factoryflow.data.Item
import io.github.rantark.factoryflow.data.ItemShape
import io.github.rantark.factoryflow.gfx.Col
import io.github.rantark.factoryflow.gfx.Shapes
import io.github.rantark.factoryflow.sim.Accumulator
import io.github.rantark.factoryflow.sim.Belt
import io.github.rantark.factoryflow.sim.Building
import io.github.rantark.factoryflow.sim.Chest
import io.github.rantark.factoryflow.sim.CoalGenerator
import io.github.rantark.factoryflow.sim.Crafter
import io.github.rantark.factoryflow.sim.Dir
import io.github.rantark.factoryflow.sim.Factory
import io.github.rantark.factoryflow.sim.FluidNode
import io.github.rantark.factoryflow.sim.Inserter
import io.github.rantark.factoryflow.sim.Lab
import io.github.rantark.factoryflow.sim.Pump
import io.github.rantark.factoryflow.sim.UndergroundBelt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All procedural art. Everything is drawn in a building's local "tile" space
 * (0..size) and mapped to the screen through [ox], [oy] and [u] (units per tile), so
 * exactly the same code renders buildings in the world and icons in the UI.
 */
class Painter {
    lateinit var s: Shapes
    var ox = 0f
    var oy = 0f
    var u = 1f
    /** Seconds of real time, for idle animations (pulses, shimmer). */
    var time = 0f
    /** Set when drawing into the world so pipes/belts can look at their neighbours. */
    var factory: Factory? = null
    /** Draw simplified shapes when zoomed far out. */
    var lowDetail = false

    private fun X(l: Float) = ox + l * u
    private fun Y(l: Float) = oy + l * u
    private fun L(l: Float) = l * u

    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Float, c2: Float = c) =
        s.roundRect(X(x), Y(y), L(w), L(h), L(r), c, c2)
    private fun circ(x: Float, y: Float, r: Float, c: Float) = s.circle(X(x), Y(y), L(r), c)
    private fun circG(x: Float, y: Float, r: Float, a: Float, b: Float) = s.circleGrad(X(x), Y(y), L(r), a, b)
    private fun ln(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, c: Float) = s.line(X(x1), Y(y1), X(x2), Y(y2), L(w), c)
    private fun cap(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, c: Float) = s.capsule(X(x1), Y(y1), X(x2), Y(y2), L(w), c)

    /** Direction helpers: a point [fwd] along dir and [side] to its left, from (cx, cy). */
    private fun dx(d: Int, fwd: Float, side: Float) = Dir.DX[d] * fwd - Dir.DY[d] * side
    private fun dy(d: Int, fwd: Float, side: Float) = Dir.DY[d] * fwd + Dir.DX[d] * side

    // =================================================================================
    // Shared pieces
    // =================================================================================

    private fun shadow(size: Float, inset: Float = 0.05f) {
        rr(inset + 0.07f, inset - 0.1f, size - inset * 2, size - inset * 2, 0.22f, SHADOW)
    }

    /** Standard machine body: dark base plate with a coloured, gradient-lit roof. */
    private fun body(size: Float, color: Int, inset: Float = 0.06f, radius: Float = 0.22f) {
        shadow(size, inset)
        rr(inset, inset, size - inset * 2, size - inset * 2, radius, Col.pack(0x2A2E36), Col.pack(0x3B414C))
        val i2 = inset + 0.1f
        rr(i2, i2, size - i2 * 2, size - i2 * 2, radius * 0.8f, Col.pack(Col.darken(color, 0.35f)), Col.pack(Col.lighten(color, 0.08f)))
        // Top edge highlight for a soft bevel.
        rr(i2 + 0.08f, size - i2 - 0.12f, size - i2 * 2 - 0.16f, 0.06f, 0.03f, Col.pack(0xFFFFFF, 0.18f))
    }

    /** Output arrow on the side the building faces. */
    private fun portArrow(size: Float, d: Int, color: Int = 0xFFFFFF, alpha: Float = 0.85f) {
        val c = size / 2f
        val px = c + Dir.DX[d] * (c - 0.2f); val py = c + Dir.DY[d] * (c - 0.2f)
        s.arrowHead(X(px), Y(py), L(0.16f), d * MathUtils.HALF_PI, Col.pack(color, alpha))
    }

    private fun bolt(cx: Float, cy: Float, r: Float, c: Float) {
        val p = floatArrayOf(
            X(cx + 0.1f * r), Y(cy + 1f * r), X(cx - 0.55f * r), Y(cy - 0.1f * r), X(cx - 0.05f * r), Y(cy - 0.1f * r),
        )
        s.tri(p[0], p[1], p[2], p[3], p[4], p[5], c)
        s.tri(X(cx - 0.1f * r), Y(cy - 1f * r), X(cx + 0.55f * r), Y(cy + 0.1f * r), X(cx + 0.05f * r), Y(cy + 0.1f * r), c)
        s.quad(X(cx - 0.05f * r), Y(cy - 0.1f * r), X(cx + 0.55f * r), Y(cy + 0.1f * r), X(cx + 0.05f * r), Y(cy + 0.1f * r), X(cx - 0.55f * r), Y(cy - 0.1f * r), c)
    }

    /** Lightning bolt icon, exposed for status indicators and the UI. */
    fun powerIcon(cx: Float, cy: Float, r: Float, rgb: Int, a: Float = 1f) {
        val so = ox; val sy = oy; val su = u
        ox = 0f; oy = 0f; u = 1f
        bolt(cx, cy, r, Col.pack(rgb, a))
        ox = so; oy = sy; u = su
    }

    // =================================================================================
    // Buildings
    // =================================================================================

    /**
     * Draw a building of [type] with its bottom-left at world/screen ([x], [y]) and
     * [unit] units per tile. [b] supplies live state; null draws an idle icon.
     */
    fun building(type: BuildingType, x: Float, y: Float, unit: Float, dir: Int, b: Building?) {
        ox = x; oy = y; u = unit
        val sz = type.size.toFloat()
        val anim = b?.anim ?: 0f
        val working = b?.working ?: false
        when (type) {
            BuildingType.BELT -> belt(dir, b as? Belt)
            BuildingType.UNDERGROUND -> underground(dir, b as? UndergroundBelt)
            BuildingType.SPLITTER, BuildingType.MERGER -> router(type, dir, anim)
            BuildingType.PIPE_SEG -> pipe(b)
            BuildingType.INSERTER, BuildingType.LONG_INSERTER, BuildingType.FAST_INSERTER,
            BuildingType.FILTER_INSERTER -> inserter(type, dir, b as? Inserter)
            BuildingType.MINING_DRILL -> drill(dir, anim, working)
            BuildingType.PUMP_JACK -> pumpJack(anim)
            BuildingType.WATER_PUMP -> waterPump(anim, working)
            BuildingType.POLE -> pole(false, working)
            BuildingType.BIG_POLE -> pole(true, working)
            BuildingType.COAL_GEN -> coalGen(b as? CoalGenerator, anim, working)
            BuildingType.SOLAR -> solar()
            BuildingType.ACCUMULATOR -> accumulator(b as? Accumulator)
            BuildingType.STONE_FURNACE -> stoneFurnace(dir, working)
            BuildingType.ELECTRIC_FURNACE -> electricFurnace(dir, working)
            BuildingType.ASSEMBLER_1, BuildingType.ASSEMBLER_2, BuildingType.ASSEMBLER_3 -> assembler(type, dir, b as? Crafter, anim)
            BuildingType.ELECTRONICS -> electronics(dir, b as? Crafter, anim, working)
            BuildingType.CHEM_PLANT -> chemPlant(dir, b as? Crafter, anim, working)
            BuildingType.REFINERY -> refinery(dir, anim, working)
            BuildingType.CHEST_1, BuildingType.CHEST_2, BuildingType.CHEST_3 -> chest(type, b as? Chest)
            BuildingType.TANK -> tank(b as? FluidNode)
            BuildingType.LAB -> lab(b as? Lab, anim, working)
            BuildingType.CORE -> core(anim)
        }
        if (b != null && sz >= 1f) statusBadge(b, sz)
    }

    /** Icon for the build menu: the building scaled to fit a [size] box centred at (cx, cy). */
    fun buildingIcon(type: BuildingType, cx: Float, cy: Float, size: Float, dir: Int = 0) {
        val unit = size / max(type.size, 1)
        val pf = factory; factory = null
        building(type, cx - size / 2, cy - size / 2, unit, dir, null)
        factory = pf
    }

    private fun statusBadge(b: Building, sz: Float) {
        if (b.type.power <= 0f || b is Belt) return
        if (b.status == io.github.rantark.factoryflow.sim.Status.NO_POWER) {
            val blink = 0.55f + 0.45f * sin(time * 5f)
            circ(sz - 0.28f, sz - 0.28f, 0.22f, Col.pack(0x1A1A1A, 0.8f))
            bolt(sz - 0.28f, sz - 0.28f, 0.17f, Col.pack(0xFF5A5F, blink))
        } else if (b.status == io.github.rantark.factoryflow.sim.Status.LOW_POWER) {
            circ(sz - 0.28f, sz - 0.28f, 0.2f, Col.pack(0x1A1A1A, 0.7f))
            bolt(sz - 0.28f, sz - 0.28f, 0.15f, Col.pack(0xFFC145, 0.9f))
        }
    }

    // ---- Belts ----------------------------------------------------------------------

    private fun belt(dir: Int, b: Belt?) {
        val curve = b?.curve ?: 0
        val phase = ((b?.anim ?: time * 1.8f) * 2f) % 1f
        val track = Col.pack(0x24262B)
        val rail = Col.pack(BELT_RAIL)
        val mark = Col.pack(0xFFE08A, 0.55f)
        if (curve == 0) {
            // Straight: track along dir, rails at both sides, chevrons flowing forward.
            val a = dir * MathUtils.HALF_PI
            val (x0, y0, w, h) = if (dir and 1 == 0) arrayOf(0f, 0.12f, 1f, 0.76f) else arrayOf(0.12f, 0f, 0.76f, 1f)
            s.rect(X(x0), Y(y0), L(w), L(h), track)
            if (dir and 1 == 0) {
                s.rect(X(0f), Y(0.08f), L(1f), L(0.1f), rail); s.rect(X(0f), Y(0.82f), L(1f), L(0.1f), rail)
            } else {
                s.rect(X(0.08f), Y(0f), L(0.1f), L(1f), rail); s.rect(X(0.82f), Y(0f), L(0.1f), L(1f), rail)
            }
            if (!lowDetail) for (k in 0 until 2) {
                val t = (k + phase) / 2f
                val fx = 0.5f + Dir.DX[dir] * (t - 0.5f); val fy = 0.5f + Dir.DY[dir] * (t - 0.5f)
                s.chevron(X(fx), Y(fy), L(0.3f), L(0.07f), a, mark)
            }
        } else {
            // Curved: quarter ring around the corner shared by the input side and front.
            val side = if (curve == 1) Dir.left(dir) else Dir.right(dir)
            val ccx = 0.5f + (Dir.DX[dir] + Dir.DX[side]) * 0.5f
            val ccy = 0.5f + (Dir.DY[dir] + Dir.DY[side]) * 0.5f
            val a0 = MathUtils.atan2(-(Dir.DY[dir] + Dir.DY[side]).toFloat(), -(Dir.DX[dir] + Dir.DX[side]).toFloat()) - MathUtils.PI / 4
            s.arc(X(ccx), Y(ccy), L(0.12f), L(0.88f), a0, MathUtils.HALF_PI, track)
            s.arc(X(ccx), Y(ccy), L(0.08f), L(0.18f), a0, MathUtils.HALF_PI, rail)
            s.arc(X(ccx), Y(ccy), L(0.82f), L(0.92f), a0, MathUtils.HALF_PI, rail)
            if (!lowDetail) for (k in 0 until 2) {
                val t = (k + phase) / 2f
                val p = curvePoint(dir, curve, t)
                val ang = curveAngle(dir, curve, t)
                s.chevron(X(p.first), Y(p.second), L(0.28f), L(0.07f), ang, mark)
            }
        }
    }

    /** Position along a curved belt (tile-local) for progress [t]. */
    fun curvePoint(dir: Int, curve: Int, t: Float): Pair<Float, Float> {
        // In local "east-facing" space: from left (curve=1) centre=(1,1) angle 180→270°,
        // from right (curve=-1) centre=(1,0) angle 180→90°.
        val a = if (curve == 1) MathUtils.PI + t * MathUtils.HALF_PI else MathUtils.PI - t * MathUtils.HALF_PI
        val lx = (1f + 0.5f * MathUtils.cos(a)) - 0.5f
        val ly = (if (curve == 1) 1f else 0f) + 0.5f * MathUtils.sin(a) - 0.5f
        return rot(dir, lx, ly)
    }

    private fun curveAngle(dir: Int, curve: Int, t: Float): Float {
        val a = if (curve == 1) MathUtils.PI + t * MathUtils.HALF_PI else MathUtils.PI - t * MathUtils.HALF_PI
        val tangent = if (curve == 1) a + MathUtils.HALF_PI else a - MathUtils.HALF_PI
        return tangent + dir * MathUtils.HALF_PI
    }

    /** Rotate a point given relative to the tile centre (east-facing) into [dir]. */
    private fun rot(dir: Int, lx: Float, ly: Float): Pair<Float, Float> = when (dir) {
        0 -> Pair(0.5f + lx, 0.5f + ly)
        1 -> Pair(0.5f - ly, 0.5f + lx)
        2 -> Pair(0.5f - lx, 0.5f - ly)
        else -> Pair(0.5f + ly, 0.5f - lx)
    }

    /** Draw the items riding on [b] (world space). */
    fun beltItems(b: Belt) {
        ox = b.x.toFloat(); oy = b.y.toFloat(); u = 1f
        val under = b is UndergroundBelt
        for (k in 0 until b.count) {
            val p = b.prog[k]
            var px: Float; var py: Float
            if (under) {
                // Entrance: show items only until they dip into the tunnel.
                val world = p * b.length
                if (!b.isExit && world > 0.5f) continue
                val lp = if (b.isExit) p else world
                px = 0.5f + Dir.DX[b.dir] * (lp - 0.5f); py = 0.5f + Dir.DY[b.dir] * (lp - 0.5f)
                if (b.isExit && lp < 0.45f) continue
            } else if (b.curve == 0) {
                px = 0.5f + Dir.DX[b.dir] * (p - 0.5f); py = 0.5f + Dir.DY[b.dir] * (p - 0.5f)
            } else {
                val q = curvePoint(b.dir, b.curve, p); px = q.first; py = q.second
            }
            item(Item.ALL[b.items[k]], ox + px, oy + py, 0.19f)
        }
    }

    private fun underground(dir: Int, b: UndergroundBelt?) {
        val exit = b?.isExit ?: false
        belt(dir, b)
        // Tunnel hood: a dark arch on the far side (entrance) or near side (exit).
        val c = 0.5f
        val f = if (exit) -0.18f else 0.18f
        val hx = c + Dir.DX[dir] * f; val hy = c + Dir.DY[dir] * f
        val a0 = (dir * MathUtils.HALF_PI) + (if (exit) MathUtils.HALF_PI else -MathUtils.HALF_PI)
        s.pie(X(hx), Y(hy), L(0.48f), a0, MathUtils.PI, Col.pack(0x15161A))
        s.arc(X(hx), Y(hy), L(0.42f), L(0.5f), a0, MathUtils.PI, Col.pack(BELT_RAIL))
        if (b != null && b.partner == null) circ(0.5f, 0.5f, 0.1f, Col.pack(0xFF5A5F, 0.8f + 0.2f * sin(time * 4)))
    }

    private fun router(type: BuildingType, dir: Int, anim: Float) {
        shadow(1f, 0.06f)
        rr(0.06f, 0.06f, 0.88f, 0.88f, 0.16f, Col.pack(0x2A2E36), Col.pack(0x3C434E))
        rr(0.14f, 0.14f, 0.72f, 0.72f, 0.12f, Col.pack(0xB88A1A), Col.pack(BELT_RAIL))
        val c = Col.pack(0x24262B)
        if (type == BuildingType.SPLITTER) {
            for (d in intArrayOf(Dir.left(dir), dir, Dir.right(dir))) {
                s.arrowHead(X(0.5f + Dir.DX[d] * 0.24f), Y(0.5f + Dir.DY[d] * 0.24f), L(0.12f), d * MathUtils.HALF_PI, c)
            }
            circ(0.5f, 0.5f, 0.09f, c)
        } else {
            for (d in intArrayOf(Dir.left(dir), Dir.opposite(dir), Dir.right(dir))) {
                val od = Dir.opposite(d)
                s.arrowHead(X(0.5f + Dir.DX[d] * 0.26f), Y(0.5f + Dir.DY[d] * 0.26f), L(0.1f), od * MathUtils.HALF_PI, c)
            }
            s.arrowHead(X(0.5f + Dir.DX[dir] * 0.22f), Y(0.5f + Dir.DY[dir] * 0.22f), L(0.14f), dir * MathUtils.HALF_PI, c)
        }
    }

    // ---- Pipes ----------------------------------------------------------------------

    private fun pipe(b: Building?) {
        val f = factory
        val metal = Col.pack(0x8C96A3); val dark = Col.pack(0x4F5761)
        var fluidColor = -1
        if (b != null && f != null) {
            val net = f.fluids.netAt(b.x, b.y)
            if (net >= 0 && net < f.fluids.nets.size) {
                val n = f.fluids.nets[net]
                if (n.fluid >= 0 && n.amount > 0.5f) fluidColor = Fluid.ALL[n.fluid].color
            }
        }
        var any = false
        for (d in 0..3) {
            val connected = if (b == null || f == null) (d == 0 || d == 2) else {
                val nb = f.at(b.x + Dir.DX[d], b.y + Dir.DY[d])
                nb is FluidNode || nb is Pump || (nb is Crafter && nb.mclass.let {
                    it == io.github.rantark.factoryflow.data.MachineClass.CHEMICAL || it == io.github.rantark.factoryflow.data.MachineClass.REFINERY
                })
            }
            if (!connected) continue
            any = true
            val ex = 0.5f + Dir.DX[d] * 0.5f; val ey = 0.5f + Dir.DY[d] * 0.5f
            ln(0.5f, 0.5f, ex, ey, 0.34f, dark)
            ln(0.5f, 0.5f, ex, ey, 0.24f, metal)
            if (fluidColor >= 0) ln(0.5f, 0.5f, ex, ey, 0.1f, Col.pack(fluidColor, 0.9f))
            // Flange ring near the joint.
            val fx = 0.5f + Dir.DX[d] * 0.42f; val fy = 0.5f + Dir.DY[d] * 0.42f
            if (d and 1 == 0) s.rect(X(fx - 0.04f), Y(0.3f), L(0.08f), L(0.4f), dark) else s.rect(X(0.3f), Y(fy - 0.04f), L(0.4f), L(0.08f), dark)
        }
        circ(0.5f, 0.5f, if (any) 0.2f else 0.26f, dark)
        circ(0.5f, 0.5f, if (any) 0.15f else 0.2f, metal)
        if (fluidColor >= 0) circ(0.5f, 0.5f, 0.08f, Col.pack(fluidColor))
    }

    // ---- Inserters ------------------------------------------------------------------

    private fun inserter(type: BuildingType, dir: Int, b: Inserter?) {
        val color = when (type) {
            BuildingType.LONG_INSERTER -> 0xE76F51
            BuildingType.FAST_INSERTER -> 0x4EA8DE
            BuildingType.FILTER_INSERTER -> 0xB388FF
            else -> 0xF2C94C
        }
        val reach = if (type == BuildingType.LONG_INSERTER) 2f else 1f
        circ(0.55f, 0.42f, 0.34f, SHADOW)
        rr(0.18f, 0.18f, 0.64f, 0.64f, 0.14f, Col.pack(0x2A2E36), Col.pack(0x434A56))
        circ(0.5f, 0.5f, 0.24f, Col.pack(Col.darken(color, 0.3f)))
        circ(0.5f, 0.5f, 0.17f, Col.pack(color))
        // Arm swings from the pickup side (behind) over the left to the drop side.
        val t = b?.arm ?: 0.5f
        val start = Dir.opposite(dir) * MathUtils.HALF_PI
        val ang = start - t * MathUtils.PI
        val len = 0.52f * reach
        val ex = 0.5f + MathUtils.cos(ang) * len; val ey = 0.5f + MathUtils.sin(ang) * len
        val ew = 0.5f + MathUtils.cos(ang + 0.5f) * len * 0.5f; val eh = 0.5f + MathUtils.sin(ang + 0.5f) * len * 0.5f
        cap(0.5f, 0.5f, ew, eh, 0.12f, Col.pack(Col.darken(color, 0.15f)))
        cap(ew, eh, ex, ey, 0.09f, Col.pack(Col.lighten(color, 0.2f)))
        circ(ex, ey, 0.08f, Col.pack(0x30343C))
        val held = b?.held ?: -1
        if (held >= 0) item(Item.ALL[held], X(ex), Y(ey), L(0.17f))
        if (type == BuildingType.FILTER_INSERTER && b != null && b.filter >= 0) {
            circ(0.5f, 0.5f, 0.12f, Col.pack(0x1A1A1A))
            item(Item.ALL[b.filter], X(0.5f), Y(0.5f), L(0.1f))
        }
        s.arrowHead(X(0.5f + Dir.DX[dir] * 0.36f), Y(0.5f + Dir.DY[dir] * 0.36f), L(0.07f), dir * MathUtils.HALF_PI, Col.pack(0xFFFFFF, 0.6f))
    }

    // ---- Extraction -----------------------------------------------------------------

    private fun drill(dir: Int, anim: Float, working: Boolean) {
        body(2f, 0xF2A541)
        // Hazard stripes along the back edge.
        if (!lowDetail) {
            val back = Dir.opposite(dir)
            for (k in 0 until 4) {
                val side = -0.6f + k * 0.4f
                val bx = 1f + dx(back, 0.62f, side); val by = 1f + dy(back, 0.62f, side)
                circ(bx, by, 0.07f, Col.pack(0x1F1F24, 0.8f))
            }
        }
        circ(1f, 1f, 0.62f, Col.pack(0x2B2F37))
        circG(1f, 1f, 0.55f, Col.pack(0x5A616D), Col.pack(0x3A3F48))
        s.gear(X(1f), Y(1f), L(0.46f), 8, anim * 4f, Col.pack(0xC9D1DA))
        circ(1f, 1f, 0.2f, Col.pack(0x2B2F37))
        circ(1f, 1f, 0.1f, Col.pack(if (working) 0xFFD166 else 0x777777))
        portArrow(2f, dir)
    }

    private fun pumpJack(anim: Float) {
        body(2f, 0xF2A541)
        circ(1f, 1f, 0.5f, Col.pack(0x241C2B))
        // Rocking walking beam.
        val a = sin(anim * 2.4f) * 0.35f
        val px = 1f; val py = 1.05f
        val ax = MathUtils.cos(a) * 0.75f; val ay = MathUtils.sin(a) * 0.75f
        cap(px - ax, py - ay, px + ax, py + ay, 0.16f, Col.pack(0x3A3F48))
        cap(px - ax, py - ay, px + ax, py + ay, 0.1f, Col.pack(0xF2A541))
        rr(px + ax - 0.12f, py + ay - 0.2f, 0.24f, 0.4f, 0.08f, Col.pack(0xE36414))
        circ(px - ax, py - ay, 0.17f, Col.pack(0x555C68))
        circ(px, py, 0.1f, Col.pack(0x22262D))
    }

    private fun waterPump(anim: Float, working: Boolean) {
        shadow(1f, 0.08f)
        circ(0.5f, 0.5f, 0.44f, Col.pack(0x2A2E36))
        circG(0.5f, 0.5f, 0.36f, Col.pack(0x7CC6FE), Col.pack(0x2F6690))
        for (k in 0 until 4) {
            val a = anim * 6f + k * MathUtils.HALF_PI
            ln(0.5f, 0.5f, 0.5f + MathUtils.cos(a) * 0.28f, 0.5f + MathUtils.sin(a) * 0.28f, 0.08f, Col.pack(0xE8F4FF, if (working) 0.9f else 0.5f))
        }
        circ(0.5f, 0.5f, 0.08f, Col.pack(0x1B3A55))
    }

    // ---- Power ----------------------------------------------------------------------

    private fun pole(big: Boolean, powered: Boolean) {
        // Powered poles pulse softly, like a heartbeat travelling down the line.
        val pulse = if (powered) 0.55f + 0.45f * sin(time * 3f + (ox + oy) * 0.35f) else 0f
        if (big) {
            circ(0.56f, 0.4f, 0.34f, SHADOW)
            s.ngon(X(0.5f), Y(0.5f), L(0.36f), 4, MathUtils.PI / 4, Col.pack(0x5C6672))
            s.ngon(X(0.5f), Y(0.5f), L(0.26f), 4, MathUtils.PI / 4, Col.pack(0x8894A2))
            ln(0.2f, 0.2f, 0.8f, 0.8f, 0.06f, Col.pack(0x4A525C)); ln(0.2f, 0.8f, 0.8f, 0.2f, 0.06f, Col.pack(0x4A525C))
            circ(0.5f, 0.5f, 0.1f, Col.pack(0x4FC3F7))
            if (powered) s.glow(X(0.5f), Y(0.5f), L(0.35f), 0x9FE3FF, 0.5f * pulse)
        } else {
            circ(0.56f, 0.42f, 0.22f, SHADOW)
            circ(0.5f, 0.5f, 0.18f, Col.pack(0x5B4636))
            circ(0.5f, 0.5f, 0.13f, Col.pack(0x8B6B4E))
            ln(0.22f, 0.5f, 0.78f, 0.5f, 0.09f, Col.pack(0x4A3A2C))
            circ(0.25f, 0.5f, 0.06f, Col.pack(0x9FD8F5)); circ(0.75f, 0.5f, 0.06f, Col.pack(0x9FD8F5))
            if (powered) {
                s.glow(X(0.25f), Y(0.5f), L(0.16f), 0xC8F0FF, 0.7f * pulse)
                s.glow(X(0.75f), Y(0.5f), L(0.16f), 0xC8F0FF, 0.7f * pulse)
            }
        }
    }

    private fun coalGen(b: CoalGenerator?, anim: Float, working: Boolean) {
        body(2f, 0x4FC3F7)
        // Firebox window.
        val flick = if (working) 0.65f + 0.35f * sin(time * 17f + anim * 3f) * sin(time * 7.3f) else 0f
        rr(0.35f, 0.3f, 0.9f, 0.55f, 0.1f, Col.pack(0x1C1C20))
        if (working) {
            rr(0.42f, 0.36f, 0.76f, 0.43f, 0.08f, Col.pack(0xFF7B00, 0.9f), Col.pack(0xFFD166, 0.6f + 0.4f * flick))
        }
        // Chimney.
        circ(1.5f, 1.45f, 0.28f, Col.pack(0x2B2F37))
        circ(1.5f, 1.45f, 0.18f, Col.pack(0x111114))
        // Turbine housing.
        circG(0.7f, 1.35f, 0.3f, Col.pack(0xB9E6FF), Col.pack(0x4A90B8))
        s.gear(X(0.7f), Y(1.35f), L(0.2f), 6, anim * 5f, Col.pack(0x2A4D63))
        // Smoke puffs drifting upward.
        if (working && !lowDetail) for (k in 0 until 3) {
            val t = ((time * 0.45f) + k / 3f) % 1f
            circ(1.5f + t * 0.35f, 1.55f + t * 0.9f, 0.12f + t * 0.22f, Col.pack(0x9A9A9A, 0.35f * (1f - t)))
        }
        val coal = b?.coal ?: 0
        for (k in 0 until min(coal, 5)) circ(0.45f + k * 0.2f, 0.18f + 0.05f, 0.06f, Col.pack(0x202024))
    }

    private fun solar() {
        shadow(2f, 0.06f)
        rr(0.06f, 0.06f, 1.88f, 1.88f, 0.12f, Col.pack(0x9AA5B1), Col.pack(0xC8D1DA))
        for (i in 0 until 3) for (j in 0 until 3) {
            val x = 0.16f + i * 0.57f; val y = 0.16f + j * 0.57f
            rr(x, y, 0.52f, 0.52f, 0.05f, Col.pack(0x14305A), Col.pack(0x2E5FA3))
        }
        // Specular sheen that slides slowly across the panel.
        val sweep = (time * 0.12f) % 1f
        val sx = 0.1f + sweep * 1.8f
        s.quad(X(sx), Y(0.1f), X(sx + 0.18f), Y(0.1f), X(sx + 0.05f), Y(1.9f), X(sx - 0.13f), Y(1.9f), Col.pack(0xFFFFFF, 0.1f))
    }

    private fun accumulator(b: Accumulator?) {
        body(2f, 0x4FC3F7)
        val charge = (b?.stored ?: 2500f) / Accumulator.CAPACITY_KJ
        for (i in 0 until 2) for (j in 0 until 2) {
            val cx = 0.6f + i * 0.8f; val cy = 0.6f + j * 0.8f
            circ(cx, cy, 0.3f, Col.pack(0x1E2229))
            circG(cx, cy, 0.24f, Col.pack(0x4C5563), Col.pack(0x2E343D))
            val pulse = if ((b?.flow ?: 0f) > 1f) 0.7f + 0.3f * sin(time * 4f) else 1f
            s.arc(X(cx), Y(cy), L(0.18f), L(0.26f), MathUtils.HALF_PI, -MathUtils.PI2 * charge, Col.pack(0x7BF1A8, pulse))
        }
    }

    // ---- Processing -----------------------------------------------------------------

    private fun stoneFurnace(dir: Int, working: Boolean) {
        shadow(2f, 0.08f)
        rr(0.08f, 0.08f, 1.84f, 1.84f, 0.45f, Col.pack(0x6E5E4E), Col.pack(0x9C8A74))
        if (!lowDetail) {
            val mortar = Col.pack(0x5A4C3F, 0.7f)
            for (row in 1..3) s.rect(X(0.2f), Y(row * 0.45f + 0.05f), L(1.6f), L(0.04f), mortar)
        }
        circ(1f, 1f, 0.52f, Col.pack(0x3B2F26))
        val mx = 1f + Dir.DX[dir] * 0.25f; val my = 1f + Dir.DY[dir] * 0.25f
        if (working) {
            val fl = 0.7f + 0.3f * sin(time * 13f) * sin(time * 5.1f)
            circG(mx, my, 0.42f, Col.pack(0xFFE08A, fl), Col.pack(0xFF6B00, 0.6f))
        } else circ(mx, my, 0.3f, Col.pack(0x1E1813))
        portArrow(2f, dir, 0xFFE0B0)
    }

    private fun electricFurnace(dir: Int, working: Boolean) {
        body(2f, 0xEF6F6C)
        circ(1f, 1f, 0.6f, Col.pack(0x2B2F37))
        val heat = if (working) 0.6f + 0.4f * sin(time * 3f) else 0.15f
        for (k in 0 until 3) s.ring(X(1f), Y(1f), L(0.18f + k * 0.14f), L(0.24f + k * 0.14f), Col.pack(0xFF8C42, heat * (1f - k * 0.2f)))
        portArrow(2f, dir)
    }

    private fun tierDots(n: Int, sz: Float) {
        for (k in 0 until n) circ(0.42f + k * 0.22f, sz - 0.42f, 0.07f, Col.pack(0xFFE08A))
    }

    private fun recipeBadge(c: Crafter?, sz: Float) {
        val r = c?.recipe ?: return
        val cx = sz / 2f; val cy = 0.62f
        circ(cx, cy, 0.3f, Col.pack(0x1B1D22, 0.85f))
        val it = r.mainItem
        if (it != null) item(it, X(cx), Y(cy), L(0.22f))
        else r.mainFluid?.let { fluidDrop(it, X(cx), Y(cy), L(0.22f)) }
    }

    private fun assembler(type: BuildingType, dir: Int, c: Crafter?, anim: Float) {
        body(3f, when (type) { BuildingType.ASSEMBLER_2 -> 0x5E9FD8; BuildingType.ASSEMBLER_3 -> 0x9B7EDE; else -> 0xEF6F6C })
        circ(1.5f, 1.65f, 0.78f, Col.pack(0x2B2F37))
        s.gear(X(1.5f), Y(1.65f), L(0.62f), 10, anim * 2f, Col.pack(0xC9D1DA))
        circ(1.5f, 1.65f, 0.22f, Col.pack(0x2B2F37))
        if (!lowDetail) {
            s.gear(X(2.3f), Y(2.3f), L(0.3f), 7, -anim * 2f * 2f + 0.3f, Col.pack(0x9AA5B1))
            circ(2.3f, 2.3f, 0.09f, Col.pack(0x2B2F37))
        }
        tierDots(when (type) { BuildingType.ASSEMBLER_2 -> 2; BuildingType.ASSEMBLER_3 -> 3; else -> 1 }, 3f)
        progressRing(c, 1.5f, 1.65f, 0.72f)
        recipeBadge(c, 3f)
        portArrow(3f, dir)
    }

    private fun progressRing(c: Crafter?, cx: Float, cy: Float, r: Float) {
        if (c == null || !c.crafting || lowDetail) return
        s.arc(X(cx), Y(cy), L(r), L(r + 0.06f), MathUtils.HALF_PI, -MathUtils.PI2 * c.progress, Col.pack(0x7BD389, 0.9f))
    }

    private fun electronics(dir: Int, c: Crafter?, anim: Float, working: Boolean) {
        body(3f, 0x2D9D5C)
        rr(0.45f, 0.45f, 2.1f, 2.1f, 0.12f, Col.pack(0x0F4D2A), Col.pack(0x1B6B3E))
        if (!lowDetail) {
            val trace = Col.pack(0xD4AF37, 0.8f)
            ln(0.6f, 1.2f, 2.4f, 1.2f, 0.05f, trace); ln(0.6f, 1.9f, 2.4f, 1.9f, 0.05f, trace)
            ln(1.1f, 0.6f, 1.1f, 2.4f, 0.05f, trace); ln(1.9f, 0.6f, 1.9f, 2.4f, 0.05f, trace)
            for (i in 0 until 4) {
                val on = working && ((anim * 3f + i * 0.37f) % 1f) > 0.5f
                circ(0.8f + i * 0.47f, 2.2f, 0.07f, Col.pack(if (on) 0x72EFDD else 0x2A4A40))
            }
        }
        rr(1.15f, 1.3f, 0.7f, 0.5f, 0.06f, Col.pack(0x1A1A1A))
        progressRing(c, 1.5f, 1.55f, 0.5f)
        recipeBadge(c, 3f)
        portArrow(3f, dir)
    }

    private fun chemPlant(dir: Int, c: Crafter?, anim: Float, working: Boolean) {
        body(3f, 0xEF6F6C)
        val fluid = c?.recipe?.fluidIn?.firstOrNull()?.fluid?.color ?: 0x7AE582
        for (k in 0 until 2) {
            val vx = 0.95f + k * 1.1f; val vy = 1.75f
            circ(vx, vy, 0.52f, Col.pack(0x2B2F37))
            circG(vx, vy, 0.44f, Col.pack(Col.lighten(fluid, 0.3f), 0.95f), Col.pack(Col.darken(fluid, 0.3f)))
            if (working && !lowDetail) for (bb in 0 until 3) {
                val t = ((anim * 0.8f) + bb * 0.33f + k * 0.17f) % 1f
                circ(vx - 0.15f + bb * 0.15f, vy - 0.3f + t * 0.6f, 0.05f + 0.03f * t, Col.pack(0xFFFFFF, 0.5f * (1f - t)))
            }
            s.ring(X(vx), Y(vy), L(0.44f), L(0.5f), Col.pack(0x9AA5B1))
        }
        ln(0.95f, 1.75f, 2.05f, 1.75f, 0.1f, Col.pack(0x9AA5B1))
        progressRing(c, 1.5f, 0.62f, 0.34f)
        recipeBadge(c, 3f)
        portArrow(3f, dir)
    }

    private fun refinery(dir: Int, anim: Float, working: Boolean) {
        body(3f, 0xEF6F6C)
        val towers = arrayOf(floatArrayOf(0.95f, 1.9f, 0.5f), floatArrayOf(2.05f, 1.95f, 0.42f), floatArrayOf(1.5f, 0.95f, 0.36f))
        for (t in towers) {
            circ(t[0], t[1], t[2] + 0.06f, Col.pack(0x2B2F37))
            circG(t[0], t[1], t[2], Col.pack(0xE0E4EA), Col.pack(0x7E8894))
            s.ring(X(t[0]), Y(t[1]), L(t[2] * 0.55f), L(t[2] * 0.65f), Col.pack(0x5C6672))
        }
        if (working) {
            val fl = 0.6f + 0.4f * sin(time * 11f) * sin(time * 4.3f)
            circG(0.95f, 1.9f, 0.22f, Col.pack(0xFFE08A, fl), Col.pack(0xFF6B00, 0.3f))
        }
        portArrow(3f, dir)
    }

    // ---- Storage & research -----------------------------------------------------------

    private fun chest(type: BuildingType, c: Chest?) {
        val sz = type.size.toFloat()
        val col = when (type) { BuildingType.CHEST_1 -> 0x8C96A3; BuildingType.CHEST_2 -> 0x5E7A99; else -> 0xD4A62A }
        shadow(sz, 0.1f)
        rr(0.1f, 0.1f, sz - 0.2f, sz - 0.2f, 0.12f, Col.pack(Col.darken(col, 0.35f)), Col.pack(col))
        rr(0.1f, sz * 0.55f, sz - 0.2f, 0.1f * sz, 0.03f, Col.pack(Col.darken(col, 0.5f)))
        circ(sz / 2, sz * 0.5f, 0.07f * sz, Col.pack(0xFFE08A))
        if (c != null && c.total > 0) {
            val fr = c.total.toFloat() / c.capacity
            s.rect(X(0.2f), Y(0.16f), L((sz - 0.4f) * fr), L(0.06f), Col.pack(0x7BD389))
        }
    }

    private fun tank(n: FluidNode?) {
        shadow(2f, 0.1f)
        circ(1f, 1f, 0.92f, Col.pack(0x3B414C))
        circG(1f, 1f, 0.84f, Col.pack(0xD5DBE2), Col.pack(0x8E98A4))
        var fill = 0f; var color = 0x3FA7F5
        val f = factory
        if (n != null && f != null) {
            val net = f.fluids.netAt(n.x, n.y)
            if (net >= 0 && net < f.fluids.nets.size) {
                val fn = f.fluids.nets[net]; fill = fn.fill
                if (fn.fluid >= 0) color = Fluid.ALL[fn.fluid].color
            }
        }
        circ(1f, 1f, 0.66f, Col.pack(0x2B2F37))
        if (fill > 0.01f) circG(1f, 1f, 0.66f * sqrt(fill), Col.pack(Col.lighten(color, 0.25f)), Col.pack(color))
        s.ring(X(1f), Y(1f), L(0.66f), L(0.72f), Col.pack(0x5C6672))
    }

    private fun lab(l: Lab?, anim: Float, working: Boolean) {
        body(2f, 0xB388FF, radius = 0.5f)
        circ(1f, 1f, 0.62f, Col.pack(0x241B33))
        val pulse = if (working) 0.6f + 0.4f * sin(time * 3f) else 0.25f
        circG(1f, 1f, 0.5f, Col.pack(0xE0D1FF, pulse), Col.pack(0x5B3E96, 0.6f))
        // Rotating ring of science-pack colour segments.
        val colors = intArrayOf(0xE63946, 0x52B788, 0x4895EF, 0x9D4EDD, 0xFFD166)
        for (k in 0 until 5) {
            val a = anim * 1.5f + k * MathUtils.PI2 / 5
            val has = l == null || l.packs[Item.SCIENCE[k].id] > 0
            s.arc(X(1f), Y(1f), L(0.66f), L(0.8f), a, MathUtils.PI2 / 5 * 0.7f, Col.pack(colors[k], if (has) 0.95f else 0.2f))
        }
        circ(1f, 1f, 0.16f, Col.pack(0xFFFFFF, 0.85f))
    }

    private fun core(anim: Float) {
        s.ngon(X(2.12f), Y(1.82f), L(2f), 8, MathUtils.PI / 8, SHADOW)
        s.ngon(X(2f), Y(2f), L(1.95f), 8, MathUtils.PI / 8, Col.pack(0x2A2E36))
        s.ngon(X(2f), Y(2f), L(1.8f), 8, MathUtils.PI / 8, Col.pack(0x8A6A1F), Col.pack(0x4A3A12))
        s.ngon(X(2f), Y(2f), L(1.55f), 8, MathUtils.PI / 8, Col.pack(0x3A3F48), Col.pack(0x2A2E36))
        // Rotating ring with glowing nodes.
        s.ring(X(2f), Y(2f), L(1.15f), L(1.28f), Col.pack(0xFFB703, 0.9f))
        for (k in 0 until 6) {
            val a = anim * 0.6f + k * MathUtils.PI2 / 6
            val nx = 2f + MathUtils.cos(a) * 1.21f; val ny = 2f + MathUtils.sin(a) * 1.21f
            circ(nx, ny, 0.17f, Col.pack(0xFFE08A))
        }
        val pulse = 0.75f + 0.25f * sin(time * 2f)
        circG(2f, 2f, 0.85f, Col.pack(0xFFF3C4, pulse), Col.pack(0xFFB703, 0.2f))
        circ(2f, 2f, 0.35f, Col.pack(0xFFFFFF, 0.9f))
    }

    // =================================================================================
    // Items & fluids
    // =================================================================================

    /** Draw [it] centred at absolute (x, y) with radius [r]. */
    fun item(it: Item, x: Float, y: Float, r: Float) {
        val c = Col.pack(it.color); val a = Col.pack(it.accent)
        if (lowDetail || r * s.pixelScale < 3.5f) { s.rect(x - r * 0.7f, y - r * 0.7f, r * 1.4f, r * 1.4f, c); return }
        when (it.shape) {
            ItemShape.ROCK -> {
                s.ngon(x + r * 0.05f, y - r * 0.08f, r, 6, 0.3f, a)
                s.ngon(x - r * 0.08f, y + r * 0.06f, r * 0.78f, 6, 0.1f, c)
                s.circle(x - r * 0.3f, y + r * 0.3f, r * 0.18f, Col.pack(0xFFFFFF, 0.35f))
            }
            ItemShape.PLATE -> {
                s.roundRect(x - r * 0.85f, y - r * 0.85f, r * 1.7f, r * 1.7f, r * 0.25f, a)
                s.roundRect(x - r * 0.7f, y - r * 0.62f, r * 1.4f, r * 1.4f, r * 0.2f, c, Col.pack(Col.lighten(it.color, 0.25f)))
            }
            ItemShape.BRICK -> {
                s.roundRect(x - r, y - r * 0.6f, r * 2f, r * 1.2f, r * 0.15f, a)
                s.roundRect(x - r * 0.9f, y - r * 0.45f, r * 1.8f, r * 0.95f, r * 0.12f, c)
            }
            ItemShape.BAR -> {
                s.roundRect(x - r * 1.05f, y - r * 0.5f, r * 2.1f, r * 1f, r * 0.2f, a)
                s.roundRect(x - r * 0.95f, y - r * 0.32f, r * 1.9f, r * 0.72f, r * 0.15f, c, Col.pack(Col.lighten(it.color, 0.3f)))
            }
            ItemShape.GEAR -> {
                s.gear(x, y, r, 8, 0f, a)
                s.gear(x, y + r * 0.06f, r * 0.9f, 8, 0f, c)
                s.circle(x, y, r * 0.3f, a)
            }
            ItemShape.COIL -> {
                s.ring(x, y, r * 0.45f, r, a)
                s.ring(x, y, r * 0.55f, r * 0.85f, c)
            }
            ItemShape.CHIP -> {
                for (k in -1..1) {
                    s.rect(x - r * 1.05f, y + k * r * 0.45f - r * 0.08f, r * 2.1f, r * 0.16f, Col.pack(0xC0C0C0))
                }
                s.roundRect(x - r * 0.8f, y - r * 0.8f, r * 1.6f, r * 1.6f, r * 0.15f, a)
                s.roundRect(x - r * 0.6f, y - r * 0.6f, r * 1.2f, r * 1.2f, r * 0.1f, c)
            }
            ItemShape.TUBE -> {
                s.capsule(x - r * 0.8f, y, x + r * 0.8f, y, r * 0.9f, a)
                s.capsule(x - r * 0.7f, y + r * 0.05f, x + r * 0.7f, y + r * 0.05f, r * 0.6f, c)
            }
            ItemShape.ENGINE -> {
                s.roundRect(x - r, y - r * 0.75f, r * 2f, r * 1.5f, r * 0.25f, Col.pack(Col.darken(it.color, 0.3f)))
                s.roundRect(x - r * 0.85f, y - r * 0.55f, r * 1.2f, r * 1.1f, r * 0.2f, c)
                s.circle(x + r * 0.5f, y, r * 0.4f, a)
            }
            ItemShape.BEAD -> {
                s.circle(x, y, r * 0.9f, a)
                s.circleGrad(x - r * 0.1f, y + r * 0.1f, r * 0.75f, Col.pack(0xFFFFFF), c)
            }
            ItemShape.CAPSULE -> {
                s.capsule(x, y - r * 0.55f, x, y + r * 0.45f, r * 1.1f, a)
                s.capsule(x, y - r * 0.45f, x, y + r * 0.2f, r * 0.8f, c)
                s.rect(x - r * 0.2f, y + r * 0.8f, r * 0.4f, r * 0.25f, Col.pack(0xDDDDDD))
            }
            ItemShape.FRAME -> {
                s.roundRectOutline(x - r, y - r, r * 2f, r * 2f, r * 0.2f, r * 0.4f, a)
                s.roundRectOutline(x - r * 0.85f, y - r * 0.85f, r * 1.7f, r * 1.7f, r * 0.15f, r * 0.25f, c)
                s.circle(x, y, r * 0.25f, a)
            }
            ItemShape.CRYSTAL -> {
                s.ngon(x, y, r * 1.1f, 4, MathUtils.HALF_PI, a)
                s.ngon(x, y + r * 0.08f, r * 0.8f, 4, MathUtils.HALF_PI, c, Col.pack(Col.lighten(it.color, 0.4f)))
            }
            ItemShape.FLASK -> {
                s.rect(x - r * 0.25f, y + r * 0.1f, r * 0.5f, r * 0.8f, Col.pack(0xE8EEF2))
                s.circle(x, y - r * 0.2f, r * 0.8f, Col.pack(0xE8EEF2))
                s.circle(x, y - r * 0.25f, r * 0.62f, c)
                s.rect(x - r * 0.35f, y + r * 0.8f, r * 0.7f, r * 0.2f, Col.pack(0x8C6A4A))
            }
            ItemShape.POWDER -> {
                s.circle(x - r * 0.4f, y - r * 0.3f, r * 0.5f, a)
                s.circle(x + r * 0.4f, y - r * 0.3f, r * 0.5f, a)
                s.circle(x, y + r * 0.2f, r * 0.55f, c)
                s.circle(x - r * 0.35f, y - r * 0.25f, r * 0.4f, c)
                s.circle(x + r * 0.35f, y - r * 0.25f, r * 0.4f, c)
            }
        }
    }

    fun fluidDrop(fl: Fluid, x: Float, y: Float, r: Float) {
        val c = Col.pack(fl.color)
        s.circle(x, y - r * 0.2f, r * 0.75f, c)
        s.tri(x - r * 0.68f, y - r * 0.05f, x + r * 0.68f, y - r * 0.05f, x, y + r * 1.05f, c)
        s.circle(x - r * 0.25f, y - r * 0.05f, r * 0.2f, Col.pack(0xFFFFFF, 0.5f))
    }

    // =================================================================================
    // Night lights
    // =================================================================================

    /** Additive glow for a building at night (called with additive blending on). */
    fun nightGlow(b: Building, strength: Float) {
        val cx = b.cx; val cy = b.cy
        val r = b.size * 0.9f
        when (b.type) {
            BuildingType.CORE -> s.glow(cx, cy, 6f, 0xFFB703, 0.55f * strength)
            BuildingType.STONE_FURNACE, BuildingType.COAL_GEN, BuildingType.REFINERY ->
                if (b.working) s.glow(cx, cy, r * 1.6f, 0xFF8C42, 0.5f * strength)
            BuildingType.POLE, BuildingType.BIG_POLE ->
                if (b.working) s.glow(cx, cy, 1.2f, 0x9FD8F5, 0.25f * strength)
            BuildingType.BELT, BuildingType.UNDERGROUND, BuildingType.PIPE_SEG -> {}
            else -> if (b.working) s.glow(cx, cy, r * 1.3f, Col.lighten(b.type.color, 0.3f), 0.32f * strength)
                    else if (b.type.power > 0f) s.glow(cx, cy, r * 0.6f, 0x88AADD, 0.08f * strength)
        }
    }

    companion object {
        val SHADOW = Col.pack(0x000000, 0.32f)
        const val BELT_RAIL = 0xF2B134
    }
}
