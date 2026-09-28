package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Item
import java.io.DataInputStream
import java.io.DataOutputStream

/** Cardinal directions: 0 = east (+x), 1 = north (+y), 2 = west, 3 = south. */
object Dir {
    val DX = intArrayOf(1, 0, -1, 0)
    val DY = intArrayOf(0, 1, 0, -1)
    fun opposite(d: Int) = (d + 2) and 3
    fun left(d: Int) = (d + 1) and 3
    fun right(d: Int) = (d + 3) and 3
    fun angle(d: Int) = d * 90f
}

/** What a building is doing right now – shown in the info panel and as world icons. */
enum class Status(val label: String, val color: Int) {
    WORKING("Working", 0x7BD389),
    IDLE("Idle", 0xB0B0B0),
    NO_POWER("No power", 0xFF5A5F),
    LOW_POWER("Low power", 0xFFC145),
    NO_INPUT("Waiting for ingredients", 0xFFC145),
    OUTPUT_FULL("Output blocked", 0xFF9F1C),
    NO_RECIPE("Choose a recipe", 0x8EC5FF),
    NO_RESOURCE("Nothing to extract here", 0xFF5A5F),
    NO_FUEL("Needs coal", 0xFF9F1C),
    NO_RESEARCH("No research selected", 0x8EC5FF),
}

/**
 * Base class for everything placed on the map. Buildings occupy a [type].size square
 * whose bottom-left tile is ([x], [y]). Items move between buildings with [offer]
 * (push) and [take] (pull, used by inserters).
 */
abstract class Building(val type: BuildingType, val x: Int, val y: Int, var dir: Int) {
    lateinit var f: Factory

    val size get() = type.size
    /** World-space centre. */
    val cx get() = x + size * 0.5f
    val cy get() = y + size * 0.5f

    /** Power network index, or -1 when no pole reaches this building. */
    var powerNet = -1
    /** Fraction (0..1) of requested power the network could deliver this tick. */
    var satisfaction = 0f
    /** kW this building asks for on the next tick. */
    var demand = 0f
    /** True when it did useful work last update – drives animation, glow and audio. */
    var working = false
    /** Free-running animation clock, advanced only while working. */
    var anim = 0f
    var status = Status.IDLE

    fun contains(tx: Int, ty: Int) = tx >= x && ty >= y && tx < x + size && ty < y + size

    /** Tile just outside the middle of the side the building faces (its output port). */
    val outX get() = when (dir) { 0 -> x + size; 2 -> x - 1; else -> x + size / 2 }
    val outY get() = when (dir) { 1 -> y + size; 3 -> y - 1; else -> y + size / 2 }

    open fun update(dt: Float) {}

    /**
     * Try to accept [item] moving in direction [moveDir]. [overflow] is how far past the
     * sender's edge the item already travelled (lets belts hand over smoothly).
     */
    open fun offer(item: Int, moveDir: Int, overflow: Float = 0f): Boolean = false

    /** Would this building accept [item] right now? Used so inserters never grab junk. */
    open fun wants(item: Int): Boolean = false

    /**
     * Remove and return one item that [target] wants (and matches [filter] if >= 0),
     * or -1. Only outputs/storage are takeable – never a machine's ingredients.
     */
    open fun take(target: Building?, filter: Int): Int = -1

    /** Push an item out of the output port into whatever is there. */
    fun pushOut(item: Int): Boolean {
        val t = f.at(outX, outY) ?: return false
        if (t === this) return false
        return t.offer(item, dir, 0f)
    }

    open fun onPlaced() {}
    open fun onRemoved() {}

    /** Return any held items to the core stock (called on bulldoze). */
    open fun dumpContents(stock: IntArray) {}

    /** Items for the info panel. */
    open fun inventory(out: MutableList<Pair<Item, Int>>) {}

    /** Player-chosen configuration (recipe, filter…) as a string, for undo/redo. */
    open fun config(): String? = null
    open fun applyConfig(c: String?) {}

    open fun write(o: DataOutputStream) {}
    open fun read(i: DataInputStream, version: Int) {}

    protected fun addAll(stock: IntArray, inv: IntArray) {
        for (k in inv.indices) stock[k] += inv[k]
    }

    protected fun listInv(inv: IntArray, out: MutableList<Pair<Item, Int>>) {
        for (k in inv.indices) if (inv[k] > 0) out.add(Item.ALL[k] to inv[k])
    }

    protected fun writeInv(o: DataOutputStream, inv: IntArray) {
        var n = 0
        for (v in inv) if (v > 0) n++
        o.writeShort(n)
        for (k in inv.indices) if (inv[k] > 0) { o.writeByte(k); o.writeInt(inv[k]) }
    }

    protected fun readInv(i: DataInputStream, inv: IntArray) {
        val n = i.readShort().toInt()
        repeat(n) {
            val k = i.readByte().toInt() and 0xFF
            val v = i.readInt()
            if (k < inv.size) inv[k] = v
        }
    }
}

/** Anything that feeds the power grid. */
interface PowerSource {
    /** kW this source can deliver this tick. */
    fun available(): Float
    /** Record that [kw] was actually drawn for [dt] seconds. */
    fun draw(kw: Float, dt: Float)
}

/** Anything that connects poles with wires. */
interface PowerPole {
    val wireReach: Float
    val supplyRadius: Int
}
