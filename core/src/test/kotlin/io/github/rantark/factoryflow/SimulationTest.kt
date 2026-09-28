package io.github.rantark.factoryflow

import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Item
import io.github.rantark.factoryflow.data.Techs
import io.github.rantark.factoryflow.sim.Belt
import io.github.rantark.factoryflow.sim.Crafter
import io.github.rantark.factoryflow.sim.Factory
import io.github.rantark.factoryflow.sim.SaveGame
import io.github.rantark.factoryflow.sim.Scenario
import io.github.rantark.factoryflow.world.Resource
import io.github.rantark.factoryflow.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Headless tests of the simulation (no graphics needed). */
class SimulationTest {

    private fun run(f: Factory, seconds: Float) {
        repeat((seconds * 60).toInt()) { f.tick(1f / 60f) }
    }

    /** Find a 2x2 block of [res] ore near the start. */
    private fun findOre(w: World, res: Resource): Pair<Int, Int> {
        for (r in 0..60) for (y in World.START_Y - r..World.START_Y + r) for (x in World.START_X - r..World.START_X + r) {
            if ((0..1).all { dy -> (0..1).all { dx -> w.resAt(x + dx, y + dy) == res } }) return x to y
        }
        error("no $res")
    }

    @Test
    fun worldHasStartingDeposits() {
        val w = World(42)
        for (r in listOf(Resource.IRON, Resource.COPPER, Resource.COAL, Resource.STONE)) findOre(w, r)
        assertTrue(w.deposits.size > 50)
        assertTrue("start must be dry land", !w.isWater(World.START_X, World.START_Y))
    }

    @Test
    fun drillFeedsBeltIntoCore() {
        val f = Scenario.newGame(42)
        val (ox, oy) = findOre(f.world, Resource.IRON)
        // Drill facing east, then a belt line east and a pole next to the drill.
        val drill = f.place(BuildingType.MINING_DRILL, ox, oy, 0)
        assertNotNull(drill)
        // Route: from drill output go along y = oy+1 to x = core.x - 1, then turn.
        val core = f.core
        val by = oy + 1
        var x = ox + 2
        val placed = ArrayList<Belt>()
        // Horizontal run towards the core's column, then vertical run into it.
        val targetX = core.x + 1
        val stepX = if (targetX > x) 1 else -1
        // Go vertical first away from the drill if needed: simple L-path.
        while (x != targetX) {
            f.place(BuildingType.BELT, x, by, if (stepX > 0) 0 else 2)?.let { placed.add(it as Belt) }
            x += stepX
        }
        val stepY = if (core.y > by) 1 else -1
        var y = by
        val vdir = if (stepY > 0) 1 else 3
        while (!core.contains(x, y)) {
            f.place(BuildingType.BELT, x, y, vdir)?.let { placed.add(it as Belt) }
            y += stepY
        }
        // Power: a chain of poles from the core to the drill.
        var px = core.x - 1; var py = core.y - 1
        while (Math.abs(px - ox) > 5 || Math.abs(py - oy) > 5) {
            f.place(BuildingType.POLE, px, py, 0)
            px += Integer.signum(ox - px) * 5; py += Integer.signum(oy - py) * 5
            while (f.at(px, py) != null) py += 1
        }
        f.place(BuildingType.POLE, px, py, 0)
        run(f, 5f)
        assertTrue("drill should be powered: ${drill!!.status}", drill.satisfaction > 0.5f)
        val before = f.stock[Item.IRON_ORE.id]
        run(f, 120f)
        val gained = f.stock[Item.IRON_ORE.id] - before
        assertTrue("core should receive ore, got $gained", gained >= 30)
    }

    @Test
    fun stoneFurnaceSmeltsWithCoal() {
        val f = Scenario.newGame(7)
        val fx = World.START_X + 6; val fy = World.START_Y + 6
        val furnace = f.place(BuildingType.STONE_FURNACE, fx, fy, 0) as Crafter
        repeat(3) { assertTrue(furnace.offer(Item.COAL.id, 0)) }
        // The input buffer holds 2x the recipe amount + 2, so a 5th ore is refused.
        repeat(4) { assertTrue(furnace.offer(Item.IRON_ORE.id, 0)) }
        assertTrue(!furnace.offer(Item.IRON_ORE.id, 0))
        // Output goes into a chest in front of the port.
        val chest = f.place(BuildingType.CHEST_1, furnace.outX, furnace.outY, 0)
        assertNotNull(chest)
        run(f, 20f)
        val list = ArrayList<Pair<Item, Int>>()
        chest!!.inventory(list)
        val plates = list.firstOrNull { it.first == Item.IRON_PLATE }?.second ?: 0
        assertEquals(4, plates)
    }

    @Test
    fun beltsMoveItemsAndRespectSpacing() {
        val f = Scenario.newGame(3)
        val y = World.START_Y + 10
        val belts = (0 until 6).map { f.place(BuildingType.BELT, World.START_X + it, y, 0) as Belt }
        repeat(10) { belts[0].insertBack(Item.GEAR.id, 0f); run(f, 0.2f) }
        run(f, 10f)
        // The last belt dead-ends: items queue with at most 4 per tile.
        val total = belts.sumOf { it.count }
        assertEquals(10, total)
        for (b in belts) for (k in 1 until b.count) assertTrue(b.prog[k - 1] - b.prog[k] >= Belt.SPACING - 1e-4f)
        assertEquals(4, belts[5].count)
    }

    @Test
    fun researchUnlocksBuildings() {
        val f = Scenario.newGame(1)
        assertNull(f.placementProblem(BuildingType.BELT, World.START_X + 8, World.START_Y + 8))
        assertEquals("Not researched yet", f.placementProblem(BuildingType.SPLITTER, World.START_X + 8, World.START_Y + 8))
        f.research.complete(Techs["logistics"])
        assertNull(f.placementProblem(BuildingType.SPLITTER, World.START_X + 8, World.START_Y + 8))
    }

    @Test
    fun undoRestoresStock() {
        val f = Scenario.newGame(5)
        val before = f.stock[Item.IRON_PLATE.id]
        val b = f.place(BuildingType.BELT, World.START_X + 9, World.START_Y + 9, 0)!!
        f.undo.record(listOf(io.github.rantark.factoryflow.sim.UndoOp(true, b.type, b.x, b.y, b.dir, null)))
        assertEquals(before - 1, f.stock[Item.IRON_PLATE.id])
        f.undo.undo()
        assertEquals(before, f.stock[Item.IRON_PLATE.id])
        assertNull(f.at(World.START_X + 9, World.START_Y + 9))
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val f = Scenario.newGame(99)
        f.place(BuildingType.BELT, World.START_X + 9, World.START_Y + 9, 1)
        f.place(BuildingType.ASSEMBLER_1, World.START_X + 12, World.START_Y + 12, 0)
        (f.at(World.START_X + 12, World.START_Y + 12) as Crafter).applyConfig("gear")
        f.world.mine(0, 0, 0)
        run(f, 2f)
        val bytes = ByteArrayOutputStream().also { SaveGame.write(f, SaveGame.Camera(1f, 2f, 1.5f), it) }.toByteArray()
        val (g, cam) = SaveGame.read(ByteArrayInputStream(bytes))
        assertEquals(f.buildings.size, g.buildings.size)
        assertEquals(f.stock.toList(), g.stock.toList())
        assertEquals(1.5f, cam.zoom, 0f)
        assertEquals("gear", (g.at(World.START_X + 12, World.START_Y + 12) as Crafter).recipe?.id)
    }

    @Test
    fun twoHundredMachinesStayFast() {
        val f = Scenario.newGame(11)
        var placed = 0
        var y = World.START_Y + 20
        while (placed < 220) {
            for (x in World.START_X - 40 until World.START_X + 40 step 4) {
                if (f.place(BuildingType.ASSEMBLER_1, x, y, 0) != null) placed++
                f.stock[Item.IRON_PLATE.id] += 20; f.stock[Item.GEAR.id] += 10; f.stock[Item.CIRCUIT.id] += 5
            }
            y += 4
        }
        // Plus a long belt carrying items.
        for (x in World.START_X - 60 until World.START_X + 60) {
            f.place(BuildingType.BELT, x, World.START_Y - 20, 0)?.let { (it as Belt).insertBack(Item.GEAR.id, 0f) }
        }
        for (b in f.buildings) if (b is Crafter) b.applyConfig("gear")
        run(f, 2f)
        val t0 = System.nanoTime()
        run(f, 10f)
        val msPerTick = (System.nanoTime() - t0) / 1e6 / 600
        assertTrue("tick too slow: $msPerTick ms", msPerTick < 2.0)
    }
}

/** Tests of the player-facing placement logic (no graphics needed). */
class SessionTest {
    private val platform = object : Platform {
        override fun rasterizeGlyph(ch: Char, px: Int) = GlyphBitmap(1, 1, ByteArray(1), 1f, 0, 0)
        override val audioEnabled = false
    }

    private fun session(): io.github.rantark.factoryflow.game.Session {
        val game = io.github.rantark.factoryflow.game.FactoryGame(platform)
        return io.github.rantark.factoryflow.game.Session(game, Scenario.newGame(21), 1)
    }

    @Test
    fun lineDragBuildsLShapedBeltWithCorner() {
        val s = session()
        s.startPlacing(BuildingType.BELT)
        val sx = World.START_X + 6; val sy = World.START_Y + 6
        s.line = s.planLine(sx, sy, sx + 4, sy + 3)
        s.commitLine()
        // 5 tiles east (incl. corner) + 3 north.
        for (k in 0 until 4) assertEquals(0, s.factory.at(sx + k, sy)!!.dir)
        val corner = s.factory.at(sx + 4, sy) as Belt
        assertEquals(1, corner.dir)
        assertEquals("north-facing corner is fed from the west = its left side", 1, corner.curve)
        for (k in 1..3) assertEquals(1, s.factory.at(sx + 4, sy + k)!!.dir)
        // Undo removes the whole line in one step.
        s.undo()
        assertNull(s.factory.at(sx, sy))
        assertNull(s.factory.at(sx + 4, sy + 3))
    }

    @Test
    fun tapGhostTwiceBuildsAndBulldozeRefunds() {
        val s = session()
        s.startPlacing(BuildingType.CHEST_1)
        val tx = World.START_X + 8; val ty = World.START_Y - 8
        s.tapWorld(tx, ty)          // moves ghost
        assertNull(s.factory.at(tx, ty))
        s.tapWorld(tx, ty)          // confirms
        assertNotNull(s.factory.at(tx, ty))
        val plates = s.factory.stock[Item.IRON_PLATE.id]
        s.cancelPlacing(); s.toggleBulldoze()
        s.tapWorld(tx, ty)
        assertNull(s.factory.at(tx, ty))
        assertEquals(plates + 8, s.factory.stock[Item.IRON_PLATE.id])
    }

    @Test
    fun undergroundPairsEntranceAndExit() {
        val s = session()
        s.factory.research.complete(Techs["logistics"], silent = true)
        val y = World.START_Y + 12
        val a = s.factory.place(BuildingType.UNDERGROUND, World.START_X, y, 0) as io.github.rantark.factoryflow.sim.UndergroundBelt
        val b = s.factory.place(BuildingType.UNDERGROUND, World.START_X + 4, y, 0) as io.github.rantark.factoryflow.sim.UndergroundBelt
        assertTrue(!a.isExit && b.isExit && a.partner === b)
        a.insertBack(Item.GEAR.id, 0f)
        val out = s.factory.place(BuildingType.CHEST_1, World.START_X + 5, y, 0)!!
        repeat(300) { s.factory.tick(1f / 60f) }
        val inv = ArrayList<Pair<Item, Int>>(); out.inventory(inv)
        assertEquals(Item.GEAR, inv.single().first)
    }
}
