package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.world.World
import kotlin.math.hypot
import kotlin.math.min

/** One connected electrical grid. */
class PowerNet(val id: Int) {
    val consumers = ArrayList<Building>()
    val sources = ArrayList<PowerSource>()
    val accumulators = ArrayList<Accumulator>()
    /** kW requested / generated / satisfaction for the last tick. */
    var demand = 0f
    var generated = 0f
    var satisfaction = 1f
    private var wasOk = true
    var lowSince = 0f

    fun checkFailure(f: Factory, dt: Float) {
        val ok = satisfaction > 0.6f || demand < 1f
        if (!ok) lowSince += dt else lowSince = 0f
        if (wasOk && !ok) f.emit(GameEvent.POWER_LOW, "Power shortage – build more generators")
        wasOk = ok
    }
}

/**
 * Builds electrical networks from poles (auto-wiring each pole to its nearest
 * neighbours in reach) and balances supply against demand every tick, charging or
 * draining accumulators with the difference.
 */
class PowerSystem(private val f: Factory) {
    var dirty = true
    val nets = ArrayList<PowerNet>()
    /** Pairs of connected poles – drawn as sagging wires. */
    val wires = ArrayList<Pair<Building, Building>>()
    private val supply = IntArray(World.W * World.H)

    fun netSatisfaction(id: Int) = if (id in nets.indices) nets[id].satisfaction else 0f

    /** Which network (if any) powers tile (x, y)? Used by the placement preview. */
    fun netAt(x: Int, y: Int) = if (x in 0 until World.W && y in 0 until World.H) supply[y * World.W + x] else -1

    fun rebuild() {
        dirty = false
        nets.clear(); wires.clear()
        val poles = f.buildings.filter { it is PowerPole }
        // Union-find over poles.
        val parent = IntArray(poles.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in poles.indices) {
            val a = poles[i]; val pa = a as PowerPole
            // Connect to up to 4 nearest poles within reach.
            val near = ArrayList<Pair<Int, Float>>()
            for (j in poles.indices) {
                if (j == i) continue
                val b = poles[j]
                val d = hypot(a.cx - b.cx, a.cy - b.cy)
                if (d <= min(pa.wireReach, (b as PowerPole).wireReach)) near.add(j to d)
            }
            near.sortBy { it.second }
            for ((j, _) in near.take(4)) {
                val ri = find(i); val rj = find(j)
                if (j > i || ri != rj) {
                    if (wires.none { (p, q) -> (p === a && q === poles[j]) || (q === a && p === poles[j]) }) wires.add(a to poles[j])
                }
                if (ri != rj) parent[ri] = rj
            }
        }
        val netOfRoot = HashMap<Int, Int>()
        supply.fill(-1)
        for (i in poles.indices) {
            val root = find(i)
            val id = netOfRoot.getOrPut(root) { nets.add(PowerNet(nets.size)); nets.size - 1 }
            val p = poles[i]
            p.powerNet = id
            val r = (p as PowerPole).supplyRadius
            for (ty in p.y - r until p.y + p.size + r) for (tx in p.x - r until p.x + p.size + r) {
                if (tx in 0 until World.W && ty in 0 until World.H) {
                    val k = ty * World.W + tx
                    if (supply[k] < 0) supply[k] = id
                }
            }
        }
        for (b in f.buildings) {
            if (b is PowerPole && b !is PowerSource) continue
            val needs = b.type.power > 0f || b is PowerSource || b is Accumulator
            if (!needs) continue
            var net = if (b is PowerPole) b.powerNet else -1
            if (net < 0) {
                loop@ for (ty in b.y until b.y + b.size) for (tx in b.x until b.x + b.size) {
                    val n = supply[ty * World.W + tx]
                    if (n >= 0) { net = n; break@loop }
                }
            }
            b.powerNet = net
            if (net < 0) { b.satisfaction = 0f; continue }
            val pn = nets[net]
            if (b is PowerSource) pn.sources.add(b)
            if (b is Accumulator) pn.accumulators.add(b)
            if (b.type.power > 0f) pn.consumers.add(b)
        }
        // Unconnected consumers get nothing.
        for (b in f.buildings) if (b.powerNet < 0) b.satisfaction = 0f
    }

    fun update(dt: Float) {
        var genTotal = 0f; var useTotal = 0f; var capTotal = 0f
        for (net in nets) {
            var demand = 0f
            for (c in net.consumers) demand += c.demand
            var avail = 0f
            for (s in net.sources) avail += s.available()
            var accAvail = 0f
            for (a in net.accumulators) accAvail += min(Accumulator.RATE_KW, a.stored / dt)
            capTotal += avail + accAvail
            val sat: Float
            if (avail >= demand) {
                sat = 1f
                // Surplus charges accumulators.
                var surplus = avail - demand
                for (a in net.accumulators) {
                    val room = (Accumulator.CAPACITY_KJ - a.stored) / dt
                    val c = min(min(Accumulator.RATE_KW, room), surplus)
                    a.stored += c * dt; a.flow = c; surplus -= c
                }
                val used = avail - surplus
                drawFromSources(net, used, avail, dt)
                net.generated = used
            } else {
                val fromAcc = min(accAvail, demand - avail)
                sat = if (demand > 0f) (avail + fromAcc) / demand else 1f
                var need = fromAcc
                for (a in net.accumulators) {
                    val c = min(min(Accumulator.RATE_KW, a.stored / dt), need)
                    a.stored -= c * dt; a.flow = -c; need -= c
                }
                drawFromSources(net, avail, avail, dt)
                net.generated = avail + fromAcc
            }
            net.demand = demand
            net.satisfaction = sat
            for (c in net.consumers) c.satisfaction = sat
            net.checkFailure(f, dt)
            genTotal += net.generated
            useTotal += demand * sat
        }
        f.stats.samplePower(genTotal, useTotal, dt)
        f.stats.capNow = capTotal
    }

    /** Spread [used] kW across sources proportionally to what each can offer. */
    private fun drawFromSources(net: PowerNet, used: Float, avail: Float, dt: Float) {
        for (s in net.sources) {
            val a = s.available()
            s.draw(if (avail > 0f) used * a / avail else 0f, dt)
        }
    }

    /** Total stored accumulator energy (kJ) across all networks. */
    fun storedEnergy() = nets.sumOf { n -> n.accumulators.sumOf { it.stored.toDouble() } }.toFloat()
}
