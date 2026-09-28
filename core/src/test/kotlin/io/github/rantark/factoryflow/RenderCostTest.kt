package io.github.rantark.factoryflow

import io.github.rantark.factoryflow.game.DemoFactory
import io.github.rantark.factoryflow.gfx.MeshBuilder
import io.github.rantark.factoryflow.render.Painter
import io.github.rantark.factoryflow.sim.Belt
import io.github.rantark.factoryflow.sim.Scenario
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures CPU time to generate one frame of vector geometry for a large factory
 * (everything drawn at once, i.e. worse than any real viewport). No GPU involved.
 */
class RenderCostTest {
    @Test
    fun frameGeometryIsCheap() {
        val f = Scenario.newGame(42)
        DemoFactory.build(f)
        DemoFactory.stress(f)
        repeat(120) { f.tick(1f / 60f) }
        val mb = MeshBuilder(1 shl 20)
        val p = Painter().apply { s = mb; factory = f }
        mb.pixelScale = 40f
        fun frame() {
            mb.reset()
            for (b in f.buildings) p.building(b.type, b.x.toFloat(), b.y.toFloat(), 1f, b.dir, b)
            for (b in f.buildings) if (b is Belt && b.count > 0) p.beltItems(b)
        }
        repeat(30) { frame() } // JIT warm-up
        val n = 50
        val t0 = System.nanoTime()
        repeat(n) { frame() }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        val verts = mb.floatCount / 5
        println("Frame geometry: ${f.buildings.size} buildings, $verts vertices, ${"%.2f".format(ms)} ms")
        assertTrue("geometry too slow: $ms ms", ms < 12.0)
    }
}
