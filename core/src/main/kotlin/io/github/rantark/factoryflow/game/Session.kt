package io.github.rantark.factoryflow.game

import io.github.rantark.factoryflow.audio.Sfx
import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.sim.Belt
import io.github.rantark.factoryflow.sim.Building
import io.github.rantark.factoryflow.sim.Dir
import io.github.rantark.factoryflow.sim.Factory
import io.github.rantark.factoryflow.sim.GameEvent
import io.github.rantark.factoryflow.sim.UndoOp
import kotlin.math.abs
import kotlin.math.sign

enum class Mode { NORMAL, PLACE, BULLDOZE }

/** A planned placement shown as a ghost. [problem] is null when it can be built. */
class Ghost(val type: BuildingType, val x: Int, val y: Int, val dir: Int, val problem: String?, val reorient: Boolean = false)

/** A short message shown at the top of the screen. */
class Toast(val text: String, val color: Int, var life: Float = 3.5f)

/**
 * The player's current game: the factory, the camera and everything about how the
 * player is interacting with it (placement ghosts, selection, speed, save slot).
 */
class Session(val game: FactoryGame, var factory: Factory, var slot: Int) {
    val cam = GameCamera()
    var mode = Mode.NORMAL
    var buildType: BuildingType? = null
    var buildDir = 0
    /** Tile the single-placement ghost is centred on. */
    var ghostTX = 0
    var ghostTY = 0
    /** Ghosts of a line being painted by dragging (belts, pipes, poles). */
    var line: List<Ghost>? = null
    var selected: Building? = null
    var speed = 1
    val toasts = ArrayList<Toast>()
    private var acc = 0f

    fun toast(msg: String, color: Int = 0xF1EADF) {
        toasts.removeAll { it.text == msg }
        toasts.add(Toast(msg, color))
        while (toasts.size > 3) toasts.removeAt(0)
    }

    // ---- Simulation ---------------------------------------------------------------

    fun update(dt: Float) {
        cam.update(dt)
        val f = factory
        // Only the area around the viewport runs at full rate.
        f.activeX0 = cam.left.toInt(); f.activeX1 = cam.right.toInt()
        f.activeY0 = cam.bottom.toInt(); f.activeY1 = cam.top.toInt()
        f.world.explore(cam.left.toInt() - 4, cam.bottom.toInt() - 4, cam.right.toInt() + 4, cam.top.toInt() + 4)
        acc += dt * speed
        var steps = 0
        while (acc >= STEP && steps < MAX_STEPS * speed) { f.tick(STEP); acc -= STEP; steps++ }
        if (acc > STEP * 4) acc = 0f // don't spiral if the device can't keep up
        val sel = selected
        if (sel != null && f.at(sel.x, sel.y) !== sel) selected = null
        toasts.forEach { it.life -= dt }
        toasts.removeAll { it.life <= 0f }
    }

    // ---- Placement ----------------------------------------------------------------

    fun startPlacing(type: BuildingType) {
        mode = Mode.PLACE
        buildType = type
        selected = null
        line = null
        ghostTX = cam.x.toInt(); ghostTY = cam.y.toInt()
        game.audio.play(Sfx.CLICK)
        if (type.lineBuild) toast("Tap to move the ghost, tap it again to build, or drag from it to paint a line", 0x8EC5FF)
        else toast("Tap to move the ghost, tap it again to build", 0x8EC5FF)
    }

    fun cancelPlacing() {
        mode = Mode.NORMAL; buildType = null; line = null
    }

    fun rotateGhost() {
        buildDir = (buildDir + 3) and 3
        game.audio.play(Sfx.CLICK)
    }

    /** The ghosts to draw right now. */
    fun ghosts(): List<Ghost> {
        val t = buildType ?: return emptyList()
        line?.let { return it }
        val x = factory.anchorX(t, ghostTX); val y = factory.anchorY(t, ghostTY)
        return listOf(Ghost(t, x, y, buildDir, factory.placementProblem(t, x, y)))
    }

    fun ghostContains(tx: Int, ty: Int): Boolean {
        val t = buildType ?: return false
        val x = factory.anchorX(t, ghostTX); val y = factory.anchorY(t, ghostTY)
        // Be generous with the hit area on small buildings: fingers are big.
        val pad = if (t.size == 1) 1 else 0
        return tx >= x - pad && ty >= y - pad && tx < x + t.size + pad && ty < y + t.size + pad
    }

    /** Build the single ghost. */
    fun confirmGhost() {
        val g = ghosts().firstOrNull() ?: return
        if (g.problem != null) { denied(g.problem); return }
        val b = factory.place(g.type, g.x, g.y, g.dir) ?: return
        factory.undo.record(listOf(UndoOp(true, b.type, b.x, b.y, b.dir, null)))
        game.audio.play(Sfx.PLACE)
        // Belts: advance the ghost so repeated taps extend the line.
        if (g.type.lineBuild && g.type.rotatable) { ghostTX += Dir.DX[buildDir]; ghostTY += Dir.DY[buildDir] }
        if (g.type == BuildingType.UNDERGROUND && b is io.github.rantark.factoryflow.sim.UndergroundBelt && !b.isExit) {
            toast("Now place the exit up to 5 tiles ahead", 0x8EC5FF)
        }
    }

    private fun denied(problem: String) {
        toast(problem, 0xFF6B6B)
        game.audio.play(Sfx.DENIED)
    }

    /**
     * Plan an L-shaped line from tile (sx, sy) to (ex, ey): first along the longer axis,
     * then the shorter one. Belts face along the path; poles are spaced to stay wired.
     */
    fun planLine(sx: Int, sy: Int, ex: Int, ey: Int): List<Ghost> {
        val t = buildType ?: return emptyList()
        val tiles = ArrayList<IntArray>() // x, y, dir
        val dxT = ex - sx; val dyT = ey - sy
        val horizFirst = abs(dxT) >= abs(dyT)
        var x = sx; var y = sy
        val d1 = if (horizFirst) (if (dxT >= 0) 0 else 2) else (if (dyT >= 0) 1 else 3)
        val d2 = if (horizFirst) (if (dyT >= 0) 1 else 3) else (if (dxT >= 0) 0 else 2)
        val n1 = if (horizFirst) abs(dxT) else abs(dyT)
        val n2 = if (horizFirst) abs(dyT) else abs(dxT)
        if (n1 == 0 && n2 == 0) return listOf(Ghost(t, sx, sy, buildDir, factory.placementProblem(t, sx, sy)))
        for (k in 0 until n1) {
            tiles.add(intArrayOf(x, y, d1))
            if (horizFirst) x += dxT.sign else y += dyT.sign
        }
        for (k in 0..n2) {
            tiles.add(intArrayOf(x, y, if (n2 > 0) d2 else d1))
            if (k < n2) { if (horizFirst) y += dyT.sign else x += dxT.sign }
        }
        if (t.rotatable) buildDir = tiles.last()[2]
        val spacing = when (t) { BuildingType.POLE -> 7; BuildingType.BIG_POLE -> 16; else -> 1 }
        // Validate with a running tally of what we can afford.
        val budget = factory.stock.copyOf()
        val out = ArrayList<Ghost>()
        for ((i, p) in tiles.withIndex()) {
            if (spacing > 1 && i % spacing != 0 && i != tiles.size - 1) continue
            val existing = factory.at(p[0], p[1])
            if (existing is Belt && existing.type == t && t.rotatable) {
                out.add(Ghost(t, p[0], p[1], p[2], null, reorient = existing.dir != p[2])); continue
            }
            var problem = factory.placementProblem(t, p[0], p[1], checkCost = false)
            if (problem == null) {
                val short = t.cost.firstOrNull { budget[it.item.id] < it.count }
                if (short != null) problem = "Need ${short.count} ${short.item.title}"
                else t.cost.forEach { budget[it.item.id] -= it.count }
            }
            out.add(Ghost(t, p[0], p[1], p[2], problem))
        }
        return out
    }

    fun commitLine() {
        val l = line ?: return
        line = null
        val ops = ArrayList<UndoOp>()
        var skipped: String? = null
        for (g in l) {
            if (g.reorient) {
                val b = factory.at(g.x, g.y)
                if (b != null && b.dir != g.dir) {
                    ops.add(UndoOp(false, b.type, b.x, b.y, b.dir, b.config()))
                    factory.remove(b)
                    factory.place(g.type, g.x, g.y, g.dir)?.let { ops.add(UndoOp(true, it.type, it.x, it.y, it.dir, null)) }
                }
                continue
            }
            if (g.problem != null) { skipped = g.problem; continue }
            factory.place(g.type, g.x, g.y, g.dir)?.let { ops.add(UndoOp(true, it.type, it.x, it.y, it.dir, null)) }
        }
        factory.undo.record(ops)
        if (ops.isNotEmpty()) game.audio.play(Sfx.PLACE)
        if (skipped != null && skipped != "Blocked") denied(skipped)
        l.lastOrNull()?.let {
            ghostTX = it.x + if (it.type.rotatable) Dir.DX[it.dir] else 0
            ghostTY = it.y + if (it.type.rotatable) Dir.DY[it.dir] else 0
        }
    }

    // ---- World taps ------------------------------------------------------------------

    fun tapWorld(tx: Int, ty: Int) {
        when (mode) {
            Mode.PLACE -> {
                if (ghostContains(tx, ty)) confirmGhost()
                else { ghostTX = tx; ghostTY = ty }
            }
            Mode.BULLDOZE -> bulldoze(tx, ty)
            Mode.NORMAL -> {
                val b = factory.at(tx, ty)
                if (b != null && b !== selected) game.audio.play(Sfx.CLICK)
                selected = if (b === selected) null else b
            }
        }
    }

    fun bulldoze(tx: Int, ty: Int) {
        val b = factory.at(tx, ty) ?: return
        if (b.type == BuildingType.CORE) { denied("The Core can't be removed"); return }
        factory.undo.record(listOf(UndoOp(false, b.type, b.x, b.y, b.dir, b.config())))
        factory.remove(b)
        if (selected === b) selected = null
        game.audio.play(Sfx.REMOVE)
    }

    fun toggleBulldoze() {
        mode = if (mode == Mode.BULLDOZE) Mode.NORMAL else Mode.BULLDOZE
        buildType = null; line = null; selected = null
        game.audio.play(Sfx.CLICK)
        if (mode == Mode.BULLDOZE) toast("Bulldozer on: tap buildings to remove them (materials are refunded)", 0xFFC145)
    }

    fun undo() {
        val msg = factory.undo.undo()
        if (msg == null) { toast("Nothing to undo", 0x9C978F); return }
        factory.emit(GameEvent.UNDO)
        game.audio.play(Sfx.UNDO)
        toast(msg)
    }

    fun cycleSpeed() {
        speed = when (speed) { 1 -> 2; 2 -> 4; else -> 1 }
        game.audio.play(Sfx.CLICK)
    }

    /** Android back / Escape: close the innermost thing. */
    fun back(): Boolean {
        return when {
            line != null -> { line = null; true }
            mode != Mode.NORMAL -> { mode = Mode.NORMAL; buildType = null; true }
            selected != null -> { selected = null; true }
            else -> false
        }
    }

    companion object {
        const val STEP = 1f / 60f
        const val MAX_STEPS = 4
    }
}
