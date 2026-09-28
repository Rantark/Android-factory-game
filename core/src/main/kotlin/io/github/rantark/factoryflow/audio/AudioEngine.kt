package io.github.rantark.factoryflow.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.AudioDevice
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/** One-shot interface sounds. */
enum class Sfx { CLICK, PLACE, REMOVE, CHIME, POWER_LOW, DENIED, UNDO }

/** Continuous machine layers whose loudness follows how many are working on screen. */
enum class Hum { DRILL, FURNACE, BELT, ASSEMBLER, POWER, FLUID, LAB }

/**
 * Real-time software synthesiser. Everything you hear is generated sample by sample
 * on a background thread and streamed to the platform's PCM output – no audio files.
 *
 * The game thread only writes a few parameters (biome, daylight, hum levels) and
 * queues one-shot [Sfx]; the synth smooths all changes so nothing clicks or jumps.
 * Everything is mixed through a gentle tanh limiter to stay soft and never harsh.
 */
class AudioEngine(private val enabled: Boolean) {
    // ---- Parameters written by the game thread ----
    @Volatile var biome = 0
    @Volatile var daylight = 1f
    @Volatile var muted = false
    val hums = FloatArray(Hum.entries.size)
    private val queue = ConcurrentLinkedQueue<Sfx>()

    private var device: AudioDevice? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun play(s: Sfx) { if (enabled && !muted) queue.add(s) }

    fun start() {
        if (!enabled || running) return
        if (device == null) {
            device = try { Gdx.audio?.newAudioDevice(RATE, true) } catch (e: Throwable) { null }
        }
        val dev = device ?: return
        running = true
        thread = Thread({ loop(dev) }, "synth").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    fun dispose() {
        stop()
        try { device?.dispose() } catch (_: Throwable) {}
        device = null
    }

    // =================================================================================
    // Synthesis (audio thread only below this line)
    // =================================================================================

    private val rnd = java.util.Random(1234)
    private fun noise() = rnd.nextFloat() * 2f - 1f

    // Smoothed parameters
    private var sDay = 1f
    private var sWind = 0.3f
    private var sCut = 700f
    private var sBirds = 1f
    private var sFrogs = 0f
    private var sWhistle = 0f
    private var sHiss = 0f
    private val sHum = FloatArray(Hum.entries.size)
    private var master = 0f

    // Oscillator / filter state
    private var t = 0.0
    private var windLp1 = 0f; private var windLp2 = 0f; private var rumble = 0f
    private var gust = 0f; private var gustTarget = 0f; private var gustTimer = 0f
    private var bp1 = 0f; private var bp2 = 0f
    private var beltLp = 0f; private var beltBp = 0f
    private var crackle = 0f; private var crackleLp = 0f
    private var furnaceRumble = 0f
    private var fluidLp = 0f

    /** Short-lived voices (UI sounds, bird chirps, frog croaks, crickets). */
    private class Voice(var kind: Int, var f0: Float, var f1: Float, var dur: Float, var amp: Float, var delay: Float = 0f) {
        var age = 0f
        var phase = 0.0
        var phase2 = 0.0
    }
    private val voices = ArrayList<Voice>()
    private var birdTimer = 2f
    private var frogTimer = 3f
    private var cricketPhase = 0.0

    private fun loop(dev: AudioDevice) {
        val buf = ShortArray(BUF)
        while (running) {
            try {
                fill(buf)
                dev.writeSamples(buf, 0, buf.size)
            } catch (e: Throwable) {
                running = false
            }
        }
    }

    private fun biomeTargets() {
        // wind level, cutoff, birds, frogs, highland whistle, sand hiss
        val p = when (biome) {
            1 -> floatArrayOf(0.5f, 420f, 0.35f, 0f, 0.35f, 0f)     // highlands
            2 -> floatArrayOf(0.42f, 1500f, 0.2f, 0f, 0f, 0.5f)     // sandy flats
            3 -> floatArrayOf(0.24f, 900f, 1.4f, 0f, 0f, 0f)        // forest
            4 -> floatArrayOf(0.22f, 480f, 0.6f, 1f, 0f, 0f)        // wetlands
            else -> floatArrayOf(0.32f, 700f, 1f, 0f, 0f, 0f)       // grassland
        }
        val k = 0.0004f // per-block smoothing – biome shifts fade over ~ a second
        sWind += (p[0] - sWind) * k * BUF
        sCut += (p[1] - sCut) * k * BUF
        sBirds += (p[2] - sBirds) * k * BUF
        sFrogs += (p[3] - sFrogs) * k * BUF
        sWhistle += (p[4] - sWhistle) * k * BUF
        sHiss += (p[5] - sHiss) * k * BUF
        sDay += (daylight - sDay) * 0.0002f * BUF
        for (i in sHum.indices) sHum[i] += (hums[i] - sHum[i]) * 0.00025f * BUF
        val targetMaster = if (muted) 0f else 1f
        master += (targetMaster - master) * 0.0003f * BUF
    }

    private fun fill(buf: ShortArray) {
        biomeTargets()
        while (true) {
            val s = queue.poll() ?: break
            trigger(s)
        }
        val dt = 1f / RATE
        val night = 1f - sDay
        // Schedule nature sounds per block.
        birdTimer -= BUF * dt
        if (birdTimer <= 0f) {
            birdTimer = 1.5f + rnd.nextFloat() * 5f
            if (rnd.nextFloat() < sBirds * sDay * 0.8f) bird()
        }
        frogTimer -= BUF * dt
        if (frogTimer <= 0f) {
            frogTimer = 0.8f + rnd.nextFloat() * 3f
            if (rnd.nextFloat() < sFrogs * (0.3f + night)) frog()
        }
        val cutoffA = (2 * PI * sCut * (0.7f + 0.3f * sDay) / RATE).toFloat().coerceAtMost(0.9f)
        for (i in buf.indices) {
            t += dt.toDouble()
            // ---- Wind: filtered noise with slowly wandering gusts ----
            gustTimer -= dt
            if (gustTimer <= 0f) { gustTimer = 2f + rnd.nextFloat() * 5f; gustTarget = rnd.nextFloat() }
            gust += (gustTarget - gust) * 0.00004f
            val n = noise()
            windLp1 += (n - windLp1) * cutoffA * (0.4f + gust)
            windLp2 += (windLp1 - windLp2) * cutoffA
            rumble += (n - rumble) * 0.004f
            var out = (windLp2 * 1.6f + rumble * 3f) * sWind * (0.45f + 0.55f * gust) * (0.75f + 0.25f * sDay)
            // Highland whistle: resonant band-pass around ~900 Hz.
            if (sWhistle > 0.01f) {
                val f = 2f * sin(PI.toFloat() * (850f + 150f * gust) / RATE)
                bp1 += f * (n - bp1 - bp2 * 0.08f); bp2 += f * bp1
                out += bp2 * 0.015f * sWhistle * gust
            }
            if (sHiss > 0.01f) out += (n - windLp1) * 0.03f * sHiss * gust
            // Crickets at night: pulsed high sine in little trills.
            if (night > 0.05f) {
                cricketPhase += 4600.0 / RATE
                val trill = if (sin(t * 2 * PI * 0.7) > 0.2) 1f else 0f
                val pulse = if (sin(t * 2 * PI * 32) > 0.3) 1f else 0f
                out += sin(cricketPhase * 2 * PI).toFloat() * 0.012f * night * trill * pulse
            }
            out += machines(dt, n)
            out += voicesSample(dt)
            // Soft limiter.
            val y = tanh(out * 1.3f) * 0.8f * master
            buf[i] = (y * 32000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /** Continuous building hums. */
    private fun machines(dt: Float, n: Float): Float {
        var out = 0f
        val tt = t
        // Drills: low mechanical pulse (triangle at 55 Hz, amplitude thumping ~2.3 Hz).
        val d = sHum[Hum.DRILL.ordinal]
        if (d > 0.005f) {
            val thump = exp(-((tt * 2.3) % 1.0) * 6.0).toFloat()
            val tri = (2.0 * kotlin.math.abs(2.0 * ((tt * 55.0) % 1.0) - 1.0) - 1.0).toFloat()
            out += (tri * 0.6f + sin(tt * 2 * PI * 110).toFloat() * 0.3f) * thump * 0.09f * d
        }
        // Furnaces: warm rumble plus random crackles.
        val fu = sHum[Hum.FURNACE.ordinal]
        if (fu > 0.005f) {
            furnaceRumble += (n - furnaceRumble) * 0.01f
            if (rnd.nextFloat() < 0.0015f * fu) crackle = 0.6f + rnd.nextFloat() * 0.4f
            crackle *= 0.992f
            crackleLp += (n * crackle - crackleLp) * 0.35f
            out += (furnaceRumble * 0.35f + crackleLp * 0.12f) * fu
        }
        // Belts: soft rhythmic whir – band-limited noise with a 7 Hz lilt.
        val be = sHum[Hum.BELT.ordinal]
        if (be > 0.005f) {
            beltLp += (n - beltLp) * 0.12f
            beltBp += (beltLp - beltBp) * 0.05f
            val lilt = 0.6f + 0.4f * sin(tt * 2 * PI * 7).toFloat()
            out += (beltLp - beltBp) * 0.1f * lilt * be
        }
        // Assemblers: layered rhythm – two click trains in a 3:4 pattern over a pad.
        val a = sHum[Hum.ASSEMBLER.ordinal]
        if (a > 0.005f) {
            val c1 = exp(-((tt * 3.0) % 1.0) * 40.0).toFloat() * sin(tt * 2 * PI * 820).toFloat()
            val c2 = exp(-((tt * 4.0 + 0.13) % 1.0) * 30.0).toFloat() * sin(tt * 2 * PI * 610).toFloat()
            val pad = sin(tt * 2 * PI * 146.8).toFloat() * (0.5f + 0.5f * sin(tt * 2 * PI * 0.5).toFloat())
            out += (c1 * 0.05f + c2 * 0.04f + pad * 0.025f) * a
        }
        // Generators: mains hum.
        val p = sHum[Hum.POWER.ordinal]
        if (p > 0.005f) {
            out += (sin(tt * 2 * PI * 60).toFloat() * 0.5f + sin(tt * 2 * PI * 120).toFloat() * 0.25f) * 0.03f * p *
                (0.9f + 0.1f * sin(tt * 2 * PI * 0.3).toFloat())
        }
        // Pumps & refineries: slow liquid whoosh.
        val fl = sHum[Hum.FLUID.ordinal]
        if (fl > 0.005f) {
            fluidLp += (n - fluidLp) * 0.02f
            out += fluidLp * 0.5f * fl * (0.5f + 0.5f * sin(tt * 2 * PI * 0.6).toFloat())
        }
        // Labs: faint shimmering chord.
        val l = sHum[Hum.LAB.ordinal]
        if (l > 0.005f) {
            out += (sin(tt * 2 * PI * 880).toFloat() + sin(tt * 2 * PI * 1318.5).toFloat() * 0.6f) *
                0.006f * l * (0.5f + 0.5f * sin(tt * 2 * PI * 0.25).toFloat())
        }
        return out
    }

    // ---- Voices ------------------------------------------------------------------------

    private fun trigger(s: Sfx) {
        when (s) {
            Sfx.CLICK -> voices.add(Voice(K_SINE, 1500f, 1300f, 0.05f, 0.16f))
            Sfx.PLACE -> {
                voices.add(Voice(K_SINE, 240f, 105f, 0.14f, 0.45f))
                voices.add(Voice(K_NOISE, 0f, 0f, 0.035f, 0.12f))
                voices.add(Voice(K_SINE, 900f, 900f, 0.06f, 0.06f, 0.02f))
            }
            Sfx.REMOVE -> {
                voices.add(Voice(K_SINE, 330f, 140f, 0.22f, 0.35f))
                voices.add(Voice(K_NOISE, 0f, 0f, 0.08f, 0.1f))
            }
            Sfx.CHIME -> {
                val notes = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)
                for ((k, f) in notes.withIndex()) voices.add(Voice(K_BELL, f, f, 1.8f, 0.2f, k * 0.13f))
            }
            Sfx.POWER_LOW -> {
                voices.add(Voice(K_TRI, 110f, 98f, 0.9f, 0.35f))
                voices.add(Voice(K_TRI, 55f, 49f, 0.9f, 0.3f))
            }
            Sfx.DENIED -> {
                voices.add(Voice(K_TRI, 196f, 185f, 0.07f, 0.3f))
                voices.add(Voice(K_TRI, 165f, 155f, 0.09f, 0.3f, 0.1f))
            }
            Sfx.UNDO -> voices.add(Voice(K_SINE, 420f, 840f, 0.12f, 0.22f))
        }
        while (voices.size > 24) voices.removeAt(0)
    }

    private fun bird() {
        val base = 2300f + rnd.nextFloat() * 1600f
        val count = 2 + rnd.nextInt(4)
        for (k in 0 until count) {
            val up = rnd.nextBoolean()
            voices.add(Voice(K_SINE, if (up) base else base * 1.3f, if (up) base * 1.35f else base, 0.06f + rnd.nextFloat() * 0.05f, 0.04f, k * 0.12f))
        }
    }

    private fun frog() {
        voices.add(Voice(K_CROAK, 140f + rnd.nextFloat() * 60f, 120f, 0.28f, 0.14f))
        if (rnd.nextBoolean()) voices.add(Voice(K_CROAK, 150f + rnd.nextFloat() * 60f, 130f, 0.22f, 0.1f, 0.35f))
    }

    private fun voicesSample(dt: Float): Float {
        var out = 0f
        var i = 0
        while (i < voices.size) {
            val v = voices[i]
            if (v.delay > 0f) { v.delay -= dt; i++; continue }
            v.age += dt
            val p = (v.age / v.dur).coerceAtMost(1f)
            if (p >= 1f) { voices.removeAt(i); continue }
            val f = v.f0 + (v.f1 - v.f0) * p
            v.phase += f / RATE
            // Quick attack, smooth exponential release.
            val env = (v.age / 0.004f).coerceAtMost(1f) * exp(-p * 4.5f) * (1f - p)
            val s = when (v.kind) {
                K_SINE -> sin(v.phase * 2 * PI).toFloat()
                K_TRI -> (2.0 * kotlin.math.abs(2.0 * (v.phase % 1.0) - 1.0) - 1.0).toFloat()
                K_NOISE -> noise() * 0.6f
                K_BELL -> {
                    v.phase2 += f * 2.76 / RATE
                    (sin(v.phase * 2 * PI) + 0.35 * sin(v.phase2 * 2 * PI) + 0.15 * sin(v.phase * 5.4 * 2 * PI)).toFloat() * exp(-v.age * 2.2f) / exp(-p * 4.5f).coerceAtLeast(0.05f)
                }
                K_CROAK -> {
                    val am = if (sin(v.age * 2 * PI * 26) > 0) 1f else 0.2f
                    (((v.phase % 1.0) * 2 - 1).toFloat()) * am * 0.6f
                }
                else -> 0f
            }
            out += s * env * v.amp
            i++
        }
        return out
    }

    companion object {
        const val RATE = 22050
        const val BUF = 512
        private const val K_SINE = 0
        private const val K_TRI = 1
        private const val K_NOISE = 2
        private const val K_BELL = 3
        private const val K_CROAK = 4
    }
}
