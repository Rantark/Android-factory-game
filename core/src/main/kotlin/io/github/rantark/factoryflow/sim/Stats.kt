package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.Fluid
import io.github.rantark.factoryflow.data.Item

/**
 * Rolling production statistics. Counts land in 1-second buckets; summing the last 60
 * buckets gives per-minute rates. Power is sampled once a second for the graph.
 */
class Stats(private val f: Factory) {
    private val prod = Array(Item.COUNT) { FloatArray(BUCKETS) }
    private val cons = Array(Item.COUNT) { FloatArray(BUCKETS) }
    private val fprod = Array(Fluid.COUNT) { FloatArray(BUCKETS) }
    private val fcons = Array(Fluid.COUNT) { FloatArray(BUCKETS) }
    private val deliv = Array(Item.COUNT) { FloatArray(BUCKETS) }
    private var bucket = 0
    private var bucketTime = 0f

    /** Power history (kW), one sample per second, newest at [powerHead]-1. */
    val powerGen = FloatArray(HISTORY)
    val powerUse = FloatArray(HISTORY)
    var powerHead = 0
        private set
    var powerSamples = 0
        private set
    private var genAcc = 0f; private var useAcc = 0f; private var accTime = 0f
    /** Latest instantaneous values. */
    var genNow = 0f; var useNow = 0f
    /** Total generation capacity available right now (incl. accumulator output). */
    var capNow = 0f

    fun produced(item: Int, n: Int) { prod[item][bucket] += n.toFloat() }
    fun consumed(item: Int, n: Int) { cons[item][bucket] += n.toFloat() }
    fun delivered(item: Int) { deliv[item][bucket] += 1f }
    fun producedFluid(fl: Int, a: Float) { fprod[fl][bucket] += a }
    fun consumedFluid(fl: Int, a: Float) { fcons[fl][bucket] += a }

    fun producedPerMin(item: Int) = prod[item].sum()
    fun consumedPerMin(item: Int) = cons[item].sum()
    fun deliveredPerMin(item: Int) = deliv[item].sum()
    fun fluidProducedPerMin(fl: Int) = fprod[fl].sum()
    fun fluidConsumedPerMin(fl: Int) = fcons[fl].sum()

    fun samplePower(gen: Float, use: Float, dt: Float) {
        genNow = gen; useNow = use
        genAcc += gen * dt; useAcc += use * dt; accTime += dt
        if (accTime >= 1f) {
            powerGen[powerHead] = genAcc / accTime
            powerUse[powerHead] = useAcc / accTime
            powerHead = (powerHead + 1) % HISTORY
            if (powerSamples < HISTORY) powerSamples++
            genAcc = 0f; useAcc = 0f; accTime = 0f
        }
    }

    fun update(dt: Float) {
        bucketTime += dt
        if (bucketTime >= 1f) {
            bucketTime -= 1f
            bucket = (bucket + 1) % BUCKETS
            for (a in prod) a[bucket] = 0f
            for (a in cons) a[bucket] = 0f
            for (a in fprod) a[bucket] = 0f
            for (a in fcons) a[bucket] = 0f
            for (a in deliv) a[bucket] = 0f
            // Exponentially smoothed extraction rate per deposit.
            for (d in f.world.deposits) {
                d.rate = d.rate * 0.95f + d.minedThisSecond * 0.05f
                d.minedThisSecond = 0
            }
        }
    }

    companion object {
        const val BUCKETS = 60
        const val HISTORY = 180
    }
}
