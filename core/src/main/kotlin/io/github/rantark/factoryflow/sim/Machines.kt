package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Fluid
import io.github.rantark.factoryflow.data.Item
import io.github.rantark.factoryflow.data.MachineClass
import io.github.rantark.factoryflow.data.Recipe
import io.github.rantark.factoryflow.data.Recipes
import io.github.rantark.factoryflow.world.Resource
import java.io.DataInputStream
import java.io.DataOutputStream

/** Energy content of one coal, in kJ. */
const val COAL_KJ = 4000f

// =====================================================================================
// Extraction
// =====================================================================================

/** 2×2 electric drill: mines the ore tiles underneath and pushes it out of its port. */
class Drill(x: Int, y: Int, dir: Int) : Building(BuildingType.MINING_DRILL, x, y, dir) {
    var out = -1
    private var timer = 0f
    private var rr = 0
    private var scan = 0f
    var oreLeft = 0L
        private set
    var mainRes = Resource.NONE
        private set

    override fun onPlaced() = rescan()

    private fun rescan() {
        oreLeft = 0; mainRes = Resource.NONE
        for (ty in y until y + size) for (tx in x until x + size) {
            val r = f.world.resAt(tx, ty)
            if (r.solid) { oreLeft += f.world.amount[f.world.idx(tx, ty)]; mainRes = r }
        }
    }

    override fun update(dt: Float) {
        working = false
        if (out >= 0 && pushOut(out)) out = -1
        scan -= dt
        if (scan <= 0f) { scan = 1f; rescan() }
        if (oreLeft <= 0L) { status = Status.NO_RESOURCE; demand = 0f; return }
        if (out >= 0) { status = Status.OUTPUT_FULL; demand = 0f; return }
        demand = type.power
        if (satisfaction <= 0.01f) { status = Status.NO_POWER; return }
        status = if (satisfaction < 0.6f) Status.LOW_POWER else Status.WORKING
        val rate = BASE_RATE * f.miningBonus * satisfaction * (if (mainRes == Resource.CRYSTAL) 0.5f else 1f)
        timer += dt * rate
        working = true
        anim += dt * satisfaction
        if (timer >= 1f) {
            timer -= 1f
            for (k in 0 until size * size) {
                val i = (rr + k) % (size * size)
                val tx = x + i % size; val ty = y + i / size
                val r = f.world.resAt(tx, ty)
                if (!r.solid) continue
                if (f.world.mine(tx, ty, 1) > 0) {
                    out = r.item!!.id
                    oreLeft--
                    f.stats.produced(out, 1)
                    rr = i + 1
                    break
                }
            }
        }
    }

    override fun take(target: Building?, filter: Int): Int {
        val o = out
        if (o < 0 || (filter >= 0 && o != filter) || (target != null && !target.wants(o))) return -1
        out = -1
        return o
    }

    override fun dumpContents(stock: IntArray) { if (out >= 0) stock[out]++; out = -1 }
    override fun inventory(outList: MutableList<Pair<Item, Int>>) { if (out >= 0) outList.add(Item.ALL[out] to 1) }
    override fun write(o: DataOutputStream) { o.writeByte(out); o.writeFloat(timer) }
    override fun read(i: DataInputStream, version: Int) { out = i.readByte().toInt(); timer = i.readFloat() }

    companion object { const val BASE_RATE = 0.5f }
}

/** Base for pumps that push fluid into adjacent pipes. */
abstract class Pump(type: BuildingType, x: Int, y: Int, dir: Int) : Building(type, x, y, dir) {
    var rateNow = 0f

    protected fun pumpInto(fluid: Fluid, amount: Float): Float {
        var left = amount
        for (net in f.fluids.adjacentNets(this)) {
            if (left <= 0f) break
            left -= f.fluids.insert(net, fluid, left)
        }
        return amount - left
    }
}

/** Placed on water; pumps an endless supply into neighbouring pipes. */
class WaterPump(x: Int, y: Int) : Pump(BuildingType.WATER_PUMP, x, y, 0) {
    override fun update(dt: Float) {
        demand = type.power
        working = false
        if (satisfaction <= 0.01f) { status = Status.NO_POWER; rateNow = 0f; return }
        val moved = pumpInto(Fluid.WATER, RATE * satisfaction * dt)
        rateNow = moved / dt
        working = moved > 0f
        status = if (working) Status.WORKING else Status.OUTPUT_FULL
        if (working) anim += dt
    }
    companion object { const val RATE = 30f }
}

/** 2×2 oil extractor. Seeps slowly weaken but never run completely dry. */
class PumpJack(x: Int, y: Int) : Pump(BuildingType.PUMP_JACK, x, y, 0) {
    val yieldFactor: Float
        get() {
            var y0 = 0f
            for (ty in y until y + 2) for (tx in x until x + 2) {
                if (f.world.resAt(tx, ty) == Resource.OIL) {
                    val a = f.world.amount[f.world.idx(tx, ty)]
                    y0 += 0.2f + 0.8f * (a / 20000f).coerceAtMost(1f)
                }
            }
            return y0
        }

    override fun update(dt: Float) {
        working = false
        val yf = yieldFactor
        if (yf <= 0f) { status = Status.NO_RESOURCE; demand = 0f; return }
        demand = type.power
        if (satisfaction <= 0.01f) { status = Status.NO_POWER; rateNow = 0f; return }
        val want = RATE * yf * satisfaction * dt
        val moved = pumpInto(Fluid.CRUDE_OIL, want)
        rateNow = moved / dt
        if (moved > 0f) {
            working = true; anim += dt * satisfaction
            f.stats.producedFluid(Fluid.CRUDE_OIL.ordinal, moved)
            // Deplete the seep a little (never below a trickle).
            for (ty in y until y + 2) for (tx in x until x + 2) {
                if (f.world.resAt(tx, ty) == Resource.OIL && f.world.amount[f.world.idx(tx, ty)] > 2000) {
                    f.world.mine(tx, ty, 1 + (moved * 0.05f).toInt())
                }
            }
        }
        status = if (working) Status.WORKING else Status.OUTPUT_FULL
    }
    companion object { const val RATE = 6f }
}

// =====================================================================================
// Crafting machines
// =====================================================================================

/**
 * Furnaces, assemblers, chemical plants, refineries and electronics factories.
 * Ingredients arrive from belts pointing into any side or from inserters; products
 * are pushed out of the output port (arrow) and can also be taken by inserters.
 * Fluids are exchanged with any adjacent pipe network.
 */
class Crafter(type: BuildingType, x: Int, y: Int, dir: Int) : Building(type, x, y, dir) {
    val mclass: MachineClass = when (type) {
        BuildingType.STONE_FURNACE, BuildingType.ELECTRIC_FURNACE -> MachineClass.SMELTER
        BuildingType.CHEM_PLANT -> MachineClass.CHEMICAL
        BuildingType.REFINERY -> MachineClass.REFINERY
        BuildingType.ELECTRONICS -> MachineClass.ELECTRONICS
        else -> MachineClass.ASSEMBLER
    }
    val baseSpeed = when (type) {
        BuildingType.STONE_FURNACE -> 1f
        BuildingType.ELECTRIC_FURNACE -> 2f
        BuildingType.ASSEMBLER_1 -> 0.5f
        BuildingType.ASSEMBLER_2 -> 0.75f
        BuildingType.ASSEMBLER_3 -> 1.25f
        BuildingType.ELECTRONICS -> 1.5f
        else -> 1f
    }
    val usesFuel = type == BuildingType.STONE_FURNACE

    var recipe: Recipe? = null
    /** Furnaces choose their recipe from the first ore they receive unless locked. */
    var autoRecipe = mclass == MachineClass.SMELTER
    val inv = IntArray(Item.COUNT)
    val fin = FloatArray(Fluid.COUNT)
    val out = IntArray(Item.COUNT)
    val fout = FloatArray(Fluid.COUNT)
    var progress = 0f
    var crafting = false
    var fuelItems = 0
    var fuel = 0f
    private var pushTimer = 0f
    /** Crafts completed – for the production-rate readout. */
    var crafts = 0

    val speed get() = baseSpeed * f.craftBonus

    fun canUse(r: Recipe) = mclass in r.machines && f.research.recipeUnlocked(r)

    fun setRecipe(r: Recipe?, lock: Boolean = true) {
        if (r === recipe) { autoRecipe = autoRecipe && !lock; return }
        // Return unused ingredients to the core so nothing is lost when switching.
        for (k in inv.indices) { f.stock[k] += inv[k]; inv[k] = 0 }
        for (k in fin.indices) fin[k] = 0f
        recipe = r; progress = 0f; crafting = false
        if (lock) autoRecipe = false
    }

    private fun activeRecipeFor(item: Int): Recipe? {
        val r = recipe
        if (r != null && r.needs(Item.ALL[item]) > 0) return r
        if (autoRecipe && !crafting && inv.all { it == 0 }) {
            return Recipes.smeltingFor(Item.ALL[item]) { f.research.recipeUnlocked(it) }
        }
        return null
    }

    override fun wants(item: Int): Boolean {
        if (usesFuel && item == Item.COAL.id && fuelItems < 5) return true
        val r = activeRecipeFor(item) ?: return false
        val need = r.needs(Item.ALL[item])
        return inv[item] < need * 2 + 2
    }

    override fun offer(item: Int, moveDir: Int, overflow: Float): Boolean {
        if (usesFuel && item == Item.COAL.id) {
            val asIngredient = (recipe?.needs(Item.COAL) ?: 0) > 0 && inv[item] < recipe!!.needs(Item.COAL) * 2 + 2
            if (fuelItems < 2 || (!asIngredient && fuelItems < 5)) { fuelItems++; return true }
            if (!asIngredient) return false
        }
        val r = activeRecipeFor(item) ?: return false
        if (inv[item] >= r.needs(Item.ALL[item]) * 2 + 2) return false
        if (r !== recipe) { recipe = r; progress = 0f }
        inv[item]++
        return true
    }

    override fun take(target: Building?, filter: Int): Int {
        for (k in out.indices) {
            if (out[k] <= 0) continue
            if (filter >= 0 && k != filter) continue
            if (target != null && !target.wants(k)) continue
            out[k]--
            return k
        }
        return -1
    }

    private fun hasInputs(r: Recipe): Boolean {
        for (s in r.inputs) if (inv[s.item.id] < s.count) return false
        for (s in r.fluidIn) if (fin[s.fluid.ordinal] < s.amount - 0.01f) return false
        return true
    }

    private fun hasRoom(r: Recipe): Boolean {
        for (s in r.outputs) if (out[s.item.id] + s.count > maxOf(s.count * 3, 10)) return false
        return true
    }

    override fun update(dt: Float) {
        working = false
        val r = recipe
        if (r == null) { status = Status.NO_RECIPE; demand = 0f; pushOutputs(dt); return }
        pullFluids(r)
        if (!crafting) {
            if (!hasRoom(r)) { status = Status.OUTPUT_FULL; demand = 0f; pushOutputs(dt); return }
            if (!hasInputs(r)) { status = Status.NO_INPUT; demand = 0f; pushOutputs(dt); return }
            for (s in r.inputs) { inv[s.item.id] -= s.count; f.stats.consumed(s.item.id, s.count) }
            for (s in r.fluidIn) { fin[s.fluid.ordinal] -= s.amount; f.stats.consumedFluid(s.fluid.ordinal, s.amount) }
            crafting = true; progress = 0f
        }
        val pf: Float
        if (usesFuel) {
            demand = 0f
            if (fuel <= 0f && fuelItems > 0) { fuelItems--; fuel += COAL_KJ; f.stats.consumed(Item.COAL.id, 1) }
            pf = if (fuel > 0f) 1f else 0f
            if (pf == 0f) status = Status.NO_FUEL
        } else {
            demand = type.power
            pf = satisfaction
            if (pf <= 0.01f) status = Status.NO_POWER
        }
        if (pf > 0.01f) {
            status = if (pf < 0.6f) Status.LOW_POWER else Status.WORKING
            progress += dt * speed * pf / r.time
            if (usesFuel) fuel -= FURNACE_KW * dt
            working = true
            anim += dt * pf
            if (progress >= 1f) {
                progress = 0f; crafting = false; crafts++
                for (s in r.outputs) { out[s.item.id] += s.count; f.stats.produced(s.item.id, s.count) }
                for (s in r.fluidOut) { fout[s.fluid.ordinal] += s.amount; f.stats.producedFluid(s.fluid.ordinal, s.amount) }
            }
        }
        pushOutputs(dt)
    }

    /** Draw needed fluids from neighbouring pipe networks. */
    private fun pullFluids(r: Recipe) {
        if (r.fluidIn.isEmpty()) return
        for (s in r.fluidIn) {
            val k = s.fluid.ordinal
            val want = s.amount * 2f - fin[k]
            if (want <= 0f) continue
            var got = 0f
            for (net in f.fluids.adjacentNets(this)) {
                got += f.fluids.extract(net, s.fluid, want - got)
                if (got >= want) break
            }
            fin[k] += got
        }
    }

    private fun pushOutputs(dt: Float) {
        // Fluids: into matching (or empty) neighbouring networks; vent any backlog so
        // byproducts never deadlock the machine.
        for (k in fout.indices) {
            if (fout[k] <= 0f) continue
            val fl = Fluid.ALL[k]
            val nets = f.fluids.adjacentNets(this)
            // Pass 1: networks already carrying this fluid. Pass 2: unclaimed pipes.
            for (pass in 0..1) for (net in nets) {
                if (fout[k] <= 0f) break
                val nf = f.fluids.nets[net].fluid
                if ((pass == 0) != (nf == k)) continue
                if (pass == 1 && nf >= 0) continue
                fout[k] -= f.fluids.insert(net, fl, fout[k])
            }
            if (fout[k] > 400f) fout[k] = 400f
        }
        pushTimer -= dt
        if (pushTimer > 0f) return
        pushTimer = 0.08f
        for (k in out.indices) {
            if (out[k] > 0 && pushOut(k)) { out[k]--; return }
        }
    }

    override fun dumpContents(stock: IntArray) {
        addAll(stock, inv); addAll(stock, out)
        stock[Item.COAL.id] += fuelItems
        if (crafting) recipe?.inputs?.forEach { stock[it.item.id] += it.count }
        inv.fill(0); out.fill(0); fuelItems = 0; crafting = false
    }

    override fun inventory(out: MutableList<Pair<Item, Int>>) {
        listInv(inv, out)
        listInv(this.out, out)
        if (fuelItems > 0) out.add(Item.COAL to fuelItems)
    }

    override fun config() = recipe?.id?.let { if (autoRecipe) "auto" else it }
    override fun applyConfig(c: String?) {
        val r = Recipes.find(c)
        if (r != null && canUse(r)) setRecipe(r)
    }

    override fun write(o: DataOutputStream) {
        o.writeUTF(recipe?.id ?: "")
        o.writeBoolean(autoRecipe)
        o.writeFloat(progress); o.writeBoolean(crafting)
        o.writeByte(fuelItems); o.writeFloat(fuel)
        writeInv(o, inv); writeInv(o, out)
        for (v in fin) o.writeFloat(v)
        for (v in fout) o.writeFloat(v)
    }

    override fun read(i: DataInputStream, version: Int) {
        recipe = Recipes.find(i.readUTF().ifEmpty { null })
        autoRecipe = i.readBoolean()
        progress = i.readFloat(); crafting = i.readBoolean()
        fuelItems = i.readByte().toInt(); fuel = i.readFloat()
        readInv(i, inv); readInv(i, out)
        for (k in fin.indices) fin[k] = i.readFloat()
        for (k in fout.indices) fout[k] = i.readFloat()
    }

    companion object { const val FURNACE_KW = 90f }
}

// =====================================================================================
// Research
// =====================================================================================

/** Consumes one of each science pack per unit of the current research. */
class Lab(x: Int, y: Int) : Building(BuildingType.LAB, x, y, 0) {
    val packs = IntArray(Item.COUNT)
    var unitProgress = 0f
    var busyTech = -1

    override fun wants(item: Int) = Item.ALL[item].isScience && packs[item] < 10

    override fun offer(item: Int, moveDir: Int, overflow: Float): Boolean {
        if (!wants(item)) return false
        packs[item]++
        return true
    }

    override fun update(dt: Float) {
        working = false
        val res = f.research
        if (busyTech < 0) {
            val t = res.current
            if (t == null) { status = Status.NO_RESEARCH; demand = 0f; return }
            if (t.packs.any { packs[it.id] <= 0 }) { status = Status.NO_INPUT; demand = 0f; return }
            for (p in t.packs) { packs[p.id]--; f.stats.consumed(p.id, 1) }
            busyTech = t.index; unitProgress = 0f
        }
        demand = type.power
        if (satisfaction <= 0.01f) { status = Status.NO_POWER; return }
        status = Status.WORKING
        val tech = io.github.rantark.factoryflow.data.Techs.ALL[busyTech]
        unitProgress += dt * f.labBonus * satisfaction / tech.unitTime
        working = true
        anim += dt * satisfaction
        if (unitProgress >= 1f) {
            res.addUnit(tech)
            busyTech = -1; unitProgress = 0f
        }
    }

    override fun dumpContents(stock: IntArray) { addAll(stock, packs); packs.fill(0) }
    override fun inventory(out: MutableList<Pair<Item, Int>>) = listInv(packs, out)
    override fun write(o: DataOutputStream) { writeInv(o, packs); o.writeShort(busyTech); o.writeFloat(unitProgress) }
    override fun read(i: DataInputStream, version: Int) { readInv(i, packs); busyTech = i.readShort().toInt(); unitProgress = i.readFloat() }
}

// =====================================================================================
// Storage
// =====================================================================================

class Chest(type: BuildingType, x: Int, y: Int) : Building(type, x, y, 0) {
    val inv = IntArray(Item.COUNT)
    var total = 0
    val capacity = when (type) { BuildingType.CHEST_1 -> 400; BuildingType.CHEST_2 -> 1200; else -> 6000 }

    override fun wants(item: Int) = total < capacity
    override fun offer(item: Int, moveDir: Int, overflow: Float): Boolean {
        if (total >= capacity) return false
        inv[item]++; total++
        return true
    }

    override fun take(target: Building?, filter: Int): Int {
        if (total == 0) return -1
        for (k in inv.indices) {
            if (inv[k] <= 0 || (filter >= 0 && k != filter)) continue
            if (target != null && !target.wants(k)) continue
            inv[k]--; total--
            return k
        }
        return -1
    }

    override fun update(dt: Float) { working = false; status = if (total >= capacity) Status.OUTPUT_FULL else Status.IDLE }
    override fun dumpContents(stock: IntArray) { addAll(stock, inv); inv.fill(0); total = 0 }
    override fun inventory(out: MutableList<Pair<Item, Int>>) = listInv(inv, out)
    override fun write(o: DataOutputStream) = writeInv(o, inv)
    override fun read(i: DataInputStream, version: Int) { readInv(i, inv); total = inv.sum() }
}

/** Pipes and tanks are nodes of fluid networks; each keeps its share for saving. */
open class FluidNode(type: BuildingType, x: Int, y: Int, val capacity: Float) : Building(type, x, y, 0) {
    var fluid = -1
    var amount = 0f
    override fun write(o: DataOutputStream) { o.writeByte(fluid); o.writeFloat(amount) }
    override fun read(i: DataInputStream, version: Int) { fluid = i.readByte().toInt(); amount = i.readFloat() }
    override fun onPlaced() { f.fluids.dirty = true }
    override fun onRemoved() { f.fluids.dirty = true }
}

class Pipe(x: Int, y: Int) : FluidNode(BuildingType.PIPE_SEG, x, y, 100f)
class Tank(x: Int, y: Int) : FluidNode(BuildingType.TANK, x, y, 2500f)

// =====================================================================================
// Power
// =====================================================================================

class Pole(type: BuildingType, x: Int, y: Int) : Building(type, x, y, 0), PowerPole {
    override val wireReach = if (type == BuildingType.BIG_POLE) 18f else 8f
    override val supplyRadius = if (type == BuildingType.BIG_POLE) 1 else 2
    override fun update(dt: Float) { working = powerNet >= 0 && f.power.netSatisfaction(powerNet) > 0f }
}

class CoalGenerator(x: Int, y: Int) : Building(BuildingType.COAL_GEN, x, y, 0), PowerSource {
    var coal = 0
    var energy = 0f
    var output = 0f

    override fun wants(item: Int) = item == Item.COAL.id && coal < 10
    override fun offer(item: Int, moveDir: Int, overflow: Float): Boolean {
        if (!wants(item)) return false
        coal++; return true
    }

    override fun available() = if (energy > 0f || coal > 0) MAX_KW else 0f

    override fun draw(kw: Float, dt: Float) {
        output = kw
        energy -= kw * dt
        while (energy < 0f && coal > 0) { coal--; energy += COAL_KJ; f.stats.consumed(Item.COAL.id, 1) }
        if (energy < 0f) energy = 0f
    }

    override fun update(dt: Float) {
        working = output > 1f
        if (working) anim += dt * (0.3f + output / MAX_KW)
        status = when {
            coal == 0 && energy <= 0f -> Status.NO_FUEL
            working -> Status.WORKING
            else -> Status.IDLE
        }
    }

    override fun dumpContents(stock: IntArray) { stock[Item.COAL.id] += coal; coal = 0 }
    override fun inventory(out: MutableList<Pair<Item, Int>>) { if (coal > 0) out.add(Item.COAL to coal) }
    override fun write(o: DataOutputStream) { o.writeByte(coal); o.writeFloat(energy) }
    override fun read(i: DataInputStream, version: Int) { coal = i.readByte().toInt(); energy = i.readFloat() }

    companion object { const val MAX_KW = 900f }
}

class SolarPanel(x: Int, y: Int) : Building(BuildingType.SOLAR, x, y, 0), PowerSource {
    var output = 0f
    override fun available() = MAX_KW * f.daylight * f.solarBonus
    override fun draw(kw: Float, dt: Float) { output = kw }
    override fun update(dt: Float) {
        working = f.daylight > 0.05f
        status = if (working) Status.WORKING else Status.IDLE
    }
    companion object { const val MAX_KW = 60f }
}

class Accumulator(x: Int, y: Int) : Building(BuildingType.ACCUMULATOR, x, y, 0) {
    var stored = 0f
    /** + charging, − discharging (kW) during the last tick. */
    var flow = 0f
    override fun update(dt: Float) {
        working = flow < -1f
        anim += dt
        status = if (stored > 1f || flow > 0f) Status.WORKING else Status.IDLE
    }
    override fun write(o: DataOutputStream) { o.writeFloat(stored) }
    override fun read(i: DataInputStream, version: Int) { stored = i.readFloat() }
    companion object { const val CAPACITY_KJ = 5000f; const val RATE_KW = 300f }
}

/**
 * The Factory Core: an unlimited sink for items (which become building stock), a
 * small built-in power plant, and a power pole all in one.
 */
class Core(x: Int, y: Int) : Building(BuildingType.CORE, x, y, 0), PowerSource, PowerPole {
    override val wireReach = 10f
    override val supplyRadius = 4
    var output = 0f

    override fun wants(item: Int) = true
    override fun offer(item: Int, moveDir: Int, overflow: Float): Boolean {
        f.stock[item]++
        f.stats.delivered(item)
        return true
    }
    override fun available() = MAX_KW
    override fun draw(kw: Float, dt: Float) { output = kw }
    override fun update(dt: Float) { working = true; anim += dt; status = Status.WORKING }
    companion object { const val MAX_KW = 400f }
}
