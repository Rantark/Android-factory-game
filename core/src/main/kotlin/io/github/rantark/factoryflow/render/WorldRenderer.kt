package io.github.rantark.factoryflow.render

import com.badlogic.gdx.math.MathUtils
import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.game.Ghost
import io.github.rantark.factoryflow.game.Mode
import io.github.rantark.factoryflow.game.Session
import io.github.rantark.factoryflow.gfx.Col
import io.github.rantark.factoryflow.gfx.Font
import io.github.rantark.factoryflow.gfx.VectorBatch
import io.github.rantark.factoryflow.sim.Belt
import io.github.rantark.factoryflow.sim.Building
import io.github.rantark.factoryflow.sim.Dir
import io.github.rantark.factoryflow.sim.Drill
import io.github.rantark.factoryflow.sim.Inserter
import io.github.rantark.factoryflow.sim.Pipe
import io.github.rantark.factoryflow.sim.PowerPole
import io.github.rantark.factoryflow.world.Resource
import io.github.rantark.factoryflow.world.World
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the world in layers: terrain chunks → pipes/belts → items → machines →
 * inserters/poles → wires → labels → night overlay + additive glow → ghosts.
 * Everything off-screen is culled; all dynamic geometry goes through one batch.
 */
class WorldRenderer(private val batch: VectorBatch, private val font: Font, private val painter: Painter) {
    var terrain: TerrainRenderer? = null
    private val visible = ArrayList<Building>(1024)

    fun render(s: Session, time: Float) {
        val f = s.factory
        val cam = s.cam
        batch.begin(cam.ortho.combined, cam.ppt)
        val x0 = cam.left.toInt() - 1; val y0 = cam.bottom.toInt() - 1
        val x1 = cam.right.toInt() + 1; val y1 = cam.top.toInt() + 1
        terrain?.draw(x0, y0, x1, y1)

        painter.s = batch
        painter.time = time
        painter.factory = f
        painter.lowDetail = cam.ppt < 22f

        visible.clear()
        for (b in f.buildings) {
            if (b.x + b.size < x0 || b.y + b.size < y0 || b.x > x1 || b.y > y1) continue
            visible.add(b)
        }

        // Power coverage while placing something electric.
        val bt = s.buildType
        if (s.mode == Mode.PLACE && bt != null && (bt.power > 0f || bt == BuildingType.POLE || bt == BuildingType.BIG_POLE)) {
            val c = Col.pack(0x4FC3F7, 0.08f)
            for (b in visible) if (b is PowerPole) {
                val r = (b as PowerPole).supplyRadius
                batch.rect((b.x - r).toFloat(), (b.y - r).toFloat(), (b.size + 2 * r).toFloat(), (b.size + 2 * r).toFloat(), c)
            }
        }

        // Layer 1: pipes & belts (flat on the ground).
        for (b in visible) if (b is Pipe || b is Belt) painter.building(b.type, b.x.toFloat(), b.y.toFloat(), 1f, b.dir, b)
        // Layer 2: items on belts.
        for (b in visible) if (b is Belt && b.count > 0) painter.beltItems(b)
        // Layer 3: machines.
        for (b in visible) if (b !is Pipe && b !is Belt && b !is Inserter && (b !is PowerPole || b.type == BuildingType.CORE))
            painter.building(b.type, b.x.toFloat(), b.y.toFloat(), 1f, b.dir, b)
        for (b in visible) if (b is PowerPole && b.type != BuildingType.CORE) painter.building(b.type, b.x.toFloat(), b.y.toFloat(), 1f, b.dir, b)
        // Layer 4: inserters above machines so their arms overlap.
        for (b in visible) if (b is Inserter) painter.building(b.type, b.x.toFloat(), b.y.toFloat(), 1f, b.dir, b)
        // Wires.
        for ((a, b) in f.power.wires) {
            if (max(a.cx, b.cx) < x0 || min(a.cx, b.cx) > x1 || max(a.cy, b.cy) < y0 || min(a.cy, b.cy) > y1) continue
            wire(a.cx, a.cy + 0.15f, b.cx, b.cy + 0.15f, 1f)
        }
        if (cam.ppt >= 26f) depositLabels(s, x0, y0, x1, y1)

        // Night: darken the world, then add light from working buildings.
        val dark = (1f - f.daylight)
        if (dark > 0.01f) {
            batch.rect(x0.toFloat(), y0.toFloat(), (x1 - x0 + 1).toFloat(), (y1 - y0 + 1).toFloat(), Col.pack(0x070B24, dark * 0.62f))
            batch.setAdditive(true)
            for (b in visible) painter.nightGlow(b, dark)
            batch.setAdditive(false)
        }

        // Selection & ghosts sit above the night overlay so they stay readable.
        s.selected?.let { selection(it, time) }
        if (s.mode == Mode.PLACE) ghosts(s, s.ghosts(), time)
        if (s.mode == Mode.BULLDOZE) batch.rect(x0.toFloat(), y0.toFloat(), (x1 - x0 + 1).toFloat(), (y1 - y0 + 1).toFloat(), Col.pack(0xFF3B30, 0.05f))
        batch.end()
    }

    /** Sagging power line between two poles. */
    private fun wire(ax: Float, ay: Float, bx: Float, by: Float, alpha: Float) {
        val len = hypot(bx - ax, by - ay)
        val sag = 0.15f + len * 0.03f
        var px = ax; var py = ay
        val n = 10
        for (k in 1..n) {
            val t = k / n.toFloat()
            val x = ax + (bx - ax) * t
            val y = ay + (by - ay) * t - sag * 4f * t * (1f - t)
            batch.line(px, py - 0.08f, x, y - 0.08f, 0.05f, Col.pack(0x000000, 0.18f * alpha))
            batch.line(px, py, x, y, 0.045f, Col.pack(0x3A2A1E, 0.9f * alpha))
            batch.line(px, py + 0.012f, x, y + 0.012f, 0.015f, Col.pack(0xD08A4A, 0.6f * alpha))
            px = x; py = y
        }
    }

    private fun depositLabels(s: Session, x0: Int, y0: Int, x1: Int, y1: Int) {
        val w = s.factory.world
        for (d in w.deposits) {
            if (d.cx < x0 || d.cx > x1 || d.cy < y0 || d.cy > y1) continue
            if (d.res == Resource.OIL || d.remaining <= 0) continue
            val frac = d.remaining.toFloat() / max(1L, d.initial)
            val label = "${d.res.title} ${fmt(d.remaining)}"
            val size = 0.28f
            val tw = font.width(label, size) + 0.4f
            val bx = d.cx - tw / 2; val by = d.cy + 0.9f
            batch.roundRect(bx, by - 0.12f, tw, 0.62f, 0.14f, Col.pack(0x121418, 0.6f))
            font.draw(batch, label, d.cx, by + 0.14f, size, Col.pack(0xF1EADF, 0.9f), 0)
            batch.roundRect(bx + 0.18f, by - 0.02f, tw - 0.36f, 0.09f, 0.04f, Col.pack(0x000000, 0.6f))
            batch.roundRect(bx + 0.18f, by - 0.02f, (tw - 0.36f) * frac, 0.09f, 0.04f, Col.pack(if (frac > 0.3f) 0x7BD389 else 0xFFC145))
        }
    }

    private fun selection(b: Building, time: Float) {
        val pulse = 0.6f + 0.4f * sin(time * 4f)
        val c = Col.pack(0xFFB703, pulse)
        batch.roundRectOutline(b.x - 0.08f, b.y - 0.08f, b.size + 0.16f, b.size + 0.16f, 0.25f, 0.07f, c)
        when (b) {
            is Inserter -> {
                marker(b.pickX, b.pickY, 0x7BD389); marker(b.dropX, b.dropY, 0xFFB703)
            }
            is Drill -> batch.rect(b.x.toFloat(), b.y.toFloat(), b.size.toFloat(), b.size.toFloat(), Col.pack(0xFFB703, 0.1f))
            is PowerPole -> {
                val r = (b as PowerPole).supplyRadius
                batch.roundRectOutline((b.x - r).toFloat(), (b.y - r).toFloat(), (b.size + 2 * r).toFloat(), (b.size + 2 * r).toFloat(), 0.2f, 0.05f, Col.pack(0x4FC3F7, 0.8f))
            }
        }
        if (b.type.rotatable && b !is Belt) {
            val ax = b.cx + Dir.DX[b.dir] * (b.size / 2f + 0.35f); val ay = b.cy + Dir.DY[b.dir] * (b.size / 2f + 0.35f)
            batch.arrowHead(ax, ay, 0.22f, b.dir * MathUtils.HALF_PI, c)
        }
    }

    private fun marker(x: Int, y: Int, rgb: Int) {
        batch.roundRectOutline(x + 0.1f, y + 0.1f, 0.8f, 0.8f, 0.15f, 0.06f, Col.pack(rgb, 0.9f))
    }

    private fun ghosts(s: Session, list: List<Ghost>, time: Float) {
        val f = s.factory
        val first = list.firstOrNull() ?: return
        // Faint grid around the ghost helps line things up.
        val gx = first.x; val gy = first.y
        for (k in -7..8) {
            val a = 0.12f * (1f - abs(k - 0.5f) / 8f)
            batch.rect((gx + k).toFloat() - 0.01f, (gy - 7).toFloat(), 0.02f, 15f, Col.pack(0xFFFFFF, a))
            batch.rect((gx - 7).toFloat(), (gy + k).toFloat() - 0.01f, 15f, 0.02f, Col.pack(0xFFFFFF, a))
        }
        for (g in list) {
            val sz = g.type.size.toFloat()
            painter.building(g.type, g.x.toFloat(), g.y.toFloat(), 1f, g.dir, null)
            val ok = g.problem == null
            val tint = if (ok) 0x7BD389 else 0xFF5A5F
            val pulse = 0.25f + 0.1f * sin(time * 5f)
            batch.roundRect(g.x.toFloat(), g.y.toFloat(), sz, sz, 0.2f, Col.pack(tint, pulse))
            batch.roundRectOutline(g.x.toFloat(), g.y.toFloat(), sz, sz, 0.2f, 0.06f, Col.pack(tint, 0.95f))
            if (g.type.rotatable && list.size == 1) {
                val cx = g.x + sz / 2; val cy = g.y + sz / 2
                batch.arrowHead(cx + Dir.DX[g.dir] * (sz / 2 + 0.3f), cy + Dir.DY[g.dir] * (sz / 2 + 0.3f), 0.22f, g.dir * MathUtils.HALF_PI, Col.pack(0xFFFFFF, 0.9f))
            }
        }
        if (list.size != 1) return
        val g = first
        when (g.type) {
            BuildingType.POLE, BuildingType.BIG_POLE -> {
                val r = if (g.type == BuildingType.BIG_POLE) 1 else 2
                val reach = if (g.type == BuildingType.BIG_POLE) 18f else 8f
                batch.roundRect((g.x - r).toFloat(), (g.y - r).toFloat(), (1 + 2 * r).toFloat(), (1 + 2 * r).toFloat(), 0.2f, Col.pack(0x4FC3F7, 0.15f))
                for (b in f.buildings) if (b is PowerPole) {
                    val d = hypot(b.cx - (g.x + 0.5f), b.cy - (g.y + 0.5f))
                    if (d <= min(reach, (b as PowerPole).wireReach) && d > 0.1f) wire(g.x + 0.5f, g.y + 0.65f, b.cx, b.cy + 0.15f, 0.6f)
                }
            }
            BuildingType.INSERTER, BuildingType.LONG_INSERTER, BuildingType.FAST_INSERTER, BuildingType.FILTER_INSERTER -> {
                val reach = if (g.type == BuildingType.LONG_INSERTER) 2 else 1
                marker(g.x - Dir.DX[g.dir] * reach, g.y - Dir.DY[g.dir] * reach, 0x7BD389)
                marker(g.x + Dir.DX[g.dir] * reach, g.y + Dir.DY[g.dir] * reach, 0xFFB703)
            }
            BuildingType.UNDERGROUND -> for (k in 1..6) {
                val tx = g.x + Dir.DX[g.dir] * k; val ty = g.y + Dir.DY[g.dir] * k
                batch.circle(tx + 0.5f, ty + 0.5f, 0.08f, Col.pack(0xFFE08A, 0.5f))
            }
            else -> {}
        }
        g.problem?.let {
            val cx = g.x + g.type.size / 2f
            val ty = g.y + g.type.size + 0.35f
            val tw = font.width(it, 0.32f) + 0.4f
            batch.roundRect(cx - tw / 2, ty - 0.15f, tw, 0.6f, 0.15f, Col.pack(0x2A0E10, 0.85f))
            font.draw(batch, it, cx, ty, 0.32f, Col.pack(0xFF8A8A), 0)
        }
    }

    companion object {
        fun fmt(v: Long): String = when {
            v >= 1_000_000 -> String.format("%.1fM", v / 1e6)
            v >= 10_000 -> "${v / 1000}k"
            v >= 1000 -> String.format("%.1fk", v / 1e3)
            else -> v.toString()
        }
    }
}
