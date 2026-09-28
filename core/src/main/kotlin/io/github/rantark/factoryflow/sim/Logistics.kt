package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Item
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Conveyor belt tile. Items ride a single lane and are stored in fixed-size primitive
 * arrays (no per-item objects – the ultimate object pool, zero garbage per frame).
 * Progress runs 0 (entry edge) → 1 (exit edge); items keep at least [SPACING] apart.
 */
open class Belt(type: BuildingType, x: Int, y: Int, dir: Int) : Building(type, x, y, dir) {
    val items = IntArray(MAX)
    val prog = FloatArray(MAX)
    var count = 0

    /** 0 straight, +1 fed only from the left side (curves), -1 fed only from the right. */
    var curve = 0

    /** Tunnel length for underground entrances; 1 for normal tiles. */
    open val length: Float get() = 1f

    private var next: Building? = null
    private var nextVersion = -1

    /** The building items leave into. Cached until the map changes. */
    open fun target(): Building? {
        if (nextVersion != f.version) {
            next = f.at(x + Dir.DX[dir], y + Dir.DY[dir])
            nextVersion = f.version
        }
        return next
    }

    override fun update(dt: Float) {
        val n0 = count
        if (n0 == 0) { working = false; return }
        val move = f.beltSpeed * dt / length
        for (i in 0 until count) prog[i] += move
        // Hand items over the exit edge (up to two per update for large LOD steps).
        var transfers = 0
        while (count > 0 && prog[0] >= 1f) {
            val t = target()
            if (transfers < 2 && t != null && handOver(t, items[0], prog[0] - 1f)) {
                removeAt(0); transfers++
            } else { prog[0] = 1f; break }
        }
        for (i in 1 until count) {
            val lim = prog[i - 1] - SPACING
            if (prog[i] > lim) prog[i] = lim
        }
        working = true
        anim += dt * f.beltSpeed
    }

    /** Pass an item across the exit edge into [t]. */
    protected open fun handOver(t: Building, item: Int, overflow: Float) = t.offer(item, dir, overflow)

    override fun offer(item: Int, moveDir: Int, overflow: Float): Boolean {
        val rel = (moveDir - dir + 4) and 3
        if (rel == 2) return false
        if (rel == 0 || (curve == 1 && rel == 3) || (curve == -1 && rel == 1)) return insertBack(item, overflow)
        return insertMiddle(item)
    }

    override fun wants(item: Int) = count < MAX

    fun insertBack(item: Int, overflow: Float): Boolean {
        if (count >= MAX) return false
        if (count > 0 && prog[count - 1] < SPACING) return false
        var p = overflow.coerceIn(0f, 0.2f)
        if (count > 0) p = minOf(p, prog[count - 1] - SPACING)
        items[count] = item; prog[count] = p; count++
        return true
    }

    /** Side-load / inserter drop: slot the item in near the middle if there is a gap. */
    fun insertMiddle(item: Int): Boolean {
        if (count >= MAX) return false
        val p = 0.5f
        var k = 0
        while (k < count && prog[k] >= p) k++
        val gap = SPACING * 0.95f
        if (k > 0 && prog[k - 1] - p < gap) return false
        if (k < count && p - prog[k] < gap) return false
        for (j in count downTo k + 1) { items[j] = items[j - 1]; prog[j] = prog[j - 1] }
        items[k] = item; prog[k] = p; count++
        return true
    }

    fun removeAt(k: Int) {
        for (j in k until count - 1) { items[j] = items[j + 1]; prog[j] = prog[j + 1] }
        count--
    }

    /** Inserters pick from belts: take any item the target wants. */
    override fun take(target: Building?, filter: Int): Int {
        for (k in 0 until count) {
            val it = items[k]
            if (filter >= 0 && it != filter) continue
            if (target != null && !target.wants(it)) continue
            removeAt(k)
            return it
        }
        return -1
    }

    /** Recompute curve from neighbouring belts that feed into this one. */
    fun updateCurve() {
        fun feeds(d: Int): Boolean {
            val b = f.at(x + Dir.DX[d], y + Dir.DY[d]) as? Belt ?: return false
            if (b is UndergroundBelt && !b.isExit) return false
            return b.x + Dir.DX[b.dir] == x && b.y + Dir.DY[b.dir] == y
        }
        curve = 0
        if (this is UndergroundBelt) return
        if (feeds(Dir.opposite(dir))) return
        val l = feeds(Dir.left(dir)); val r = feeds(Dir.right(dir))
        if (l && !r) curve = 1 else if (r && !l) curve = -1
    }

    override fun dumpContents(stock: IntArray) {
        for (k in 0 until count) stock[items[k]]++
        count = 0
    }

    override fun write(o: DataOutputStream) {
        o.writeByte(count)
        for (k in 0 until count) { o.writeByte(items[k]); o.writeFloat(prog[k]) }
    }

    override fun read(i: DataInputStream, version: Int) {
        count = i.readByte().toInt()
        for (k in 0 until count) { items[k] = i.readByte().toInt(); prog[k] = i.readFloat() }
    }

    companion object {
        const val MAX = 4
        const val SPACING = 0.25f
    }
}

/**
 * Underground belt. The first one placed is an entrance; placing another further along
 * the same direction (within [MAX_GAP] tiles) makes it the paired exit. Items travel
 * through the tunnel as if on a belt of length = distance.
 */
class UndergroundBelt(x: Int, y: Int, dir: Int) : Belt(BuildingType.UNDERGROUND, x, y, dir) {
    var isExit = false
    var partner: UndergroundBelt? = null

    override val length: Float
        get() = if (!isExit && partner != null) maxOf(1f, (kotlin.math.abs(partner!!.x - x) + kotlin.math.abs(partner!!.y - y)).toFloat()) else 1f

    override fun target(): Building? = if (isExit) super.target() else partner

    /** Entrances take items from behind; exits only receive from their tunnel. */
    override fun offer(item: Int, moveDir: Int, overflow: Float): Boolean {
        if (isExit || moveDir != dir) return false
        return insertBack(item, overflow)
    }

    override fun handOver(t: Building, item: Int, overflow: Float): Boolean =
        if (!isExit && t is UndergroundBelt) t.acceptFromTunnel(item, overflow) else t.offer(item, dir, overflow)

    /** Called only by the partner entrance – exits refuse items from the surface behind. */
    fun acceptFromTunnel(item: Int, overflow: Float) = insertBack(item, overflow)

    override fun onPlaced() {
        if (f.loading) return
        repair()
    }

    /** Pair with an unpaired entrance behind us, becoming its exit. */
    fun repair() {
        // Look back for an unpaired entrance facing the same way.
        val bd = Dir.opposite(dir)
        for (d in 1..MAX_GAP + 1) {
            val b = f.at(x + Dir.DX[bd] * d, y + Dir.DY[bd] * d)
            if (b is UndergroundBelt && b.dir == dir) {
                if (!b.isExit && b.partner == null) { isExit = true; partner = b; b.partner = this }
                return
            }
        }
    }

    override fun onRemoved() {
        partner?.partner = null  // an orphaned exit simply stops receiving
        partner = null
    }

    override fun write(o: DataOutputStream) {
        super.write(o)
        o.writeBoolean(isExit)
    }

    override fun read(i: DataInputStream, version: Int) {
        super.read(i, version)
        isExit = i.readBoolean()
    }

    companion object { const val MAX_GAP = 5 }
}

/** Shared logic for 1×1 splitters and mergers: a two-item buffer that re-emits items. */
abstract class Router(type: BuildingType, x: Int, y: Int, dir: Int) : Building(type, x, y, dir) {
    protected val buf = IntArray(2)
    protected var n = 0
    protected var cooldown = 0f

    protected fun push(item: Int): Boolean { if (n >= 2) return false; buf[n++] = item; return true }
    protected fun pop() { buf[0] = buf[1]; n-- }

    override fun wants(item: Int) = n < 2

    override fun dumpContents(stock: IntArray) { for (k in 0 until n) stock[buf[k]]++; n = 0 }
    override fun inventory(out: MutableList<Pair<Item, Int>>) { for (k in 0 until n) out.add(Item.ALL[buf[k]] to 1) }

    protected fun sendTo(d: Int, item: Int): Boolean {
        val t = f.at(x + Dir.DX[d], y + Dir.DY[d]) ?: return false
        return t.offer(item, d, 0f)
    }

    override fun write(o: DataOutputStream) { o.writeByte(n); for (k in 0 until n) o.writeByte(buf[k]) }
    override fun read(i: DataInputStream, version: Int) { n = i.readByte().toInt(); for (k in 0 until n) buf[k] = i.readByte().toInt() }
}

/** Takes items from behind and deals them round-robin to left, front and right. */
class Splitter(x: Int, y: Int, dir: Int) : Router(BuildingType.SPLITTER, x, y, dir) {
    private var rr = 0

    override fun offer(item: Int, moveDir: Int, overflow: Float) = moveDir == dir && push(item)

    override fun update(dt: Float) {
        cooldown -= dt
        working = n > 0
        if (n == 0 || cooldown > 0f) return
        val outs = intArrayOf(Dir.left(dir), dir, Dir.right(dir))
        for (k in 0 until 3) {
            val d = outs[(rr + k) % 3]
            if (sendTo(d, buf[0])) { pop(); rr = (rr + k + 1) % 3; cooldown = 1f / (f.beltSpeed * 4f); anim += 1f; return }
        }
    }
}

/** Accepts from back and both sides; emits forward. */
class Merger(x: Int, y: Int, dir: Int) : Router(BuildingType.MERGER, x, y, dir) {
    override fun offer(item: Int, moveDir: Int, overflow: Float) = moveDir != Dir.opposite(dir) && push(item)

    override fun update(dt: Float) {
        cooldown -= dt
        working = n > 0
        if (n == 0 || cooldown > 0f) return
        if (sendTo(dir, buf[0])) { pop(); cooldown = 1f / (f.beltSpeed * 4f); anim += 1f }
    }
}

/**
 * Inserter: picks from the tile [reach] behind it and drops [reach] tiles ahead.
 * [arm] animates 0 (at pickup) → 1 (at drop). It only grabs items the destination
 * actually wants, which keeps machines from clogging.
 */
class Inserter(type: BuildingType, x: Int, y: Int, dir: Int) : Building(type, x, y, dir) {
    val reach = if (type == BuildingType.LONG_INSERTER) 2 else 1
    /** Seconds for a full pickup→drop→return cycle at full power. */
    private val cycle = when (type) {
        BuildingType.FAST_INSERTER, BuildingType.FILTER_INSERTER -> 0.7f
        BuildingType.LONG_INSERTER -> 1.2f
        else -> 1.6f
    }
    var held = -1
    var arm = 0f
    /** 0 waiting to pick, 1 swinging out, 2 waiting to drop, 3 swinging back. */
    var phase = 0
    var filter = -1
    private var retry = 0f

    val pickX get() = x - Dir.DX[dir] * reach
    val pickY get() = y - Dir.DY[dir] * reach
    val dropX get() = x + Dir.DX[dir] * reach
    val dropY get() = y + Dir.DY[dir] * reach

    override fun update(dt: Float) {
        val speed = 2f / cycle * f.inserterBonus * satisfaction
        working = false
        when (phase) {
            0 -> {
                demand = type.power * 0.1f
                status = if (satisfaction <= 0.01f) Status.NO_POWER else Status.IDLE
                retry -= dt
                if (retry > 0f || satisfaction <= 0.01f) return
                retry = 0.1f
                val dst = f.at(dropX, dropY) ?: return
                val src = f.at(pickX, pickY) ?: return
                if (src === dst || src === this) return
                val flt = if (type == BuildingType.FILTER_INSERTER) filter else -1
                if (type == BuildingType.FILTER_INSERTER && filter < 0) return
                val it = src.take(if (dst is Belt) null else dst, flt)
                if (it >= 0) { held = it; phase = 1 }
            }
            1 -> {
                demand = type.power; working = true; status = Status.WORKING
                arm += dt * speed
                if (arm >= 1f) { arm = 1f; phase = 2 }
            }
            2 -> {
                demand = type.power * 0.1f
                val dst = f.at(dropX, dropY)
                val ok = when {
                    dst == null -> false
                    dst is UndergroundBelt -> dst.insertMiddle(held)
                    dst is Belt -> dst.insertMiddle(held)
                    else -> dst.offer(held, dir, 0f)
                }
                if (ok) { held = -1; phase = 3 } else status = Status.OUTPUT_FULL
            }
            3 -> {
                demand = type.power; working = true
                arm -= dt * speed
                if (arm <= 0f) { arm = 0f; phase = 0 }
            }
        }
        if (working) anim += dt
    }

    override fun dumpContents(stock: IntArray) { if (held >= 0) stock[held]++; held = -1 }
    override fun inventory(out: MutableList<Pair<Item, Int>>) { if (held >= 0) out.add(Item.ALL[held] to 1) }

    override fun config() = if (filter >= 0) "filter=$filter" else null
    override fun applyConfig(c: String?) { filter = c?.removePrefix("filter=")?.toIntOrNull() ?: -1 }

    override fun write(o: DataOutputStream) {
        o.writeByte(held); o.writeByte(phase); o.writeFloat(arm); o.writeByte(filter)
    }

    override fun read(i: DataInputStream, version: Int) {
        held = i.readByte().toInt(); phase = i.readByte().toInt(); arm = i.readFloat(); filter = i.readByte().toInt()
    }
}
