package io.github.rantark.factoryflow.game

import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Item
import io.github.rantark.factoryflow.data.Techs
import io.github.rantark.factoryflow.sim.Belt
import io.github.rantark.factoryflow.sim.Chest
import io.github.rantark.factoryflow.sim.CoalGenerator
import io.github.rantark.factoryflow.sim.Crafter
import io.github.rantark.factoryflow.sim.Factory
import io.github.rantark.factoryflow.sim.Lab
import io.github.rantark.factoryflow.world.Resource
import io.github.rantark.factoryflow.world.World
import kotlin.math.abs
import kotlin.math.sign

/**
 * Builds a small working showcase factory next to the core. Used for automated
 * desktop screenshots and as a performance scenario (see [stress]); never shown to
 * players unless the desktop launcher is started with --demo.
 */
object DemoFactory {
    fun build(f: Factory) {
        for (i in f.stock.indices) f.stock[i] += 5000
        for (id in listOf("logistics", "automation", "steel", "logistic_science", "fluid_handling")) f.research.complete(Techs[id], silent = true)
        f.research.autoPick()
        val core = f.core

        // Iron and copper: drill → stone furnace → belt → core.
        smeltLine(f, Resource.IRON, core.x - 12, core.y + 6)
        smeltLine(f, Resource.COPPER, core.x + 16, core.y + 8)
        // Coal: drill → belt → coal generator.
        spot(f, Resource.COAL, core.x, core.y - 14)?.let { (x, y) ->
            f.place(BuildingType.MINING_DRILL, x, y, 0)
            f.place(BuildingType.BELT, x + 2, y + 1, 0)
            f.place(BuildingType.BELT, x + 3, y + 1, 0)
            (f.place(BuildingType.COAL_GEN, x + 4, y, 0) as? CoalGenerator)?.coal = 8
        }
        // Gear assembler fed from a chest by an inserter, output belt into the core.
        val ax = core.x + 7; val ay = core.y - 7
        (f.place(BuildingType.CHEST_1, ax - 2, ay + 1, 0) as? Chest)?.let { c -> repeat(300) { c.offer(Item.IRON_PLATE.id, 0) } }
        f.place(BuildingType.INSERTER, ax - 1, ay + 1, 0)
        (f.place(BuildingType.ASSEMBLER_1, ax, ay, 2) as? Crafter)?.applyConfig("gear")
        // Science assembler + lab.
        val sx = core.x - 10; val sy = core.y - 8
        (f.place(BuildingType.ASSEMBLER_1, sx, sy, 0) as? Crafter)?.let { c ->
            c.applyConfig("sci_red")
            repeat(4) { c.offer(Item.COPPER_PLATE.id, 0); c.offer(Item.GEAR.id, 0) }
        }
        f.place(BuildingType.INSERTER, sx + 3, sy + 1, 0)
        (f.place(BuildingType.LAB, sx + 4, sy, 0) as? Lab)?.let { l -> repeat(8) { l.offer(Item.SCI_RED.id, 0) } }
        // Solar field.
        for (k in 0 until 3) f.place(BuildingType.SOLAR, core.x + 8 + k * 2, core.y + 2, 0)
        // Water pump on the pond with a pipe run and a tank.
        pond(f)?.let { (px, py) ->
            f.place(BuildingType.WATER_PUMP, px, py, 0)
            var x = px + 1
            while (f.world.isWater(x, py)) x++
            for (k in 0 until 3) f.place(BuildingType.PIPE_SEG, x + k, py, 0)
            f.place(BuildingType.TANK, x + 3, py, 0)
        }
        // Blanket the area with poles so everything is powered and wired.
        for (y in core.y - 20..core.y + 20 step 5) for (x in core.x - 22..core.x + 24 step 5) {
            if (f.at(x, y) == null && !f.world.isWater(x, y)) f.place(BuildingType.POLE, x, y, 0)
        }
        // Warm up so belts carry items and machines are mid-cycle.
        repeat(60 * 25) { f.tick(1f / 60f) }
    }

    private fun smeltLine(f: Factory, res: Resource, nearX: Int, nearY: Int) {
        val (x, y) = spot(f, res, nearX, nearY) ?: return
        val core = f.core
        val towardCore = if (core.x > x) 0 else 2
        f.place(BuildingType.MINING_DRILL, x, y, towardCore)
        // Furnace directly on the drill's output side.
        val fx = if (towardCore == 0) x + 2 else x - 2
        val furnace = f.place(BuildingType.STONE_FURNACE, fx, y, towardCore) as? Crafter ?: return
        furnace.fuelItems = 5; furnace.fuel = 1e6f
        // Belt from the furnace output to the nearest core edge.
        val outX = furnace.outX; val outY = furnace.outY
        beltTo(f, outX, outY, if (towardCore == 0) core.x - 1 else core.x + core.size, core.y + 1)
    }

    /** Belt along an L-shaped path ending by pointing into the tile beyond (ex, ey). */
    private fun beltTo(f: Factory, sx: Int, sy: Int, ex: Int, ey: Int) {
        var x = sx; var y = sy
        val dirX = if (ex > sx) 0 else 2
        while (x != ex) { f.place(BuildingType.BELT, x, y, dirX); x += (ex - sx).sign }
        val dirY = if (ey > y) 1 else 3
        if (y == ey) { f.place(BuildingType.BELT, x, y, dirX); return }
        while (y != ey) { f.place(BuildingType.BELT, x, y, dirY); y += (ey - sy).sign }
        f.place(BuildingType.BELT, x, y, dirX)
    }

    /** Nearest free 2×2 block of [res] to (x, y). */
    fun spot(f: Factory, res: Resource, x: Int, y: Int): Pair<Int, Int>? {
        for (r in 0..40) for (dy in -r..r) for (dx in -r..r) {
            if (abs(dx) != r && abs(dy) != r) continue
            val px = x + dx; val py = y + dy
            if ((0..1).all { a -> (0..1).all { b -> f.world.resAt(px + a, py + b) == res && f.at(px + a, py + b) == null } }) return px to py
        }
        return null
    }

    private fun pond(f: Factory): Pair<Int, Int>? {
        val cx = World.START_X - 22; val cy = World.START_Y - 12
        for (r in 0..10) for (dy in -r..r) for (dx in -r..r) {
            val x = cx + dx; val y = cy + dy
            if (f.world.isWater(x, y) && !f.world.isWater(x + 1, y)) return x to y
        }
        return null
    }

    /** Fill a block with 240 working assemblers, inserters and belts (performance test). */
    fun stress(f: Factory) {
        for (i in f.stock.indices) f.stock[i] += 100000
        val x0 = World.START_X + 30; val y0 = World.START_Y - 40
        var n = 0
        for (row in 0 until 12) for (col in 0 until 20) {
            val x = x0 + col * 4; val y = y0 + row * 6
            val c = f.place(BuildingType.ASSEMBLER_1, x, y, 1) as? Crafter ?: continue
            c.applyConfig("gear"); n++
            f.place(BuildingType.INSERTER, x + 1, y - 1, 1)
            (f.place(BuildingType.BELT, x + 1, y - 2, 0) as? Belt)?.insertBack(Item.IRON_PLATE.id, 0f)
            if (col % 2 == 0) f.place(BuildingType.POLE, x + 3, y + 1, 0)
        }
        for (row in 0 until 12) for (col in 0 until 80) {
            val b = f.at(x0 + col, y0 + row * 6 - 2)
            if (b == null) f.place(BuildingType.BELT, x0 + col, y0 + row * 6 - 2, 0)
        }
    }
}
