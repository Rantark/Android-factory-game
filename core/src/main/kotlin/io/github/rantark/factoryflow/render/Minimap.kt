package io.github.rantark.factoryflow.render

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import java.nio.ByteOrder
import io.github.rantark.factoryflow.sim.Factory
import io.github.rantark.factoryflow.world.Biome
import io.github.rantark.factoryflow.world.Resource
import io.github.rantark.factoryflow.world.World

/**
 * 512×512 overview (one pixel per tile) of explored terrain, resources and buildings.
 * The HUD shows a window of it centred on the camera.
 * Regenerated when new terrain is explored, and refreshed every couple of seconds so
 * newly placed buildings appear.
 */
class Minimap(private val f: Factory) : Disposable {
    private val pm = Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888)
    val texture: Texture
    private val base = IntArray(SIZE * SIZE)
    private val frame = IntArray(SIZE * SIZE)
    private var baseVersion = -1
    private var timer = 0f

    init {
        pm.blending = Pixmap.Blending.None
        texture = Texture(pm)
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Nearest)
        refresh(true)
    }

    fun update(dt: Float) {
        timer -= dt
        if (timer <= 0f || baseVersion != f.world.exploredVersion) refresh(baseVersion != f.world.exploredVersion)
    }

    private fun refresh(rebuildBase: Boolean) {
        timer = 2f
        val w = f.world
        if (rebuildBase) {
            baseVersion = w.exploredVersion
            for (py in 0 until SIZE) for (px in 0 until SIZE) {
                val x = px; val y = SIZE - 1 - py
                base[py * SIZE + px] = if (!w.isExplored(x, y)) 0x262A31FF else {
                    val i = w.idx(x, y)
                    val r = Resource.ALL[w.res[i].toInt()]
                    val rgb = when {
                        w.water[i] -> 0x2A6F97
                        r == Resource.OIL -> 0x6A3F8A
                        r != Resource.NONE -> r.color
                        else -> Biome.entries[w.biome[i].toInt()].let { b -> brighten(b.base) }
                    }
                    (rgb shl 8) or 0xFF
                }
            }
        }
        System.arraycopy(base, 0, frame, 0, base.size)
        for (b in f.buildings) {
            val col = (b.type.color shl 8) or 0xFF
            val s = b.size
            for (k in 0 until s) for (j in 0 until s) {
                val px = b.x + k; val py = SIZE - 1 - (b.y + j)
                if (px in 0 until SIZE && py in 0 until SIZE) frame[py * SIZE + px] = col
            }
        }
        // Bulk copy: RGBA8888 bytes are R,G,B,A, i.e. big-endian 0xRRGGBBAA ints.
        val buf = pm.pixels.duplicate().order(ByteOrder.BIG_ENDIAN)
        buf.position(0)
        buf.asIntBuffer().put(frame)
        texture.draw(pm, 0, 0)
    }

    private fun brighten(c: Int): Int {
        val r = minOf(255, ((c shr 16) and 255) * 13 / 10)
        val g = minOf(255, ((c shr 8) and 255) * 13 / 10)
        val b = minOf(255, (c and 255) * 13 / 10)
        return (r shl 16) or (g shl 8) or b
    }

    override fun dispose() { texture.dispose(); pm.dispose() }

    companion object { const val SIZE = World.W }
}
