package io.github.rantark.factoryflow.game

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.utils.ScreenUtils
import io.github.rantark.factoryflow.Platform
import io.github.rantark.factoryflow.audio.AudioEngine
import io.github.rantark.factoryflow.audio.Hum
import io.github.rantark.factoryflow.audio.Sfx
import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Category
import io.github.rantark.factoryflow.gfx.Font
import io.github.rantark.factoryflow.gfx.VectorBatch
import io.github.rantark.factoryflow.render.Minimap
import io.github.rantark.factoryflow.render.Painter
import io.github.rantark.factoryflow.render.TerrainRenderer
import io.github.rantark.factoryflow.render.WorldRenderer
import io.github.rantark.factoryflow.sim.Factory
import io.github.rantark.factoryflow.sim.GameEvent
import io.github.rantark.factoryflow.sim.Scenario
import io.github.rantark.factoryflow.ui.Hud
import io.github.rantark.factoryflow.ui.Modal
import io.github.rantark.factoryflow.ui.Ui
import io.github.rantark.factoryflow.ui.UiInput
import kotlin.math.exp
import kotlin.math.min

/** Options used by the desktop launcher for automated screenshots. */
class DevOptions(
    val demo: Boolean = false,
    val screenshot: String? = null,
    val frames: Int = 90,
    val zoom: Float = 0f,
    val night: Boolean = false,
    val panel: String? = null,
    val seed: Long = 0L,
    val fresh: Boolean = false,
    val stress: Boolean = false,
    /** Camera offset from the core, in tiles. */
    val atX: Float = 0f,
    val atY: Float = 0f,
)

/**
 * Application entry point shared by Android and desktop. Owns the renderer, HUD,
 * audio engine and the current [Session]; runs the fixed-step simulation and draws a
 * frame every vsync.
 */
class FactoryGame(val platform: Platform, private val dev: DevOptions = DevOptions()) : ApplicationAdapter() {
    lateinit var font: Font
    lateinit var batch: VectorBatch
    lateinit var painter: Painter
    lateinit var ui: Ui
    lateinit var hud: Hud
    val audio = AudioEngine(platform.audioEnabled)
    lateinit var saves: SaveManager
    lateinit var session: Session
    private lateinit var worldRenderer: WorldRenderer
    private var terrain: TerrainRenderer? = null
    var minimap: Minimap? = null
        private set
    private var time = 0f
    private var autosave = 0f
    private var humTimer = 0f
    private var frames = 0

    override fun create() {
        font = Font(platform)
        batch = VectorBatch()
        batch.texture = font.texture
        batch.whiteU = font.whiteU; batch.whiteV = font.whiteV
        painter = Painter()
        ui = Ui(batch, font, painter)
        worldRenderer = WorldRenderer(batch, font, painter)
        saves = SaveManager()
        audio.muted = !saves.soundOn
        hud = Hud(this)

        // Resume the last game if there is one; otherwise start fresh.
        val slot = saves.lastSlot
        val loaded = if (dev.demo || dev.fresh) null else saves.load(slot)
        if (loaded != null) {
            startSession(loaded.first, slot)
            session.cam.x = loaded.second.x; session.cam.y = loaded.second.y; session.cam.zoom = loaded.second.zoom
            session.toast("Welcome back!", 0x7BD389)
        } else {
            val seed = if (dev.seed != 0L) dev.seed else System.nanoTime() and 0xFFFFFF
            startSession(Scenario.newGame(seed), if (dev.demo) 3 else slot)
            if (dev.demo) DemoFactory.build(session.factory)
            if (dev.stress) DemoFactory.stress(session.factory)
            else welcome()
        }
        if (dev.zoom > 0f) session.cam.zoom = dev.zoom
        if (dev.atX != 0f || dev.atY != 0f) { session.cam.x += dev.atX; session.cam.y += dev.atY }
        if (dev.night) session.factory.dayTime = Factory.DAY_LENGTH * 0.02f

        val controls = Controls(this)
        // Tap tolerance scales with screen density so shaky fingers still register taps.
        val slop = 14f * maxOf(1f, Gdx.graphics.density)
        val gestures = GestureDetector(slop, 0.4f, 0.6f, 0.15f, controls)
        Gdx.input.inputProcessor = InputMultiplexer(UiInput(ui), controls.keys, gestures)
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        audio.start()
        applyDevPanel()
    }

    private fun welcome() {
        session.toast("Deliver items to the golden Core to earn building materials", 0xFFB703)
        session.toast("Start: Extract → Mining Drill on ore, then Belts into the Core", 0x8EC5FF)
    }

    private fun startSession(f: Factory, slot: Int) {
        terrain?.dispose(); minimap?.dispose()
        session = Session(this, f, slot)
        session.cam.resize(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        terrain = TerrainRenderer(f.world, batch)
        worldRenderer.terrain = terrain
        minimap = Minimap(f)
        f.events.clear()
    }

    // ---- Save / load (called from the menu) -------------------------------------------

    fun saveTo(slot: Int) {
        if (saves.save(slot, session.factory, session.cam)) {
            session.slot = slot
            session.toast("Saved to slot $slot", 0x7BD389)
            audio.play(Sfx.CLICK)
        } else session.toast("Save failed", 0xFF6B6B)
    }

    fun loadFrom(slot: Int) {
        val r = saves.load(slot)
        if (r == null) { session.toast("Could not load slot $slot", 0xFF6B6B); return }
        startSession(r.first, slot)
        session.cam.x = r.second.x; session.cam.y = r.second.y; session.cam.zoom = r.second.zoom
        saves.lastSlot = slot
        session.toast("Loaded slot $slot", 0x7BD389)
    }

    fun newGame() {
        val slot = session.slot
        startSession(Scenario.newGame(System.nanoTime() and 0xFFFFFF), slot)
        saves.save(slot, session.factory, session.cam)
        welcome()
    }

    /** Back button / Escape. Returns true if something was closed. */
    fun back(): Boolean {
        if (hud.modal != null) { hud.modal = null; return true }
        if (hud.category != null) { hud.category = null; return true }
        if (session.back()) return true
        // Nothing open: leave (Android keeps the save via pause()).
        Gdx.app.exit()
        return true
    }

    // ---- Frame -----------------------------------------------------------------------

    override fun render() {
        val dt = min(Gdx.graphics.deltaTime, 0.1f)
        time += dt
        frames++
        val s = session
        s.update(dt)
        handleEvents(s)
        minimap?.update(dt)
        updateAudio(s, dt)

        autosave += dt
        if (autosave >= AUTOSAVE_SECONDS) {
            autosave = 0f
            if (saves.save(s.slot, s.factory, s.cam)) s.toast("Auto-saved", 0x9C978F)
        }

        Gdx.gl.glClearColor(0.08f, 0.08f, 0.09f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        batch.drawCalls = 0
        worldRenderer.render(s, time)
        hud.draw(s, dt, time)

        if (dev.screenshot != null && frames == dev.frames) {
            val pm = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            val flipped = Pixmap(pm.width, pm.height, pm.format)
            for (y in 0 until pm.height) flipped.drawPixmap(pm, 0, y, pm.width, 1, 0, pm.height - 1 - y, pm.width, 1)
            PixmapIO.writePNG(Gdx.files.absolute(dev.screenshot), flipped)
            pm.dispose(); flipped.dispose()
            Gdx.app.log("dev", "screenshot saved, ${batch.drawCalls} draw calls, ${Gdx.graphics.framesPerSecond} fps, ${s.factory.buildings.size} buildings")
            Gdx.app.exit()
        }
    }

    private fun handleEvents(s: Session) {
        val ev = s.factory.events
        for ((e, msg) in ev) when (e) {
            GameEvent.RESEARCH_DONE -> { s.toast(msg, 0xB388FF); audio.play(Sfx.CHIME) }
            GameEvent.POWER_LOW -> { s.toast(msg, 0xFF9F1C); audio.play(Sfx.POWER_LOW) }
            else -> {}
        }
        ev.clear()
    }

    /** Four times a second, measure what is working near the camera and set hum levels. */
    private fun updateAudio(s: Session, dt: Float) {
        humTimer -= dt
        if (humTimer > 0f) return
        humTimer = 0.25f
        val cam = s.cam
        val counts = IntArray(Hum.entries.size)
        val x0 = cam.left - 6; val x1 = cam.right + 6; val y0 = cam.bottom - 6; val y1 = cam.top + 6
        for (b in s.factory.buildings) {
            if (!b.working || b.cx < x0 || b.cx > x1 || b.cy < y0 || b.cy > y1) continue
            val h = when (b.type) {
                BuildingType.MINING_DRILL -> Hum.DRILL
                BuildingType.STONE_FURNACE, BuildingType.ELECTRIC_FURNACE -> Hum.FURNACE
                BuildingType.BELT, BuildingType.UNDERGROUND, BuildingType.SPLITTER, BuildingType.MERGER -> Hum.BELT
                BuildingType.COAL_GEN -> Hum.POWER
                BuildingType.PUMP_JACK, BuildingType.WATER_PUMP, BuildingType.REFINERY, BuildingType.CHEM_PLANT -> Hum.FLUID
                BuildingType.LAB -> Hum.LAB
                else -> if (b.type.category == Category.PROCESSING || b.type.isInserter) Hum.ASSEMBLER else null
            } ?: continue
            counts[h.ordinal]++
        }
        // Zoomed out = further away = quieter.
        val dist = (0.35f + 0.65f * min(1f, s.cam.zoom)).coerceIn(0f, 1f)
        for (h in Hum.entries) {
            val scale = if (h == Hum.BELT) 30f else 5f
            audio.hums[h.ordinal] = (1f - exp(-counts[h.ordinal] / scale)) * dist
        }
        val w = s.factory.world
        val tx = cam.x.toInt().coerceIn(0, 511); val ty = cam.y.toInt().coerceIn(0, 511)
        audio.biome = w.biome[w.idx(tx, ty)].toInt()
        audio.daylight = s.factory.daylight
    }

    private fun applyDevPanel() {
        when (val p = dev.panel) {
            null -> {}
            "tech" -> hud.modal = Modal.TECH
            "stats" -> hud.modal = Modal.STATS
            "menu" -> hud.modal = Modal.MENU
            "info" -> session.selected = session.factory.buildings.firstOrNull { it.type == BuildingType.ASSEMBLER_1 }
            "place" -> { session.startPlacing(BuildingType.ASSEMBLER_1); session.ghostTX -= 3 }
            else -> if (p.startsWith("build:")) hud.category = Category.valueOf(p.removePrefix("build:"))
        }
    }

    override fun resize(width: Int, height: Int) {
        if (::session.isInitialized) session.cam.resize(width.toFloat(), height.toFloat())
    }

    override fun pause() {
        // Leaving the app: save immediately and silence the synth.
        if (::session.isInitialized && !dev.demo) saves.save(session.slot, session.factory, session.cam)
        audio.stop()
    }

    override fun resume() { audio.start() }

    override fun dispose() {
        audio.dispose()
        terrain?.dispose(); minimap?.dispose()
        batch.dispose(); font.dispose()
    }

    companion object {
        const val AUTOSAVE_SECONDS = 300f
    }
}
