package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.Bonus
import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Item
import io.github.rantark.factoryflow.world.Resource
import io.github.rantark.factoryflow.world.World
import kotlin.math.PI
import kotlin.math.sin

/** Things that happened in the simulation that the UI/audio should react to. */
enum class GameEvent { PLACED, REMOVED, RESEARCH_DONE, POWER_LOW, DENIED, UNDO }

/**
 * The whole simulation: the building grid, the core stock, and the subsystems
 * (power, fluids, research, statistics). Runs at a fixed 60 Hz step; distant chunks
 * are simulated at a quarter of the rate (with 4× the time step) to save CPU.
 */
class Factory(val world: World) {
    val grid = arrayOfNulls<Building>(World.W * World.H)
    val buildings = ArrayList<Building>()
    private val chunkLists = Array(World.CHUNKS * World.CHUNKS) { ArrayList<Building>() }

    /** Building materials: everything delivered to the core. */
    val stock = IntArray(Item.COUNT)

    val power = PowerSystem(this)
    val fluids = FluidSystem(this)
    val research = Research(this)
    val stats = Stats(this)
    val undo = UndoHistory(this)

    /** Bumped on every structural change so cached neighbour lookups can refresh. */
    var version = 0
        private set
    var tickCount = 0L
    var simTime = 0.0
    /** Seconds into the day/night cycle. */
    var dayTime = DAY_LENGTH * 0.3f

    lateinit var core: Core

    /** Tile rectangle that is on (or near) screen – simulated at full rate. */
    var activeX0 = 0; var activeY0 = 0; var activeX1 = World.W; var activeY1 = World.H

    val events = ArrayList<Pair<GameEvent, String>>()

    // ---- Research-driven modifiers --------------------------------------------------
    val beltSpeed get() = BELT_BASE * (1f + research.bonus(Bonus.BELT_SPEED))
    val miningBonus get() = 1f + research.bonus(Bonus.MINING_SPEED)
    val labBonus get() = 1f + research.bonus(Bonus.LAB_SPEED)
    val solarBonus get() = 1f + research.bonus(Bonus.SOLAR_OUTPUT)
    val inserterBonus get() = 1f + research.bonus(Bonus.INSERTER_SPEED)
    val craftBonus get() = 1f + research.bonus(Bonus.CRAFT_SPEED)

    /** 0 at night … 1 at noon, with soft dawn/dusk. */
    var daylight = 1f
        private set

    fun at(x: Int, y: Int): Building? =
        if (x < 0 || y < 0 || x >= World.W || y >= World.H) null else grid[y * World.W + x]

    // ---------------------------------------------------------------------------------
    // Simulation step
    // ---------------------------------------------------------------------------------

    fun tick(dt: Float) {
        tickCount++
        simTime += dt
        dayTime = (dayTime + dt) % DAY_LENGTH
        daylight = computeDaylight(dayTime / DAY_LENGTH)
        if (fluids.dirty) fluids.rebuild()
        if (power.dirty) power.rebuild()
        power.update(dt)

        val cx0 = (activeX0 / World.CHUNK) - 1; val cy0 = (activeY0 / World.CHUNK) - 1
        val cx1 = (activeX1 / World.CHUNK) + 1; val cy1 = (activeY1 / World.CHUNK) + 1
        for (ci in chunkLists.indices) {
            val list = chunkLists[ci]
            if (list.isEmpty()) continue
            val cx = ci % World.CHUNKS; val cy = ci / World.CHUNKS
            if (cx in cx0..cx1 && cy in cy0..cy1) {
                for (k in list.indices) list[k].update(dt)
            } else if ((tickCount + ci) % LOD_STEP == 0L) {
                val ldt = dt * LOD_STEP
                for (k in list.indices) list[k].update(ldt)
            }
        }
        stats.update(dt)
    }

    // ---------------------------------------------------------------------------------
    // Building placement
    // ---------------------------------------------------------------------------------

    /** Bottom-left tile for a building of [type] centred on the tapped tile. */
    fun anchorX(type: BuildingType, tx: Int) = tx - (type.size - 1) / 2
    fun anchorY(type: BuildingType, ty: Int) = ty - (type.size - 1) / 2

    /** Returns null if [type] can be placed with its bottom-left at (x, y), else a reason. */
    fun placementProblem(type: BuildingType, x: Int, y: Int, checkCost: Boolean = true): String? {
        if (!research.buildingUnlocked(type)) return "Not researched yet"
        var oil = false; var ore = false; var allWater = true
        for (ty in y until y + type.size) for (tx in x until x + type.size) {
            if (!world.inBounds(tx, ty)) return "Out of bounds"
            if (grid[ty * World.W + tx] != null) return "Blocked"
            val wtr = world.isWater(tx, ty)
            if (!wtr) allWater = false
            if (wtr && type != BuildingType.WATER_PUMP) return "Can't build on water"
            val r = world.resAt(tx, ty)
            if (r == Resource.OIL) oil = true
            if (r.solid) ore = true
        }
        if (type == BuildingType.WATER_PUMP && !allWater) return "Place on water"
        if (type == BuildingType.PUMP_JACK && !oil) return "Needs an oil seep"
        if (type == BuildingType.MINING_DRILL && !ore) return "No ore here"
        if (checkCost) {
            for (s in type.cost) if (stock[s.item.id] < s.count) return "Need ${s.count} ${s.item.title}"
        }
        return null
    }

    fun place(type: BuildingType, x: Int, y: Int, dir: Int, pay: Boolean = true): Building? {
        if (placementProblem(type, x, y, pay) != null) return null
        if (pay) for (s in type.cost) stock[s.item.id] -= s.count
        val b = create(type, x, y, if (type.rotatable) dir else 0)
        insert(b)
        return b
    }

    /** Add an already-constructed building (used by placement and save loading). */
    fun insert(b: Building) {
        b.f = this
        for (ty in b.y until b.y + b.size) for (tx in b.x until b.x + b.size) grid[ty * World.W + tx] = b
        buildings.add(b)
        chunkLists[(b.y / World.CHUNK) * World.CHUNKS + b.x / World.CHUNK].add(b)
        if (b is Core) core = b
        version++
        b.onPlaced()
        structureChanged(b)
    }

    /** True while a save is being loaded (buildings restore their own pairing state). */
    var loading = false
        private set

    fun insertLoaded(b: Building) {
        loading = true
        try { insert(b) } finally { loading = false }
    }

    fun remove(b: Building, refund: Boolean = true) {
        if (b is Core) return
        for (ty in b.y until b.y + b.size) for (tx in b.x until b.x + b.size) grid[ty * World.W + tx] = null
        buildings.remove(b)
        chunkLists[(b.y / World.CHUNK) * World.CHUNKS + b.x / World.CHUNK].remove(b)
        if (refund) {
            for (s in b.type.cost) stock[s.item.id] += s.count
            b.dumpContents(stock)
        }
        version++
        b.onRemoved()
        structureChanged(b)
    }

    private fun structureChanged(b: Building) {
        if (b.type.power > 0f || b is PowerPole || b is PowerSource || b is Accumulator) power.dirty = true
        if (b is FluidNode || b is Pump || b is Crafter) fluids.dirty = true
        // Belt curves depend on neighbours.
        for (d in -1..3) {
            val nx = if (d < 0) b.x else b.x + Dir.DX[d]
            val ny = if (d < 0) b.y else b.y + Dir.DY[d]
            (at(nx, ny) as? Belt)?.updateCurve()
        }
    }

    fun rotate(b: Building) {
        if (!b.type.rotatable) return
        b.dir = (b.dir + 3) and 3 // clockwise
        version++
        structureChanged(b)
    }

    fun emit(e: GameEvent, msg: String = "") { events.add(e to msg) }

    companion object {
        const val DAY_LENGTH = 1200f   // 20-minute day/night cycle
        const val BELT_BASE = 1.875f   // tiles per second
        const val LOD_STEP = 4

        fun create(type: BuildingType, x: Int, y: Int, dir: Int): Building = when (type) {
            BuildingType.BELT -> Belt(type, x, y, dir)
            BuildingType.UNDERGROUND -> UndergroundBelt(x, y, dir)
            BuildingType.SPLITTER -> Splitter(x, y, dir)
            BuildingType.MERGER -> Merger(x, y, dir)
            BuildingType.INSERTER, BuildingType.LONG_INSERTER, BuildingType.FAST_INSERTER,
            BuildingType.FILTER_INSERTER -> Inserter(type, x, y, dir)
            BuildingType.PIPE_SEG -> Pipe(x, y)
            BuildingType.TANK -> Tank(x, y)
            BuildingType.MINING_DRILL -> Drill(x, y, dir)
            BuildingType.WATER_PUMP -> WaterPump(x, y)
            BuildingType.PUMP_JACK -> PumpJack(x, y)
            BuildingType.POLE, BuildingType.BIG_POLE -> Pole(type, x, y)
            BuildingType.COAL_GEN -> CoalGenerator(x, y)
            BuildingType.SOLAR -> SolarPanel(x, y)
            BuildingType.ACCUMULATOR -> Accumulator(x, y)
            BuildingType.STONE_FURNACE, BuildingType.ELECTRIC_FURNACE, BuildingType.ASSEMBLER_1,
            BuildingType.ASSEMBLER_2, BuildingType.ASSEMBLER_3, BuildingType.CHEM_PLANT,
            BuildingType.REFINERY, BuildingType.ELECTRONICS -> Crafter(type, x, y, dir)
            BuildingType.CHEST_1, BuildingType.CHEST_2, BuildingType.CHEST_3 -> Chest(type, x, y)
            BuildingType.LAB -> Lab(x, y)
            BuildingType.CORE -> Core(x, y)
        }

        /** Smooth daylight curve: long day, short twilight, dark night. */
        fun computeDaylight(t: Float): Float {
            // t = 0 midnight, 0.25 dawn, 0.5 noon, 0.75 dusk.
            val s = sin((t - 0.25f) * 2f * PI.toFloat())
            return ((s + 0.35f) / 0.5f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        }
    }
}
