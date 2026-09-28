package io.github.rantark.factoryflow.world

import io.github.rantark.factoryflow.data.Item
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Biomes with their dark, earthy base palettes (base / variation colour). */
enum class Biome(val title: String, val base: Int, val alt: Int) {
    GRASSLAND("Grassland", 0x3D5A31, 0x4B6B3A),
    HIGHLANDS("Rocky Highlands", 0x57524A, 0x6A6358),
    SANDY("Sandy Flats", 0x8A744E, 0x9A8458),
    FOREST("Dense Forest", 0x2A4528, 0x345532),
    WETLANDS("Wetlands", 0x364D43, 0x42594C),
}

/** Resource found in a tile. Solid ones are mined by drills; OIL needs a pump jack. */
enum class Resource(val title: String, val item: Item?, val color: Int) {
    NONE("", null, 0),
    IRON("Iron", Item.IRON_ORE, 0x9DB2C8),
    COPPER("Copper", Item.COPPER_ORE, 0xE8894D),
    COAL("Coal", Item.COAL, 0x2A2A30),
    STONE("Stone", Item.STONE, 0xD4BE96),
    CRYSTAL("Rare Crystal", Item.CRYSTAL, 0xC77DFF),
    OIL("Crude Oil", null, 0x2E2236);

    val solid get() = item != null
    companion object { val ALL = entries.toTypedArray() }
}

/** A naturally clustered patch of one resource – shown with a depletion bar. */
class Deposit(val id: Int, val res: Resource, var cx: Float, var cy: Float) {
    var initial = 0L
    var remaining = 0L
    var tiles = 0
    /** Smoothed extraction rate (units / second) for depletion estimates. */
    var rate = 0f
    var minedThisSecond = 0
}

/**
 * The 512×512 tile world. Everything is regenerated deterministically from [seed];
 * saves only store how much of each resource tile has been used up.
 */
class World(val seed: Long) {
    val biome = ByteArray(W * H)
    val water = BooleanArray(W * H)
    val res = ByteArray(W * H)
    val amount = IntArray(W * H)
    val depositOf = ShortArray(W * H) { -1 }
    val deposits = ArrayList<Deposit>()
    /** Fog of war at 8×8-tile granularity (for the minimap). */
    val explored = BooleanArray(EW * EW)
    var exploredVersion = 0
    /** Chunk meshes that must be rebuilt (e.g. after a tile ran out of ore). */
    val chunkDirty = BooleanArray(CHUNKS * CHUNKS)
    /** Original amounts, so saves can store only the difference. */
    lateinit var initialAmount: IntArray

    private val elevN = Noise(seed)
    private val moistN = Noise(seed * 31 + 7)
    private val detailN = Noise(seed * 131 + 3)

    init { generate() }

    fun idx(x: Int, y: Int) = y * W + x
    fun inBounds(x: Int, y: Int) = x in 0 until W && y in 0 until H
    fun biomeAt(x: Int, y: Int) = Biome.entries[biome[idx(x, y)].toInt()]
    fun resAt(x: Int, y: Int): Resource = if (inBounds(x, y)) Resource.ALL[res[idx(x, y)].toInt()] else Resource.NONE
    fun isWater(x: Int, y: Int) = inBounds(x, y) && water[idx(x, y)]

    /** Remove [n] units from a tile; returns what was actually mined. */
    fun mine(x: Int, y: Int, n: Int): Int {
        val i = idx(x, y)
        val a = amount[i]
        if (a <= 0) return 0
        val m = min(a, n)
        amount[i] = a - m
        val d = depositOf[i].toInt()
        if (d >= 0) { deposits[d].remaining -= m; deposits[d].minedThisSecond += m }
        // Solid tiles vanish when empty; oil seeps never fully disappear.
        if (amount[i] == 0 && Resource.ALL[res[i].toInt()].solid) {
            res[i] = 0
            markDirty(x, y)
        } else if (a > 0 && (a * 4 / max(1, initialAmount[i])) != (amount[i] * 4 / max(1, initialAmount[i]))) {
            markDirty(x, y) // visual richness changed
        }
        return m
    }

    fun markDirty(x: Int, y: Int) { chunkDirty[(y / CHUNK) * CHUNKS + x / CHUNK] = true }

    fun explore(x0: Int, y0: Int, x1: Int, y1: Int) {
        val a = max(0, x0 / 8); val b = max(0, y0 / 8)
        val c = min(EW - 1, x1 / 8); val d = min(EW - 1, y1 / 8)
        for (ey in b..d) for (ex in a..c) {
            val k = ey * EW + ex
            if (!explored[k]) { explored[k] = true; exploredVersion++ }
        }
    }

    fun isExplored(x: Int, y: Int) = explored[(y / 8) * EW + x / 8]

    /** Per-tile deterministic pseudo-random value in [0,1). */
    fun hash(x: Int, y: Int, salt: Int = 0): Float {
        var h = x * 374761393 + y * 668265263 + salt * 1442695041 + seed.toInt()
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    fun detail(x: Float, y: Float) = detailN.raw(x, y)

    // --------------------------------------------------------------------------------
    // Generation
    // --------------------------------------------------------------------------------

    private fun generate() {
        val sx = START_X.toFloat(); val sy = START_Y.toFloat()
        for (y in 0 until H) for (x in 0 until W) {
            var e = elevN.fbm(x / 110f, y / 110f, 5)
            var m = moistN.fbm(x / 140f + 50f, y / 140f + 50f, 4)
            val d = hypot(x - sx, y - sy)
            // Flatten a calm grassland clearing around the starting zone.
            val t = ((d - 16f) / 22f).coerceIn(0f, 1f)
            e = 0.5f + (e - 0.5f) * t
            m = 0.45f + (m - 0.45f) * t
            val i = idx(x, y)
            val b = when {
                e > 0.66f -> Biome.HIGHLANDS
                m > 0.62f && e < 0.5f -> Biome.WETLANDS
                m > 0.55f -> Biome.FOREST
                m < 0.36f -> Biome.SANDY
                else -> Biome.GRASSLAND
            }
            biome[i] = b.ordinal.toByte()
            val pond = detailN.fbm(x / 18f, y / 18f, 2)
            water[i] = d > 24f && (e < 0.27f || (b == Biome.WETLANDS && pond > 0.62f))
        }
        placeDeposits()
        initialAmount = amount.copyOf()
        explore(START_X - 40, START_Y - 28, START_X + 40, START_Y + 28)
    }

    private fun placeDeposits() {
        val rnd = java.util.Random(seed * 7 + 11)
        // Guaranteed mixed deposits close to the core so the first chains are easy.
        val sx = START_X; val sy = START_Y
        blob(Resource.IRON, sx - 15f, sy + 5f, 5.5f, rnd, 1.0f)
        blob(Resource.COPPER, sx + 14f, sy + 7f, 5f, rnd, 1.0f)
        blob(Resource.COAL, sx - 3f, sy - 15f, 4.5f, rnd, 1.0f)
        blob(Resource.STONE, sx + 15f, sy - 10f, 4f, rnd, 1.0f)
        blob(Resource.IRON, sx + 3f, sy + 18f, 4f, rnd, 0.8f)
        blob(Resource.CRYSTAL, sx + 34f, sy + 26f, 2.6f, rnd, 1.0f)
        blob(Resource.OIL, sx - 30f, sy + 22f, 1.8f, rnd, 1.0f)
        pond(sx - 22, sy - 12, 3.2f)

        var attempts = 0
        while (deposits.size < 170 && attempts < 4000) {
            attempts++
            val x = rnd.nextInt(W - 20) + 10
            val y = rnd.nextInt(H - 20) + 10
            val dist = hypot((x - sx).toFloat(), (y - sy).toFloat())
            if (dist < 45f || water[idx(x, y)]) continue
            val b = biomeAt(x, y)
            val r = pick(b, rnd)
            val rich = 1f + dist / 180f
            val radius = when (r) {
                Resource.OIL -> 1.2f + rnd.nextFloat() * 1.3f
                Resource.CRYSTAL -> 2f + rnd.nextFloat() * 2f
                else -> 3.5f + rnd.nextFloat() * 5f
            }
            // Keep patches from overlapping.
            if (deposits.any { hypot(it.cx - x, it.cy - y) < radius + 9f }) continue
            blob(r, x.toFloat(), y.toFloat(), radius, rnd, rich)
        }
    }

    private fun pick(b: Biome, rnd: java.util.Random): Resource {
        // Weighted by biome: highlands favour iron/stone/crystal, forests coal, etc.
        val w = when (b) {
            Biome.GRASSLAND -> floatArrayOf(3f, 3f, 2f, 1.5f, 0.3f, 0.6f)
            Biome.HIGHLANDS -> floatArrayOf(4f, 1f, 1f, 4f, 1.2f, 0.2f)
            Biome.SANDY -> floatArrayOf(1f, 4f, 0.5f, 3f, 0.4f, 2.2f)
            Biome.FOREST -> floatArrayOf(2f, 1.5f, 4f, 1f, 0.3f, 0.5f)
            Biome.WETLANDS -> floatArrayOf(1f, 2f, 3f, 0.5f, 0.3f, 2.5f)
        }
        var t = rnd.nextFloat() * w.sum()
        val opts = arrayOf(Resource.IRON, Resource.COPPER, Resource.COAL, Resource.STONE, Resource.CRYSTAL, Resource.OIL)
        for (k in w.indices) { t -= w[k]; if (t <= 0f) return opts[k] }
        return Resource.IRON
    }

    private fun blob(r: Resource, cx: Float, cy: Float, radius: Float, rnd: java.util.Random, rich: Float) {
        val dep = Deposit(deposits.size, r, cx, cy)
        val ri = radius.toInt() + 3
        var sumX = 0f; var sumY = 0f
        for (y in (cy.toInt() - ri)..(cy.toInt() + ri)) for (x in (cx.toInt() - ri)..(cx.toInt() + ri)) {
            if (!inBounds(x, y)) continue
            val i = idx(x, y)
            if (water[i] || res[i].toInt() != 0) continue
            val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
            val wobble = detailN.raw(x / 3.5f + dep.id * 13f, y / 3.5f) * radius * 0.7f
            if (d > radius + wobble) continue
            if (r == Resource.OIL && rnd.nextFloat() < 0.35f) continue // sparse seeps
            val core = 1f - (d / (radius + 1f)).coerceIn(0f, 1f) * 0.6f
            val base = when (r) {
                Resource.OIL -> 30000
                Resource.CRYSTAL -> 350
                else -> 900
            }
            val a = (base * rich * core * (0.8f + rnd.nextFloat() * 0.4f)).toInt()
            res[i] = r.ordinal.toByte(); amount[i] = a; depositOf[i] = dep.id.toShort()
            dep.initial += a; dep.tiles++
            sumX += x + 0.5f; sumY += y + 0.5f
        }
        if (dep.tiles == 0) {
            // Undo the id: nothing got placed.
            return
        }
        dep.cx = sumX / dep.tiles; dep.cy = sumY / dep.tiles
        dep.remaining = dep.initial
        deposits.add(dep)
    }

    private fun pond(cx: Int, cy: Int, r: Float) {
        val ri = r.toInt() + 2
        for (y in cy - ri..cy + ri) for (x in cx - ri..cx + ri) {
            if (!inBounds(x, y)) continue
            val d = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat())
            if (d <= r + detailN.raw(x / 2.5f, y / 2.5f) * 1.2f) {
                val i = idx(x, y)
                if (res[i].toInt() == 0) water[i] = true
            }
        }
    }

    /** Recompute deposit totals after loading a save. */
    fun recountDeposits() {
        for (d in deposits) d.remaining = 0
        for (i in 0 until W * H) {
            val d = depositOf[i].toInt()
            if (d >= 0) deposits[d].remaining += amount[i]
        }
    }

    /** Nearest deposit of [r] within [maxDist] (used by the stats panel / labels). */
    fun nearestDeposit(x: Float, y: Float, maxDist: Float): Deposit? =
        deposits.filter { it.remaining > 0 && abs(it.cx - x) < maxDist && abs(it.cy - y) < maxDist }
            .minByOrNull { hypot(it.cx - x, it.cy - y) }

    companion object {
        const val W = 512
        const val H = 512
        const val CHUNK = 32
        const val CHUNKS = W / CHUNK
        const val EW = W / 8
        const val START_X = 256
        const val START_Y = 256
    }
}
