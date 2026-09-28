package io.github.rantark.factoryflow.render

import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Disposable
import io.github.rantark.factoryflow.gfx.Col
import io.github.rantark.factoryflow.gfx.MeshBuilder
import io.github.rantark.factoryflow.gfx.VectorBatch
import io.github.rantark.factoryflow.world.Biome
import io.github.rantark.factoryflow.world.Resource
import io.github.rantark.factoryflow.world.World
import kotlin.math.max
import kotlin.math.min

/**
 * Bakes each 32×32-tile chunk into one static mesh: a smoothly shaded height-free
 * "terrain mesh" (every tile corner takes the average colour of the four tiles around
 * it, so biomes blend softly) plus procedural decorations – grass tufts, trees, rocks,
 * reeds, ore nuggets and oil pools. Only chunks near the camera are built or drawn,
 * and far-away meshes are evicted.
 */
class TerrainRenderer(private val world: World, private val batch: VectorBatch) : Disposable {
    private val n = World.CHUNKS * World.CHUNKS
    private val meshes = arrayOfNulls<Mesh>(n)
    private val counts = IntArray(n)
    private val lastUsed = LongArray(n)
    private var frame = 0L
    private val mb = MeshBuilder()
    private val tileCol = IntArray((World.CHUNK + 2) * (World.CHUNK + 2))

    /** Draw every chunk overlapping the tile rectangle; build at most a few per frame. */
    fun draw(x0: Int, y0: Int, x1: Int, y1: Int) {
        frame++
        var builds = 0
        val cx0 = max(0, x0 / World.CHUNK); val cy0 = max(0, y0 / World.CHUNK)
        val cx1 = min(World.CHUNKS - 1, x1 / World.CHUNK); val cy1 = min(World.CHUNKS - 1, y1 / World.CHUNK)
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            val i = cy * World.CHUNKS + cx
            if (meshes[i] == null || (world.chunkDirty[i] && builds < 2)) {
                if (builds >= 6 && meshes[i] != null) continue
                build(cx, cy); builds++
            }
            lastUsed[i] = frame
            meshes[i]?.let { batch.drawMesh(it, counts[i]) }
        }
        evict()
    }

    private fun evict() {
        var live = 0
        for (m in meshes) if (m != null) live++
        if (live <= MAX_MESHES) return
        // Drop the least-recently used meshes.
        val order = (0 until n).filter { meshes[it] != null }.sortedBy { lastUsed[it] }
        for (i in order.take(live - MAX_MESHES)) { meshes[i]?.dispose(); meshes[i] = null }
    }

    private fun tileColor(x: Int, y: Int): Int {
        val xx = x.coerceIn(0, World.W - 1); val yy = y.coerceIn(0, World.H - 1)
        val i = world.idx(xx, yy)
        if (world.water[i]) {
            val depth = world.detail(xx / 9f, yy / 9f) * 0.5f + 0.5f
            return Col.mix(0x1B4965, 0x2A6F97, depth)
        }
        val b = Biome.entries[world.biome[i].toInt()]
        val low = world.detail(xx / 23f + 100f, yy / 23f) * 0.5f + 0.5f
        var c = Col.mix(b.base, b.alt, (low * 0.7f + world.hash(xx, yy) * 0.3f))
        val r = Resource.ALL[world.res[i].toInt()]
        if (r != Resource.NONE) c = Col.mix(c, if (r == Resource.OIL) 0x1A1420 else r.color, 0.22f)
        return c
    }

    private fun build(cx: Int, cy: Int) {
        val i = cy * World.CHUNKS + cx
        world.chunkDirty[i] = false
        mb.reset()
        mb.whiteU = batch.whiteU; mb.whiteV = batch.whiteV
        mb.pixelScale = 40f
        val bx = cx * World.CHUNK; val by = cy * World.CHUNK
        val w = World.CHUNK + 2
        // Tile colours including a 1-tile border so corner averaging is seamless.
        for (ty in 0 until w) for (tx in 0 until w) tileCol[ty * w + tx] = tileColor(bx + tx - 1, by + ty - 1)
        fun corner(x: Int, y: Int): Float {
            // Corner (x, y) in chunk-local coordinates touches tiles (x-1..x, y-1..y).
            val a = tileCol[y * w + x]; val b = tileCol[y * w + x + 1]
            val c = tileCol[(y + 1) * w + x]; val d = tileCol[(y + 1) * w + x + 1]
            val r = ((a shr 16 and 255) + (b shr 16 and 255) + (c shr 16 and 255) + (d shr 16 and 255)) / 4
            val g = ((a shr 8 and 255) + (b shr 8 and 255) + (c shr 8 and 255) + (d shr 8 and 255)) / 4
            val bl = ((a and 255) + (b and 255) + (c and 255) + (d and 255)) / 4
            return Col.pack((r shl 16) or (g shl 8) or bl)
        }
        for (ty in 0 until World.CHUNK) for (tx in 0 until World.CHUNK) {
            val x = (bx + tx).toFloat(); val y = (by + ty).toFloat()
            mb.quad(x, y, x + 1, y, x + 1, y + 1, x, y + 1,
                corner(tx, ty), corner(tx + 1, ty), corner(tx + 1, ty + 1), corner(tx, ty + 1))
        }
        for (ty in 0 until World.CHUNK) for (tx in 0 until World.CHUNK) decorate(bx + tx, by + ty)

        val floats = mb.floatCount
        val verts = floats / VectorBatch.VSIZE
        meshes[i]?.dispose()
        val m = Mesh(true, verts, 0, VectorBatch.attributes())
        m.setVertices(mb.data, 0, floats)
        meshes[i] = m
        counts[i] = verts
    }

    /** Small procedural details for one tile. */
    private fun decorate(x: Int, y: Int) {
        val i = world.idx(x, y)
        val h = world.hash(x, y, 1)
        val h2 = world.hash(x, y, 2)
        val fx = x.toFloat(); val fy = y.toFloat()
        if (world.water[i]) {
            if (h < 0.18f) {
                val ox = fx + 0.2f + h2 * 0.5f; val oy = fy + 0.3f + h * 2f
                mb.line(ox, oy, ox + 0.35f, oy, 0.05f, Col.pack(0x8ECAE6, 0.35f))
            }
            return
        }
        val res = Resource.ALL[world.res[i].toInt()]
        if (res != Resource.NONE) { ore(res, x, y, h, h2); return }
        when (Biome.entries[world.biome[i].toInt()]) {
            Biome.GRASSLAND -> if (h < 0.3f) {
                val c = Col.pack(if (h2 < 0.5f) 0x5E8A45 else 0x2F4A26, 0.8f)
                val gx = fx + 0.2f + h2 * 0.6f; val gy = fy + 0.2f + h * 2f
                mb.tri(gx - 0.08f, gy, gx + 0.08f, gy, gx - 0.02f, gy + 0.22f, c)
                mb.tri(gx + 0.02f, gy, gx + 0.16f, gy, gx + 0.12f, gy + 0.17f, c)
                if (h < 0.04f) { // a few wildflowers
                    mb.circle(gx + 0.3f, gy + 0.1f, 0.06f, Col.pack(if (h2 < 0.5f) 0xF7D26A else 0xE9A3C9, 0.9f), 6)
                }
            }
            Biome.FOREST -> if (h < 0.42f) {
                val r = 0.28f + h2 * 0.2f
                val tx = fx + 0.5f + (h2 - 0.5f) * 0.3f; val ty = fy + 0.5f + (h - 0.2f) * 0.4f
                mb.circle(tx + 0.08f, ty - 0.1f, r, Col.pack(0x000000, 0.25f), 10)
                mb.circle(tx, ty, r, Col.pack(0x1E3B1E), 10)
                mb.circle(tx - r * 0.25f, ty + r * 0.25f, r * 0.6f, Col.pack(0x2F5A2C), 10)
                mb.circle(tx - r * 0.35f, ty + r * 0.35f, r * 0.25f, Col.pack(0x4A7A3A, 0.8f), 8)
            }
            Biome.HIGHLANDS -> if (h < 0.22f) {
                val r = 0.12f + h2 * 0.2f
                val rx = fx + 0.3f + h2 * 0.4f; val ry = fy + 0.3f + h * 1.6f
                mb.ngon(rx + 0.04f, ry - 0.05f, r, 5, h * 6f, Col.pack(0x000000, 0.25f))
                mb.ngon(rx, ry, r, 5, h * 6f, Col.pack(0x6E675D), Col.pack(0x4E4841))
                mb.circle(rx - r * 0.3f, ry + r * 0.3f, r * 0.3f, Col.pack(0x8A8378, 0.8f), 6)
            }
            Biome.SANDY -> if (h < 0.16f) {
                val sx = fx + 0.1f + h2 * 0.4f; val sy = fy + 0.2f + h * 3f
                mb.arc(sx + 0.3f, sy - 0.25f, 0.3f, 0.35f, MathUtils.PI * 0.25f, MathUtils.PI * 0.5f, Col.pack(0xB89C6A, 0.6f), 10)
            } else if (h < 0.2f) {
                mb.circle(fx + h2, fy + 0.5f, 0.07f, Col.pack(0x6B5A3E), 6)
            }
            Biome.WETLANDS -> if (h < 0.25f) {
                val rx = fx + 0.2f + h2 * 0.6f; val ry = fy + 0.2f
                val c = Col.pack(0x6B8F5E, 0.9f)
                for (k in 0 until 3) mb.line(rx + k * 0.08f, ry, rx + k * 0.08f + (k - 1) * 0.06f, ry + 0.35f + k * 0.05f, 0.04f, c)
                if (h < 0.06f) mb.circle(fx + 0.5f, fy + 0.6f, 0.22f, Col.pack(0x24453F, 0.8f), 10)
            }
        }
    }

    private fun ore(res: Resource, x: Int, y: Int, h: Float, h2: Float) {
        val i = world.idx(x, y)
        val init = max(1, world.initialAmount[i])
        val rich = (world.amount[i].toFloat() / init).coerceIn(0f, 1f)
        val fx = x.toFloat(); val fy = y.toFloat()
        when (res) {
            Resource.OIL -> {
                mb.circleGrad(fx + 0.5f, fy + 0.5f, 0.62f, Col.pack(0x0E0A12, 0.95f), Col.pack(0x1A1420, 0f), 14)
                mb.circle(fx + 0.4f, fy + 0.6f, 0.14f, Col.pack(0x7B4FA0, 0.35f), 8)
                mb.circle(fx + 0.6f, fy + 0.4f, 0.07f, Col.pack(0x4FC3F7, 0.25f), 6)
            }
            Resource.CRYSTAL -> {
                val count = 1 + (rich * 2.5f).toInt()
                mb.circleGrad(fx + 0.5f, fy + 0.5f, 0.55f, Col.pack(0xC77DFF, 0.28f), Col.pack(0xC77DFF, 0f), 12)
                for (k in 0 until count) {
                    val px = fx + 0.25f + world.hash(x, y, 10 + k) * 0.5f
                    val py = fy + 0.25f + world.hash(x, y, 20 + k) * 0.5f
                    mb.ngon(px, py, 0.18f, 4, MathUtils.HALF_PI, Col.pack(0x7B2CBF), Col.pack(0x5A189A))
                    mb.ngon(px, py + 0.03f, 0.11f, 4, MathUtils.HALF_PI, Col.pack(0xE0AAFF))
                }
            }
            else -> {
                val count = 1 + (rich * 3.2f).toInt()
                val base = res.color
                for (k in 0 until count) {
                    val px = fx + 0.2f + world.hash(x, y, 10 + k) * 0.6f
                    val py = fy + 0.2f + world.hash(x, y, 20 + k) * 0.6f
                    val r = 0.1f + world.hash(x, y, 30 + k) * 0.1f
                    mb.ngon(px + 0.03f, py - 0.04f, r, 6, h * 5f + k, Col.pack(0x000000, 0.3f))
                    mb.ngon(px, py, r, 6, h2 * 5f + k, Col.pack(base), Col.pack(Col.darken(base, 0.4f)))
                    mb.circle(px - r * 0.3f, py + r * 0.3f, r * 0.28f, Col.pack(0xFFFFFF, if (res == Resource.COAL) 0.18f else 0.35f), 6)
                }
            }
        }
    }

    override fun dispose() { for (m in meshes) m?.dispose() }

    companion object { const val MAX_MESHES = 140 }
}
