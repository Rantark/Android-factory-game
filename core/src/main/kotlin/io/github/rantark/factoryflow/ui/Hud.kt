package io.github.rantark.factoryflow.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Category
import io.github.rantark.factoryflow.data.Fluid
import io.github.rantark.factoryflow.data.Item
import io.github.rantark.factoryflow.data.MachineClass
import io.github.rantark.factoryflow.data.Recipes
import io.github.rantark.factoryflow.data.Tech
import io.github.rantark.factoryflow.data.Techs
import io.github.rantark.factoryflow.game.FactoryGame
import io.github.rantark.factoryflow.game.Mode
import io.github.rantark.factoryflow.game.Session
import io.github.rantark.factoryflow.gfx.Col
import io.github.rantark.factoryflow.sim.Accumulator
import io.github.rantark.factoryflow.sim.Belt
import io.github.rantark.factoryflow.sim.Building
import io.github.rantark.factoryflow.sim.Chest
import io.github.rantark.factoryflow.sim.CoalGenerator
import io.github.rantark.factoryflow.sim.Core
import io.github.rantark.factoryflow.sim.Crafter
import io.github.rantark.factoryflow.sim.Drill
import io.github.rantark.factoryflow.sim.Factory
import io.github.rantark.factoryflow.sim.FluidNode
import io.github.rantark.factoryflow.sim.Inserter
import io.github.rantark.factoryflow.sim.Lab
import io.github.rantark.factoryflow.sim.Pump
import io.github.rantark.factoryflow.sim.SolarPanel
import io.github.rantark.factoryflow.sim.Stats
import io.github.rantark.factoryflow.sim.UndergroundBelt
import io.github.rantark.factoryflow.world.Resource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Modal { TECH, STATS, MENU }

/**
 * The heads-up display. Layout (landscape, in UI units – the screen is 360 units tall):
 * stock + research chips top-left, speed/tech/stats/menu buttons and the minimap
 * top-right, category tabs + bulldoze + undo along the bottom, and slide-up sheets
 * for building, inspecting and placing. Tech tree, statistics and saves are modals.
 */
class Hud(private val game: FactoryGame) {
    private val ui get() = game.ui
    private val b get() = game.batch
    private val painter get() = game.painter

    var category: Category? = null
    var modal: Modal? = null
    private var statsTab = 0
    private var techFocus: Tech? = null
    private var confirmNew = 0f
    private var lastSelected: Building? = null
    private val proj = Matrix4()
    /** Slide-in animation for bottom sheets (0 hidden → 1 shown). */
    private var sheet = 0f
    private var sheetKey: Any? = null

    private val u get() = ui.u
    private val M get() = 6f * u

    fun draw(s: Session, dt: Float, time: Float) {
        val w = Gdx.graphics.width.toFloat(); val h = Gdx.graphics.height.toFloat()
        ui.beginFrame(w, h)
        proj.setToOrtho2D(0f, 0f, w, h)
        b.begin(proj, 1f)
        painter.s = b; painter.time = time; painter.factory = null; painter.lowDetail = false

        ui.dismissed?.let { id ->
            when (id) { "build" -> category = null; "info" -> s.selected = null }
            ui.dismissed = null
        }
        if (s.selected != null && s.selected !== lastSelected) category = null
        lastSelected = s.selected
        confirmNew = max(0f, confirmNew - dt)

        val key: Any? = when {
            modal != null -> null
            s.mode == Mode.PLACE -> "place"
            s.selected != null -> s.selected
            category != null -> category
            else -> null
        }
        if (key != sheetKey) { sheet = 0f; sheetKey = key }
        sheet = min(1f, sheet + dt * 7f)

        val m = modal
        if (m != null) {
            when (m) { Modal.TECH -> techModal(s); Modal.STATS -> statsModal(s); Modal.MENU -> menuModal(s) }
        } else {
            topLeft(s, w, h, time)
            topRight(s, w, h)
            bottomBar(s, w)
            val slide = (1f - ease(sheet)) * 40f * u
            when {
                s.mode == Mode.PLACE -> placeBar(s, w, slide)
                s.mode == Mode.BULLDOZE -> bulldozeBar(w)
                s.selected != null -> infoPanel(s, s.selected!!, w, slide)
                category != null -> buildPanel(s, category!!, w, slide)
            }
        }
        if (m == null) toasts(s, w, h, if (key != null) 1 else 3)
        b.end()
        ui.endFrame()
    }

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    // =================================================================================
    // Top-left: stock chips, clock, power and research progress
    // =================================================================================

    private val chipItems = listOf(Item.IRON_PLATE, Item.COPPER_PLATE, Item.GEAR, Item.CIRCUIT, Item.STEEL, Item.STONE_BRICK, Item.STONE)

    private fun topLeft(s: Session, w: Float, h: Float, time: Float) {
        val f = s.factory
        val chipW = 62f * u; val chipH = 28f * u
        val y = h - M - chipH
        var x = M
        val maxX = w - M - 4 * 52f * u - 8f * u
        // Clock + day/night.
        val clockW = 84f * u
        chip(x, y, clockW, chipH)
        ui.iconSun(x + 14f * u, y + chipH / 2, 22f * u, f.daylight)
        val hours = (f.dayTime / Factory.DAY_LENGTH * 24f)
        val hh = hours.toInt(); val mm = ((hours - hh) * 60).toInt()
        ui.text(String.format(Locale.US, "%02d:%02d", hh, mm), x + 30f * u, y + 9f * u, 11f)
        x += clockW + 4f * u
        // Power balance.
        val powW = 118f * u
        chip(x, y, powW, chipH)
        // Worst satisfaction across grids decides the colour.
        val sat = f.power.nets.filter { it.demand > 1f }.minOfOrNull { it.satisfaction } ?: 1f
        val pc = when { sat >= 0.99f -> Theme.GOOD; sat > 0.6f -> Theme.WARN; else -> Theme.BAD }
        painter.powerIcon(x + 12f * u, y + chipH / 2, 9f * u, pc)
        ui.text("${fmtKw(f.stats.useNow)} / ${fmtKw(f.stats.capNow)}", x + 24f * u, y + 9.5f * u, 8.5f)
        if (ui.tapped(x, y, powW, chipH)) { modal = Modal.STATS; statsTab = 1 }
        x += powW + 4f * u
        for (it in chipItems) {
            if (x + chipW > maxX) break
            val n = f.stock[it.id]
            if (n <= 0 && it.ordinal > Item.CIRCUIT.ordinal) continue
            chip(x, y, chipW, chipH)
            painter.item(it, x + 13f * u, y + chipH / 2, 8f * u)
            ui.text(fmt(n.toFloat()), x + 25f * u, y + 9f * u, 11f, if (n == 0) Theme.DIM else Theme.TEXT)
            if (ui.tapped(x, y, chipW, chipH)) { modal = Modal.STATS; statsTab = 0 }
            x += chipW + 4f * u
        }
        // Research progress chip.
        val ry = y - chipH - 5f * u
        val rw = 250f * u
        chip(M, ry, rw, chipH)
        ui.iconFlask(M + 14f * u, ry + chipH / 2, 22f * u)
        val cur = f.research.current
        if (cur != null) {
            val p = f.research.progress[cur.index].toFloat() / cur.units
            ui.text(cur.title, M + 28f * u, ry + 14f * u, 9.5f)
            ui.progressBar(M + 28f * u, ry + 5f * u, rw - 36f * u, 5f * u, p, 0xB388FF)
            ui.text("${(p * 100).toInt()}%", M + rw - 8f * u, ry + 14f * u, 8.5f, Theme.DIM, 1)
        } else ui.text("Research complete!", M + 28f * u, ry + 10f * u, 10f, Theme.GOOD)
        if (ui.tapped(M, ry, rw, chipH)) { modal = Modal.TECH; game.audio.play(io.github.rantark.factoryflow.audio.Sfx.CLICK) }
    }

    private fun chip(x: Float, y: Float, w: Float, h: Float) {
        b.roundRect(x, y, w, h, h / 2, Col.pack(Theme.PANEL, 0.85f), Col.pack(Col.lighten(Theme.PANEL, 0.08f), 0.85f))
        ui.block(x, y, w, h)
    }

    // =================================================================================
    // Top-right: speed, research, statistics, menu, minimap
    // =================================================================================

    private fun topRight(s: Session, w: Float, h: Float) {
        val bs = 46f * u
        var x = w - M - bs
        val y = h - M - bs
        if (ui.button(x, y, bs, bs, "Menu", icon = { cx, cy, sz -> ui.iconMenu(cx, cy, sz) })) { modal = Modal.MENU; click() }
        x -= bs + 6f * u
        if (ui.button(x, y, bs, bs, "Stats", icon = { cx, cy, sz -> ui.iconStats(cx, cy, sz) })) { modal = Modal.STATS; click() }
        x -= bs + 6f * u
        if (ui.button(x, y, bs, bs, "Tech", icon = { cx, cy, sz -> ui.iconFlask(cx, cy, sz) })) { modal = Modal.TECH; techFocus = null; click() }
        x -= bs + 6f * u
        if (ui.button(x, y, bs, bs, "${s.speed}x", selected = s.speed > 1, icon = { cx, cy, sz -> ui.iconSpeed(cx, cy, sz, s.speed) })) s.cycleSpeed()

        // Minimap.
        val ms = 100f * u
        val mx = w - M - ms; val my = y - 6f * u - ms
        ui.panel(mx - 3f * u, my - 3f * u, ms + 6f * u, ms + 6f * u, 0.9f, 8f)
        // Show a window of the world map centred on the camera.
        val cam = s.cam
        val world = io.github.rantark.factoryflow.world.World.W.toFloat()
        val span = MINIMAP_SPAN
        val wx0 = (cam.x - span / 2).coerceIn(0f, world - span)
        val wy0 = (cam.y - span / 2).coerceIn(0f, world - span)
        val tex = game.minimap?.texture
        if (tex != null) b.withTexture(tex) {
            b.texQuad(mx, my, ms, ms, wx0 / world, 1f - (wy0 + span) / world, (wx0 + span) / world, 1f - wy0 / world, Col.pack(0xFFFFFF))
        }
        val sc = ms / span
        val vx = mx + (cam.left - wx0) * sc; val vy = my + (cam.bottom - wy0) * sc
        val vw = (cam.right - cam.left) * sc; val vh = (cam.top - cam.bottom) * sc
        ui.clip(mx, my, ms, ms) {
            b.roundRectOutline(vx, vy, max(vw, 3f * u), max(vh, 3f * u), 1f * u, 1.3f * u, Col.pack(0xFFFFFF, 0.9f))
            val core = s.factory.core
            b.circle(mx + (core.cx - wx0) * sc, my + (core.cy - wy0) * sc, 2.5f * u, Col.pack(Theme.ACCENT))
        }
        if (ui.tapped(mx, my, ms, ms)) {
            // Jump the camera to the tapped spot.
            cam.x = (wx0 + (ui.lastTapX - mx) / sc).coerceIn(0f, world)
            cam.y = (wy0 + (ui.lastTapY - my) / sc).coerceIn(0f, world)
            cam.velX = 0f; cam.velY = 0f
        }
    }

    // =================================================================================
    // Bottom bar: categories, bulldoze, undo
    // =================================================================================

    private val categoryIcon = mapOf(
        Category.EXTRACTION to BuildingType.MINING_DRILL, Category.TRANSPORT to BuildingType.BELT,
        Category.POWER to BuildingType.POLE, Category.PROCESSING to BuildingType.ASSEMBLER_1,
        Category.STORAGE to BuildingType.CHEST_1, Category.RESEARCH to BuildingType.LAB,
    )

    private fun bottomBar(s: Session, w: Float) {
        val bh = 60f * u
        val bw = w - 2 * M
        ui.panel(M, M, bw, bh, 0.9f)
        val side = 58f * u
        val gap = 4f * u
        val tabW = min(74f * u, (bw - 2 * side - 3 * gap - 2 * gap - 7 * gap) / 6f)
        var x = M + gap
        for (c in Category.entries) {
            val sel = category == c && s.mode == Mode.NORMAL
            val icon = categoryIcon.getValue(c)
            if (ui.button(x, M + gap, tabW, bh - 2 * gap, c.title, selected = sel, accent = c.color,
                    icon = { cx, cy, sz -> painter.buildingIcon(icon, cx, cy, sz * 0.95f, if (icon == BuildingType.BELT) 0 else 3) })) {
                if (s.mode != Mode.NORMAL) { s.cancelPlacing(); s.mode = Mode.NORMAL }
                category = if (category == c) null else c
                s.selected = null
                ui.resetScroll("build")
                click()
            }
            x += tabW + gap
        }
        val rx = M + bw - gap - side
        if (ui.button(rx, M + gap, side, bh - 2 * gap, "Undo", enabled = s.factory.undo.size > 0,
                icon = { cx, cy, sz -> ui.iconUndo(cx, cy, sz) })) s.undo()
        if (s.factory.undo.size > 0) {
            b.circle(rx + side - 9f * u, M + bh - 13f * u, 7f * u, Col.pack(Theme.ACCENT))
            ui.text("${s.factory.undo.size}", rx + side - 9f * u, M + bh - 16.5f * u, 7f, 0x1D2026, 0)
        }
        if (ui.button(rx - side - gap, M + gap, side, bh - 2 * gap, "Remove", selected = s.mode == Mode.BULLDOZE, accent = Theme.BAD,
                icon = { cx, cy, sz -> ui.iconBulldoze(cx, cy, sz, if (s.mode == Mode.BULLDOZE) Theme.BAD else Theme.TEXT) })) {
            category = null
            s.toggleBulldoze()
        }
    }

    // =================================================================================
    // Build panel
    // =================================================================================

    private fun buildPanel(s: Session, c: Category, w: Float, slide: Float) {
        val ph = 112f * u
        val py = M + 66f * u - slide
        val pw = w - 2 * M
        ui.panel(M, py, pw, ph)
        ui.text(c.title, M + 12f * u, py + ph - 18f * u, 12f, c.color)
        ui.text("Swipe down to close", M + pw - 50f * u, py + ph - 17f * u, 8f, Theme.DIM, 1)
        if (ui.button(M + pw - 40f * u, py + ph - 30f * u, 34f * u, 26f * u, icon = { cx, cy, sz -> ui.iconCross(cx, cy, sz * 0.7f) })) { category = null; click() }
        val types = BuildingType.inCategory(c)
        val cardW = 96f * u; val cardH = 80f * u; val gap = 6f * u
        val areaX = M + 8f * u; val areaY = py + 6f * u; val areaW = pw - 16f * u
        val sc = ui.scroll("build", areaX, areaY, areaW, cardH, types.size * (cardW + gap), cardH, horizontal = true, dismissible = true)
        val f = s.factory
        ui.clip(areaX, areaY, areaW, cardH + 2f * u) {
            var x = areaX - sc.offX
            for (t in types) {
                val unlocked = f.research.buildingUnlocked(t)
                val affordable = t.cost.all { f.stock[it.item.id] >= it.count }
                b.roundRect(x, areaY, cardW, cardH, 10f * u, Col.pack(Col.darken(Theme.BUTTON, 0.1f)), Col.pack(Theme.BUTTON_HI))
                val a = if (unlocked) 1f else 0.35f
                painter.buildingIcon(t, x + cardW / 2, areaY + cardH - 21f * u, 32f * u)
                if (!unlocked) b.roundRect(x, areaY, cardW, cardH, 10f * u, Col.pack(0x000000, 0.5f))
                val lines = ui.font.wrap(t.title, 8.5f * u, cardW - 8f * u)
                for ((i, ln) in lines.take(2).withIndex()) ui.text(ln, x + cardW / 2, areaY + cardH - 48f * u - i * 10f * u, 8.5f, Theme.TEXT, 0, a)
                if (!unlocked) {
                    ui.text("Needs research", x + cardW / 2, areaY + 5f * u, 7.5f, 0xB388FF, 0)
                } else {
                    // Cost row.
                    var cx = x + 5f * u
                    for (st in t.cost) {
                        val ok = f.stock[st.item.id] >= st.count
                        painter.item(st.item, cx + 5f * u, areaY + 9f * u, 4.5f * u)
                        val txt = "${st.count}"
                        ui.text(txt, cx + 11f * u, areaY + 5.5f * u, 7.5f, if (ok) Theme.TEXT else Theme.BAD)
                        cx += 15f * u + ui.textW(txt, 7.5f)
                    }
                    if (!affordable) b.roundRectOutline(x, areaY, cardW, cardH, 10f * u, 1.2f * u, Col.pack(Theme.BAD, 0.5f))
                }
                if (ui.tapped(max(x, areaX), areaY, cardW, cardH)) {
                    if (unlocked) { category = null; s.startPlacing(t) }
                    else s.toast("${t.title}: unlock it in the tech tree", 0xB388FF)
                }
                x += cardW + gap
            }
        }
        ui.block(M, py, pw, ph)
    }

    // =================================================================================
    // Placement & bulldoze bars
    // =================================================================================

    private fun placeBar(s: Session, w: Float, slide: Float) {
        val t = s.buildType ?: return
        val bw = min(470f * u, w - 2 * M)
        val bh = 58f * u
        val x = (w - bw) / 2; val y = M + 66f * u - slide
        ui.panel(x, y, bw, bh)
        painter.buildingIcon(t, x + 30f * u, y + bh / 2, 40f * u, s.buildDir)
        ui.text(t.title, x + 58f * u, y + bh - 20f * u, 11.5f)
        val g = s.ghosts().firstOrNull()
        val problem = g?.problem
        if (problem != null) ui.text(problem, x + 58f * u, y + 12f * u, 9f, Theme.BAD)
        else {
            var cx = x + 58f * u
            for (st in t.cost) {
                painter.item(st.item, cx + 5f * u, y + 16f * u, 5f * u)
                ui.text("${st.count}", cx + 12f * u, y + 12f * u, 8.5f)
                cx += 22f * u + ui.textW("${st.count}", 8.5f)
            }
        }
        val bs = 50f * u
        var bx = x + bw - bs - 5f * u
        if (ui.button(bx, y + 4f * u, bs, bh - 8f * u, "Done", icon = { cx, cy, sz -> ui.iconCross(cx, cy, sz * 0.8f) })) { s.cancelPlacing(); click() }
        bx -= bs + 5f * u
        if (ui.button(bx, y + 4f * u, bs, bh - 8f * u, "Build", enabled = problem == null,
                icon = { cx, cy, sz -> ui.iconCheck(cx, cy, sz * 0.9f) })) s.confirmGhost()
        if (t.rotatable) {
            bx -= bs + 5f * u
            if (ui.button(bx, y + 4f * u, bs, bh - 8f * u, "Rotate", icon = { cx, cy, sz -> ui.iconRotate(cx, cy, sz) })) s.rotateGhost()
        }
    }

    private fun bulldozeBar(w: Float) {
        val bw = min(360f * u, w - 2 * M); val bh = 40f * u
        val x = (w - bw) / 2; val y = M + 66f * u
        ui.panel(x, y, bw, bh)
        ui.iconBulldoze(x + 22f * u, y + bh / 2, 26f * u, Theme.BAD)
        ui.text("Tap buildings to remove them", x + 42f * u, y + 15f * u, 10.5f)
        if (ui.button(x + bw - 70f * u, y + 5f * u, 64f * u, bh - 10f * u, "Done")) game.session.toggleBulldoze()
    }

    // =================================================================================
    // Info panel for the selected building
    // =================================================================================

    private fun infoPanel(s: Session, bld: Building, w: Float, slide: Float) {
        val f = s.factory
        val pw = min(600f * u, w - 2 * M - 112f * u)
        val ph = 180f * u
        val px = M; val py = M + 66f * u - slide
        ui.panel(px, py, pw, ph)
        ui.scroll("info", px, py, pw, ph, pw, ph, dismissible = true)
        // Header.
        painter.buildingIcon(bld.type, px + 28f * u, py + ph - 28f * u, 42f * u, bld.dir)
        ui.text(bld.type.title, px + 56f * u, py + ph - 22f * u, 13f)
        val st = bld.status
        b.circle(px + 60f * u, py + ph - 36f * u, 4f * u, Col.pack(st.color))
        ui.text(statusText(bld), px + 69f * u, py + ph - 40f * u, 9.5f, st.color)
        // Header buttons.
        val bs = 42f * u
        var bx = px + pw - bs - 6f * u
        val by = py + ph - bs - 6f * u
        if (ui.button(bx, by, bs, bs, icon = { cx, cy, sz -> ui.iconCross(cx, cy, sz * 0.8f) })) { s.selected = null; click() }
        if (bld.type != BuildingType.CORE) {
            bx -= bs + 5f * u
            if (ui.button(bx, by, bs, bs, icon = { cx, cy, sz -> ui.iconBulldoze(cx, cy, sz, Theme.BAD) })) {
                s.bulldoze(bld.x, bld.y)
            }
        }
        if (bld.type.rotatable) {
            bx -= bs + 5f * u
            if (ui.button(bx, by, bs, bs, icon = { cx, cy, sz -> ui.iconRotate(cx, cy, sz) })) { f.rotate(bld); click() }
        }
        // Body: details on the left, inventory/recipes on the right.
        val lines = ArrayList<Pair<String, Int>>()
        details(f, bld, lines)
        var ly = py + ph - 64f * u
        val colW = pw * 0.44f - 16f * u
        for ((t, c) in lines) for (ln in ui.font.wrap(t, 9f * u, colW)) {
            if (ly < py + 6f * u) break
            ui.text(ln, px + 12f * u, ly, 9f, c); ly -= 13f * u
        }
        val rx = px + pw * 0.46f
        val rw = pw - (rx - px) - 10f * u
        var ry = py + ph - 66f * u
        when (bld) {
            is Crafter -> {
                ui.text(if (bld.mclass == MachineClass.SMELTER) "Recipe (auto picks from input)" else "Choose recipe", rx, ry, 9f, Theme.DIM)
                recipeRow(s, bld, rx, ry - 42f * u, rw)
                ry -= 52f * u
                bld.recipe?.let { r ->
                    val cx = rx
                    ui.progressBar(cx, ry, rw, 6f * u, bld.progress, Theme.GOOD)
                    ry -= 8f * u
                    var ix = cx
                    ui.text("Needs:", ix, ry - 10f * u, 8.5f, Theme.DIM); ix += ui.textW("Needs:", 8.5f) + 4f * u
                    for (st2 in r.inputs) { ix = itemCount(st2.item, "${bld.inv[st2.item.id]}/${st2.count}", ix, ry - 6f * u) }
                    for (fs in r.fluidIn) { ix = fluidCount(fs.fluid, "${bld.fin[fs.fluid.ordinal].toInt()}/${fs.amount.toInt()}", ix, ry - 6f * u) }
                    ry -= 20f * u
                    ix = cx
                    ui.text("Makes:", ix, ry - 10f * u, 8.5f, Theme.DIM); ix += ui.textW("Makes:", 8.5f) + 4f * u
                    for (st2 in r.outputs) { ix = itemCount(st2.item, "×${st2.count} (${bld.out[st2.item.id]})", ix, ry - 6f * u) }
                    for (fs in r.fluidOut) { ix = fluidCount(fs.fluid, "×${fs.amount.toInt()}", ix, ry - 6f * u) }
                }
            }
            is Inserter -> if (bld.type == BuildingType.FILTER_INSERTER) {
                ui.text("Filter: only move", rx, ry, 9f, Theme.DIM)
                itemPicker(bld, rx, ry - 42f * u, rw)
            } else inventoryGrid(bld, rx, ry, rw)
            else -> inventoryGrid(bld, rx, ry, rw)
        }
    }

    private fun statusText(bld: Building): String = when {
        bld is Crafter && bld.status == io.github.rantark.factoryflow.sim.Status.WORKING -> "Working · ${(bld.progress * 100).toInt()}%"
        else -> bld.status.label
    }

    private fun itemCount(it: Item, label: String, x: Float, y: Float): Float {
        painter.item(it, x + 6f * u, y, 6f * u)
        ui.text(label, x + 14f * u, y - 4f * u, 8.5f)
        return x + 22f * u + ui.textW(label, 8.5f)
    }

    private fun fluidCount(fl: Fluid, label: String, x: Float, y: Float): Float {
        painter.fluidDrop(fl, x + 6f * u, y, 6f * u)
        ui.text(label, x + 14f * u, y - 4f * u, 8.5f)
        return x + 22f * u + ui.textW(label, 8.5f)
    }

    private fun details(f: Factory, bld: Building, out: MutableList<Pair<String, Int>>) {
        if (bld.type.power > 0f) {
            val pct = (bld.satisfaction * 100).toInt()
            out.add((if (bld.powerNet < 0) "Not connected to power – build a pole nearby" else "Power: ${bld.type.power.toInt()} kW · $pct% supplied") to
                (if (bld.powerNet < 0 || pct < 50) Theme.BAD else Theme.TEXT))
        }
        when (bld) {
            is Crafter -> {
                val r = bld.recipe
                if (r != null) {
                    val perMin = 60f * bld.speed / r.time
                    val main = r.outputs.firstOrNull()
                    if (main != null) out.add("Output: ${fmt(perMin * main.count)} ${main.item.title}/min" to Theme.TEXT)
                    else r.fluidOut.firstOrNull()?.let { out.add("Output: ${fmt(perMin * it.amount)} ${it.fluid.title}/min" to Theme.TEXT) }
                    out.add("Speed ×${fmt(bld.speed)} · ${bld.crafts} crafted" to Theme.DIM)
                }
                if (bld.usesFuel) out.add("Fuel: ${bld.fuelItems} coal" to if (bld.fuelItems == 0 && bld.fuel <= 0f) Theme.BAD else Theme.TEXT)
                if (bld.fout.any { it > 0f } || (r?.fluidOut?.isNotEmpty() == true)) out.add("Fluids leave through adjacent pipes" to Theme.DIM)
            }
            is Drill -> {
                out.add("Mining: ${bld.mainRes.title.ifEmpty { "nothing" }}" to Theme.TEXT)
                out.add("Rate: ${fmt(60f * Drill.BASE_RATE * f.miningBonus * (if (bld.mainRes == Resource.CRYSTAL) 0.5f else 1f))}/min" to Theme.TEXT)
                out.add("Ore left under drill: ${fmt(bld.oreLeft.toFloat())}" to Theme.DIM)
                depositLine(f, bld, out)
            }
            is Pump -> {
                out.add("Pumping: ${fmt(bld.rateNow * 60f)}/min" to Theme.TEXT)
                out.add("Connect pipes to any side" to Theme.DIM)
            }
            is Belt -> {
                out.add("Speed: ${fmt(f.beltSpeed)} tiles/s · ${fmt(f.beltSpeed * 4 * 60)} items/min" to Theme.TEXT)
                out.add("Items on tile: ${bld.count}" to Theme.DIM)
                if (bld is UndergroundBelt) out.add((if (bld.isExit) "Exit" else if (bld.partner != null) "Entrance (paired)" else "Entrance – place an exit ahead") to Theme.INFO)
            }
            is Inserter -> {
                out.add("Picks from the green tile, drops on the amber tile" to Theme.DIM)
                out.add("Holding: ${if (bld.held >= 0) Item.ALL[bld.held].title else "nothing"}" to Theme.TEXT)
            }
            is CoalGenerator -> {
                out.add("Output: ${fmtKw(bld.output)} of ${fmtKw(CoalGenerator.MAX_KW)}" to Theme.TEXT)
                out.add("Coal: ${bld.coal}" to if (bld.coal == 0) Theme.BAD else Theme.TEXT)
            }
            is SolarPanel -> out.add("Output: ${fmtKw(bld.output)} (daylight ${(f.daylight * 100).toInt()}%)" to Theme.TEXT)
            is Accumulator -> {
                out.add("Charge: ${(bld.stored / Accumulator.CAPACITY_KJ * 100).toInt()}% of 5 MJ" to Theme.TEXT)
                out.add((if (bld.flow > 1f) "Charging ${fmtKw(bld.flow)}" else if (bld.flow < -1f) "Discharging ${fmtKw(-bld.flow)}" else "Idle") to Theme.DIM)
            }
            is Chest -> out.add("Stored: ${bld.total} / ${bld.capacity}" to Theme.TEXT)
            is FluidNode -> {
                val net = f.fluids.netAt(bld.x, bld.y)
                val n = f.fluids.nets.getOrNull(net)
                if (n == null || n.fluid < 0) out.add("Empty" to Theme.DIM)
                else out.add("${Fluid.ALL[n.fluid].title}: ${n.amount.toInt()} / ${n.capacity.toInt()}" to Theme.TEXT)
                out.add("Network: ${n?.nodes?.size ?: 0} segments" to Theme.DIM)
            }
            is Lab -> {
                val cur = f.research.current
                out.add("Researching: ${cur?.title ?: "nothing"}" to Theme.TEXT)
                if (cur != null) out.add("Needs: ${cur.packs.joinToString(", ") { it.title.removeSuffix(" Science") }}" to Theme.DIM)
            }
            is Core -> {
                out.add("Items delivered here become building stock." to Theme.TEXT)
                out.add("Built-in generator: ${fmtKw(bld.output)} of 400 kW" to Theme.DIM)
                out.add("Tap a stock chip (top-left) for all totals." to Theme.DIM)
            }
            else -> {}
        }
    }

    private fun depositLine(f: Factory, d: Drill, out: MutableList<Pair<String, Int>>) {
        val dep = f.world.nearestDeposit(d.cx, d.cy, 12f) ?: return
        val frac = dep.remaining.toFloat() / max(1L, dep.initial)
        out.add("Deposit: ${(frac * 100).toInt()}% left" to if (frac < 0.25f) Theme.WARN else Theme.DIM)
    }

    private fun inventoryGrid(bld: Building, x: Float, yTop: Float, w: Float) {
        val list = ArrayList<Pair<Item, Int>>()
        if (bld is Core) {
            for (it in Item.ALL) if (game.session.factory.stock[it.id] > 0) list.add(it to game.session.factory.stock[it.id])
        } else bld.inventory(list)
        ui.text(if (bld is Core) "Stock" else "Contents", x, yTop, 9f, Theme.DIM)
        if (list.isEmpty()) { ui.text("Empty", x, yTop - 16f * u, 9.5f, Theme.DIM); return }
        val cw = 58f * u; val chH = 22f * u
        val cols = max(1, (w / cw).toInt())
        for ((i, p) in list.take(cols * 4).withIndex()) {
            val cx = x + (i % cols) * cw; val cy = yTop - 28f * u - (i / cols) * (chH + 3f * u)
            b.roundRect(cx, cy, cw - 4f * u, chH, 6f * u, Col.pack(Theme.BUTTON))
            painter.item(p.first, cx + 10f * u, cy + chH / 2, 6.5f * u)
            ui.text(fmt(p.second.toFloat()), cx + 20f * u, cy + 7f * u, 9f)
        }
    }

    private fun recipeRow(s: Session, c: Crafter, x: Float, y: Float, w: Float) {
        val recipes = Recipes.forClass(c.mclass)
        val bs = 38f * u; val gap = 4f * u
        val extra = if (c.mclass == MachineClass.SMELTER) 1 else 0
        val sc = ui.scroll("recipes", x, y, w, bs, (recipes.size + extra) * (bs + gap), bs, horizontal = true)
        ui.clip(x, y - 2f * u, w, bs + 4f * u) {
            var bx = x - sc.offX
            if (extra == 1) {
                if (ui.button(bx, y, bs, bs, "Auto", selected = c.autoRecipe, labelSize = 9f)) { c.autoRecipe = true; click() }
                bx += bs + gap
            }
            for (r in recipes) {
                val ok = c.canUse(r)
                val sel = c.recipe === r && !(c.autoRecipe && extra == 1)
                val main = r.mainItem; val fl = r.mainFluid
                if (ui.button(bx, y, bs, bs, selected = sel, enabled = ok, icon = { cx, cy, sz ->
                        if (main != null) painter.item(main, cx, cy, sz * 0.42f) else if (fl != null) painter.fluidDrop(fl, cx, cy, sz * 0.4f)
                        if (!ok) b.roundRect(cx - bs / 2, cy - bs / 2, bs, bs, 10f * u, Col.pack(0x000000, 0.55f))
                    })) {
                    c.setRecipe(r)
                    click()
                    s.toast(r.title, 0x7BD389)
                }
                bx += bs + gap
            }
        }
    }

    private fun itemPicker(ins: Inserter, x: Float, y: Float, w: Float) {
        val bs = 38f * u; val gap = 4f * u
        val sc = ui.scroll("filter", x, y, w, bs, Item.COUNT * (bs + gap), bs, horizontal = true)
        ui.clip(x, y - 2f * u, w, bs + 4f * u) {
            var bx = x - sc.offX
            for (it in Item.ALL) {
                if (ui.button(bx, y, bs, bs, selected = ins.filter == it.id, icon = { cx, cy, sz -> painter.item(it, cx, cy, sz * 0.42f) })) {
                    ins.filter = it.id; click(); game.session.toast("Filter: ${it.title}", 0xB388FF)
                }
                bx += bs + gap
            }
        }
    }

    // =================================================================================
    // Modals
    // =================================================================================

    private fun modalFrame(title: String): FloatArray {
        val w = ui.w; val h = ui.h
        b.rect(0f, 0f, w, h, Col.pack(0x000000, 0.5f))
        val x = M * 2; val y = M * 2; val mw = w - M * 4; val mh = h - M * 4
        ui.panel(x, y, mw, mh, 0.97f)
        ui.block(0f, 0f, w, h)
        ui.text(title, x + 16f * u, y + mh - 26f * u, 16f, Theme.ACCENT)
        if (ui.button(x + mw - 52f * u, y + mh - 50f * u, 44f * u, 44f * u, icon = { cx, cy, sz -> ui.iconCross(cx, cy, sz * 0.8f) })) { modal = null; click() }
        return floatArrayOf(x, y, mw, mh)
    }

    private fun techModal(s: Session) {
        val (x, y, mw, mh) = modalFrame("Research").let { arrayOf(it[0], it[1], it[2], it[3]) }
        val res = s.factory.research
        val cur = res.current
        ui.text(if (cur != null) "Current: ${cur.title} – ${res.progress[cur.index]}/${cur.units}" +
            (if (!res.chosen) " (auto-picked)" else "") else "All available research done", x + 150f * u, y + mh - 25f * u, 10f, Theme.TEXT)
        // Detail strip at the bottom.
        val detailH = 58f * u
        val ax = x + 10f * u; val ay = y + detailH + 14f * u; val aw = mw - 20f * u; val ah = mh - detailH - 78f * u
        val nodeW = 150f * u; val nodeH = 50f * u; val colW = 178f * u; val rowH = 64f * u
        val contentW = 10 * colW; val contentH = 6 * rowH
        val sc = ui.scroll("tech", ax, ay, aw, ah, contentW, contentH, horizontal = true, vertical = true)
        b.roundRect(ax, ay, aw, ah, 8f * u, Col.pack(0x121418, 0.8f))
        fun nx(t: Tech) = ax + 8f * u + t.col * colW - sc.offX
        fun ny(t: Tech) = ay + ah - nodeH - 8f * u - t.row * rowH + sc.offY
        ui.clip(ax, ay, aw, ah) {
            // Prerequisite links.
            for (t in Techs.ALL) for (p in t.prereqs) {
                val pt = Techs[p]
                val done = res.done[pt.index]
                b.line(nx(pt) + nodeW, ny(pt) + nodeH / 2, nx(t), ny(t) + nodeH / 2, 2f * u, Col.pack(if (done) 0x7BD389 else 0x4A4F5A, if (done) 0.7f else 0.9f))
            }
            for (t in Techs.ALL) {
                val tx = nx(t); val ty = ny(t)
                if (tx > ax + aw || tx + nodeW < ax || ty > ay + ah || ty + nodeH < ay) continue
                val done = res.done[t.index]
                val avail = res.available(t)
                val isCur = cur === t
                val base = when { done -> 0x1F3A2A; avail -> 0x2E3440; else -> 0x1E2127 }
                b.roundRect(tx, ty, nodeW, nodeH, 9f * u, Col.pack(Col.darken(base, 0.2f)), Col.pack(Col.lighten(base, 0.08f)))
                val edge = when { isCur -> Theme.ACCENT; done -> Theme.GOOD; avail -> 0x8C93A0; else -> 0x3A3F4A }
                b.roundRectOutline(tx, ty, nodeW, nodeH, 9f * u, (if (isCur) 2.4f else 1.3f) * u, Col.pack(edge))
                if (techFocus === t) b.roundRectOutline(tx - 3f * u, ty - 3f * u, nodeW + 6f * u, nodeH + 6f * u, 11f * u, 1.5f * u, Col.pack(0xFFFFFF, 0.6f))
                ui.text(t.title, tx + 8f * u, ty + nodeH - 16f * u, 9.5f, if (avail || done) Theme.TEXT else Theme.DIM)
                // Pack dots.
                for ((k, p) in t.packs.withIndex()) b.circle(tx + 12f * u + k * 11f * u, ty + 18f * u, 4f * u, Col.pack(p.color))
                ui.text("×${t.units}", tx + 14f * u + t.packs.size * 11f * u, ty + 14f * u, 8.5f, Theme.DIM)
                if (done) ui.iconCheck(tx + nodeW - 14f * u, ty + nodeH - 12f * u, 16f * u)
                else {
                    val p = res.progress[t.index].toFloat() / t.units
                    if (p > 0f) ui.progressBar(tx + 8f * u, ty + 5f * u, nodeW - 16f * u, 4f * u, p, 0xB388FF)
                }
                if (ui.tapped(max(tx, ax), max(ty, ay), nodeW, nodeH)) {
                    techFocus = t
                    if (avail) { res.select(t); click() }
                }
            }
        }
        // Details for the focused tech.
        val t = techFocus ?: cur
        if (t != null) {
            val dy = y + 10f * u
            b.roundRect(ax, dy, aw, detailH, 8f * u, Col.pack(0x121418, 0.8f))
            ui.text(t.title, ax + 10f * u, dy + detailH - 18f * u, 12f, Theme.ACCENT)
            val state = when {
                res.done[t.index] -> "Researched"
                res.available(t) -> if (cur === t) "Researching now – supply your labs" else "Tap to research"
                else -> "Requires: " + t.prereqs.filter { !res.done[Techs[it].index] }.joinToString(", ") { Techs[it].title }
            }
            ui.text(state, ax + 10f * u + ui.textW(t.title, 12f) + 12f * u, dy + detailH - 17f * u, 9f, Theme.DIM)
            ui.text("Unlocks: ${t.unlockText}", ax + 10f * u, dy + detailH - 34f * u, 9.5f)
            val packs = t.packs.joinToString(" + ") { it.title.removeSuffix(" Science") }
            ui.text("Cost: ${t.units} × ($packs) · ${t.unitTime.toInt()}s each", ax + 10f * u, dy + 8f * u, 9f, Theme.DIM)
        } else {
            ui.text("Build Research Labs, feed them science packs, and pick a technology.", ax, y + 30f * u, 10f, Theme.DIM)
        }
        ui.eatTaps()
    }

    private fun statsModal(s: Session) {
        val (x, y, mw, mh) = modalFrame("Statistics").let { arrayOf(it[0], it[1], it[2], it[3]) }
        val tabs = listOf("Production", "Power", "Resources")
        for ((i, t) in tabs.withIndex()) {
            if (ui.button(x + 170f * u + i * 104f * u, y + mh - 46f * u, 98f * u, 34f * u, t, selected = statsTab == i)) { statsTab = i; click() }
        }
        val ax = x + 12f * u; val ay = y + 12f * u; val aw = mw - 24f * u; val ah = mh - 70f * u
        when (statsTab) {
            0 -> productionTab(s.factory, ax, ay, aw, ah)
            1 -> powerTab(s.factory, ax, ay, aw, ah)
            else -> resourcesTab(s, ax, ay, aw, ah)
        }
        ui.eatTaps()
    }

    private fun productionTab(f: Factory, x: Float, y: Float, w: Float, h: Float) {
        class Row(val item: Item?, val fluid: Fluid?, val prod: Float, val cons: Float, val stock: Int)
        val rows = ArrayList<Row>()
        for (it in Item.ALL) {
            val p = f.stats.producedPerMin(it.id); val c = f.stats.consumedPerMin(it.id)
            if (p > 0f || c > 0f || f.stock[it.id] > 0) rows.add(Row(it, null, p, c, f.stock[it.id]))
        }
        for (fl in Fluid.ALL) {
            val p = f.stats.fluidProducedPerMin(fl.ordinal); val c = f.stats.fluidConsumedPerMin(fl.ordinal)
            if (p > 0f || c > 0f) rows.add(Row(null, fl, p, c, -1))
        }
        val rh = 30f * u
        ui.text("Item", x + 34f * u, y + h - 4f * u, 9f, Theme.DIM)
        ui.text("Produced /min", x + w * 0.42f, y + h - 4f * u, 9f, Theme.GOOD, 1)
        ui.text("Consumed /min", x + w * 0.62f, y + h - 4f * u, 9f, Theme.BAD, 1)
        ui.text("In core", x + w * 0.8f, y + h - 4f * u, 9f, Theme.DIM, 1)
        val ly = y; val lh = h - 16f * u
        val sc = ui.scroll("prod", x, ly, w, lh, w, rows.size * rh, vertical = true)
        val maxRate = max(1f, rows.maxOfOrNull { max(it.prod, it.cons) } ?: 1f)
        ui.clip(x, ly, w, lh) {
            for ((i, r) in rows.withIndex()) {
                val ry = ly + lh - (i + 1) * rh + sc.offY
                if (ry > ly + lh || ry + rh < ly) continue
                if (i % 2 == 0) b.rect(x, ry, w, rh, Col.pack(0xFFFFFF, 0.03f))
                if (r.item != null) painter.item(r.item, x + 16f * u, ry + rh / 2, 8f * u) else painter.fluidDrop(r.fluid!!, x + 16f * u, ry + rh / 2, 8f * u)
                ui.text(r.item?.title ?: r.fluid!!.title, x + 34f * u, ry + 10f * u, 10f)
                b.roundRect(x + w * 0.25f, ry + 5f * u, w * 0.18f * r.prod / maxRate, 3f * u, 1.5f * u, Col.pack(Theme.GOOD, 0.6f))
                ui.text(fmt(r.prod), x + w * 0.42f, ry + 10f * u, 10f, if (r.prod > 0f) Theme.GOOD else Theme.DIM, 1)
                ui.text(fmt(r.cons), x + w * 0.62f, ry + 10f * u, 10f, if (r.cons > 0f) Theme.BAD else Theme.DIM, 1)
                if (r.stock >= 0) ui.text(fmt(r.stock.toFloat()), x + w * 0.8f, ry + 10f * u, 10f, Theme.TEXT, 1)
            }
            if (rows.isEmpty()) ui.text("Nothing produced yet – place a Mining Drill on ore.", x + 10f * u, ly + lh - 30f * u, 10f, Theme.DIM)
        }
    }

    private fun powerTab(f: Factory, x: Float, y: Float, w: Float, h: Float) {
        val st = f.stats
        ui.text("Generation ${fmtKw(st.genNow)}   ·   Consumption ${fmtKw(st.useNow)}   ·   Stored ${fmt(f.power.storedEnergy() / 1000f)} MJ",
            x + 4f * u, y + h - 6f * u, 10.5f)
        val gx = x + 40f * u; val gy = y + 30f * u; val gw = w - 50f * u; val gh = h - 60f * u
        b.roundRect(gx, gy, gw, gh, 6f * u, Col.pack(0x121418, 0.9f))
        val n = st.powerSamples
        var peak = 100f
        for (k in 0 until n) peak = max(peak, max(st.powerGen[k], st.powerUse[k]))
        peak *= 1.15f
        for (k in 0..4) {
            val ly = gy + gh * k / 4f
            b.rect(gx, ly, gw, 1f * u, Col.pack(0xFFFFFF, 0.06f))
            ui.text(fmtKw(peak * k / 4f), gx - 4f * u, ly - 3f * u, 7.5f, Theme.DIM, 1)
        }
        if (n >= 2) {
            val step = gw / (Stats.HISTORY - 1)
            fun idx(k: Int) = (st.powerHead - n + k + Stats.HISTORY) % Stats.HISTORY
            val x0 = gx + gw - (n - 1) * step
            for (k in 1 until n) {
                val xa = x0 + (k - 1) * step; val xb = x0 + k * step
                val ga = gy + gh * st.powerGen[idx(k - 1)] / peak; val gb = gy + gh * st.powerGen[idx(k)] / peak
                val ua = gy + gh * st.powerUse[idx(k - 1)] / peak; val ub = gy + gh * st.powerUse[idx(k)] / peak
                b.quad(xa, gy, xb, gy, xb, gb, xa, ga, Col.pack(0x4FC3F7, 0.15f))
                b.line(xa, ga, xb, gb, 2f * u, Col.pack(0x4FC3F7))
                b.line(xa, ua, xb, ub, 2f * u, Col.pack(0xFF9F1C))
            }
        }
        ui.text("Generation", gx + 8f * u, y + 10f * u, 9f, 0x4FC3F7)
        ui.text("Consumption", gx + 90f * u, y + 10f * u, 9f, 0xFF9F1C)
        ui.text("last ${Stats.HISTORY / 60} min", gx + gw, y + 10f * u, 9f, Theme.DIM, 1)
    }

    private fun resourcesTab(s: Session, x: Float, y: Float, w: Float, h: Float) {
        val f = s.factory
        val core = f.core
        val deps = f.world.deposits.filter { it.remaining > 0 && f.world.isExplored(it.cx.toInt(), it.cy.toInt()) }
            .sortedWith(compareByDescending<io.github.rantark.factoryflow.world.Deposit> { it.rate > 0.01f }.thenBy { hypot(it.cx - core.cx, it.cy - core.cy) })
        val rh = 30f * u
        ui.text("Deposit", x + 34f * u, y + h - 4f * u, 9f, Theme.DIM)
        ui.text("Remaining", x + w * 0.45f, y + h - 4f * u, 9f, Theme.DIM, 1)
        ui.text("Mined /min", x + w * 0.62f, y + h - 4f * u, 9f, Theme.DIM, 1)
        ui.text("Runs out in", x + w * 0.82f, y + h - 4f * u, 9f, Theme.DIM, 1)
        val lh = h - 16f * u
        val sc = ui.scroll("res", x, y, w, lh, w, deps.size * rh, vertical = true)
        ui.clip(x, y, w, lh) {
            for ((i, d) in deps.withIndex()) {
                val ry = y + lh - (i + 1) * rh + sc.offY
                if (ry > y + lh || ry + rh < y) continue
                if (i % 2 == 0) b.rect(x, ry, w, rh, Col.pack(0xFFFFFF, 0.03f))
                val item = d.res.item
                if (item != null) painter.item(item, x + 16f * u, ry + rh / 2, 8f * u) else painter.fluidDrop(Fluid.CRUDE_OIL, x + 16f * u, ry + rh / 2, 8f * u)
                val dist = hypot(d.cx - core.cx, d.cy - core.cy).toInt()
                ui.text("${d.res.title} · ${dist}m away", x + 34f * u, ry + 10f * u, 10f)
                val frac = d.remaining.toFloat() / max(1L, d.initial)
                ui.progressBar(x + w * 0.3f, ry + 11f * u, w * 0.08f, 5f * u, frac, if (frac > 0.3f) Theme.GOOD else Theme.WARN)
                ui.text(fmt(d.remaining.toFloat()), x + w * 0.45f, ry + 10f * u, 10f, Theme.TEXT, 1)
                ui.text(if (d.rate > 0.01f) fmt(d.rate * 60f) else "–", x + w * 0.62f, ry + 10f * u, 10f, Theme.TEXT, 1)
                val eta = if (d.rate > 0.01f && d.res != Resource.OIL) fmtTime(d.remaining / d.rate) else "–"
                ui.text(eta, x + w * 0.82f, ry + 10f * u, 10f, Theme.TEXT, 1)
                if (ui.tapped(x, max(ry, y), w, rh)) { s.cam.x = d.cx; s.cam.y = d.cy; modal = null }
            }
        }
    }

    private fun menuModal(s: Session) {
        val (x, y, mw, mh) = modalFrame("Factory Flow").let { arrayOf(it[0], it[1], it[2], it[3]) }
        ui.text("Auto-saves every 5 minutes and when you leave the app.", x + 170f * u, y + mh - 25f * u, 9.5f, Theme.DIM)
        val rowH = 56f * u
        val df = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        for (slot in 1..3) {
            val ry = y + mh - 64f * u - slot * (rowH + 8f * u)
            val rx = x + 16f * u; val rw = mw - 32f * u
            val current = slot == s.slot
            b.roundRect(rx, ry, rw, rowH, 10f * u, Col.pack(if (current) 0x2A3140 else Theme.BUTTON))
            if (current) b.roundRectOutline(rx, ry, rw, rowH, 10f * u, 1.5f * u, Col.pack(Theme.ACCENT))
            ui.text("Slot $slot${if (current) "  (playing)" else ""}", rx + 14f * u, ry + rowH - 22f * u, 12f, if (current) Theme.ACCENT else Theme.TEXT)
            val hdr = game.saves.header(slot)
            ui.text(if (hdr == null) "Empty" else "${df.format(Date(hdr.savedAt))} · played ${fmtTime(hdr.playTime.toFloat())} · ${hdr.buildings} buildings · ${hdr.researched} techs",
                rx + 14f * u, ry + 10f * u, 9f, Theme.DIM)
            val bw = 84f * u
            if (ui.button(rx + rw - bw - 8f * u, ry + 8f * u, bw, rowH - 16f * u, "Save here")) { game.saveTo(slot) }
            if (hdr != null && ui.button(rx + rw - 2 * bw - 16f * u, ry + 8f * u, bw, rowH - 16f * u, "Load")) { game.loadFrom(slot); modal = null }
        }
        val by = y + 14f * u
        if (ui.button(x + 16f * u, by, 150f * u, 44f * u, if (confirmNew > 0f) "Tap again: new world" else "New game", accent = Theme.BAD, selected = confirmNew > 0f)) {
            if (confirmNew > 0f) { game.newGame(); modal = null; confirmNew = 0f } else confirmNew = 3f
        }
        if (ui.button(x + 176f * u, by, 130f * u, 44f * u, if (game.saves.soundOn) "Sound: On" else "Sound: Off")) {
            game.saves.soundOn = !game.saves.soundOn; game.audio.muted = !game.saves.soundOn; click()
        }
        ui.text("Version ${game.platform.versionLabel}  ·  Seed ${s.factory.world.seed}", x + mw - 16f * u, by + 16f * u, 9f, Theme.DIM, 1)
        ui.eatTaps()
    }

    // =================================================================================
    // Toasts
    // =================================================================================

    private fun toasts(s: Session, w: Float, h: Float, max: Int) {
        var y = h - M - 70f * u
        for (t in s.toasts.reversed().take(max)) {
            val a = min(1f, t.life * 2f)
            val size = 10.5f
            val tw = ui.textW(t.text, size) + 28f * u
            val x = (w - tw) / 2
            val th = 26f * u
            b.roundRect(x, y - th, tw, th, th / 2, Col.pack(0x121418, 0.88f * a))
            b.roundRectOutline(x, y - th, tw, th, th / 2, 1f * u, Col.pack(t.color, 0.5f * a))
            ui.text(t.text, w / 2, y - th + 8.5f * u, size, t.color, 0, a)
            y -= th + 6f * u
        }
    }

    private fun click() = game.audio.play(io.github.rantark.factoryflow.audio.Sfx.CLICK)

    companion object {
        /** Tiles shown across the minimap. */
        const val MINIMAP_SPAN = 160f

        fun fmt(v: Float): String = when {
            v >= 1_000_000f -> String.format(Locale.US, "%.1fM", v / 1e6f)
            v >= 10_000f -> "${(v / 1000f).toInt()}k"
            v >= 1000f -> String.format(Locale.US, "%.1fk", v / 1000f)
            v >= 10f || v == v.toInt().toFloat() -> v.toInt().toString()
            else -> String.format(Locale.US, "%.1f", v)
        }

        fun fmtKw(kw: Float): String = if (kw >= 1000f) String.format(Locale.US, "%.1f MW", kw / 1000f) else "${kw.toInt()} kW"

        fun fmtTime(sec: Float): String {
            val s = sec.toInt()
            return when {
                s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
                s >= 60 -> "${s / 60}m"
                else -> "${s}s"
            }
        }
    }
}
