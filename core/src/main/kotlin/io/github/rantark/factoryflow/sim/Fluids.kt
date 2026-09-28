package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.Fluid
import io.github.rantark.factoryflow.world.World
import kotlin.math.min

/** A connected set of pipes/tanks. Holds one fluid at a time. */
class FluidNet(val id: Int) {
    var fluid = -1
    var amount = 0f
    var capacity = 0f
    val nodes = ArrayList<FluidNode>()
    val fill get() = if (capacity > 0f) amount / capacity else 0f
}

/**
 * Pipe networks are recomputed by flood fill whenever pipes or tanks change. Each
 * network is a single well-mixed volume – simple, robust and cheap enough to scale.
 * A network adopts the first fluid put into it and keeps that fluid even when drained
 * ("sticky"), so a refinery byproduct can never leak into a line that briefly ran dry.
 * The lock resets only when the pipes are rebuilt while completely empty.
 */
class FluidSystem(private val f: Factory) {
    var dirty = true
    val nets = ArrayList<FluidNet>()
    private val netGrid = IntArray(World.W * World.H) { -1 }
    private val adjCache = HashMap<Building, IntArray>()
    private var adjVersion = -1

    fun netAt(x: Int, y: Int) = if (x in 0 until World.W && y in 0 until World.H) netGrid[y * World.W + x] else -1

    /** Write each network's contents back into its nodes (before saving / rebuilding). */
    fun distribute() {
        for (n in nets) for (node in n.nodes) {
            node.fluid = n.fluid
            node.amount = if (n.capacity > 0f) n.amount * node.capacity / n.capacity else 0f
        }
    }

    fun rebuild() {
        distribute()
        dirty = false
        for (n in nets) for (node in n.nodes) for (ty in node.y until node.y + node.size)
            for (tx in node.x until node.x + node.size) netGrid[ty * World.W + tx] = -1
        nets.clear()
        adjCache.clear()
        val seen = HashSet<FluidNode>()
        for (b in f.buildings) {
            if (b !is FluidNode || b in seen) continue
            val net = FluidNet(nets.size)
            nets.add(net)
            val stack = ArrayDeque<FluidNode>()
            stack.add(b); seen.add(b)
            val totals = FloatArray(Fluid.COUNT)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                net.nodes.add(n)
                net.capacity += n.capacity
                if (n.fluid >= 0) totals[n.fluid] += n.amount
                for (ty in n.y until n.y + n.size) for (tx in n.x until n.x + n.size) netGrid[ty * World.W + tx] = net.id
                // Neighbours along the whole perimeter.
                for (k in 0 until n.size) {
                    for (nb in listOf(f.at(n.x - 1, n.y + k), f.at(n.x + n.size, n.y + k), f.at(n.x + k, n.y - 1), f.at(n.x + k, n.y + n.size))) {
                        if (nb is FluidNode && nb !in seen) { seen.add(nb); stack.add(nb) }
                    }
                }
            }
            // Keep the dominant fluid if two networks with different fluids merged.
            var best = -1
            for (k in totals.indices) if (totals[k] > 0.01f && (best < 0 || totals[k] > totals[best])) best = k
            if (best >= 0) { net.fluid = best; net.amount = min(totals[best], net.capacity) }
            else net.fluid = net.nodes.firstOrNull { it.fluid >= 0 && it.amount > 0.01f }?.fluid ?: -1
        }
    }

    /** Networks touching the perimeter of [b]. */
    fun adjacentNets(b: Building): IntArray {
        if (adjVersion != f.version) { adjCache.clear(); adjVersion = f.version }
        return adjCache.getOrPut(b) {
            val s = LinkedHashSet<Int>()
            for (k in 0 until b.size) {
                intArrayOf(netAt(b.x - 1, b.y + k), netAt(b.x + b.size, b.y + k), netAt(b.x + k, b.y - 1), netAt(b.x + k, b.y + b.size))
                    .forEach { if (it >= 0) s.add(it) }
            }
            s.toIntArray()
        }
    }

    /** Put up to [amount] of [fluid] into network [id]; returns what fit. */
    fun insert(id: Int, fluid: Fluid, amount: Float): Float {
        if (id !in nets.indices || amount <= 0f) return 0f
        val n = nets[id]
        if (n.fluid >= 0 && n.fluid != fluid.ordinal) return 0f
        val put = min(amount, n.capacity - n.amount)
        if (put <= 0f) return 0f
        n.fluid = fluid.ordinal
        n.amount += put
        return put
    }

    fun extract(id: Int, fluid: Fluid, amount: Float): Float {
        if (id !in nets.indices || amount <= 0f) return 0f
        val n = nets[id]
        if (n.fluid != fluid.ordinal) return 0f
        val got = min(amount, n.amount)
        n.amount -= got
        if (n.amount <= 0.001f) n.amount = 0f
        return got
    }
}
