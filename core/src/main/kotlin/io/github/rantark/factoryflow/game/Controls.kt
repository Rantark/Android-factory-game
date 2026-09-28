package io.github.rantark.factoryflow.game

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.Vector2

/**
 * World touch handling (anything not on a UI panel):
 * - one-finger drag pans (with fling inertia), two-finger pinch zooms and pans;
 * - tap selects a building, moves the placement ghost, or bulldozes;
 * - in placement mode, dragging that starts on the ghost moves it, or paints a line
 *   of belts/pipes/poles.
 */
class Controls(private val game: FactoryGame) : GestureDetector.GestureAdapter() {
    private val s get() = game.session
    private var ghostDrag = false
    private var dragStartTX = 0
    private var dragStartTY = 0
    private var pinching = false
    private var pinchZoom = 1f
    private var pinchWX = 0f
    private var pinchWY = 0f

    override fun touchDown(x: Float, y: Float, pointer: Int, button: Int): Boolean {
        if (pointer == 0) {
            s.cam.velX = 0f; s.cam.velY = 0f
            ghostDrag = false
            if (s.mode == Mode.PLACE && s.ghostContains(s.cam.tileX(x), s.cam.tileY(y))) {
                ghostDrag = true
                dragStartTX = s.ghostTX; dragStartTY = s.ghostTY
            }
        }
        return false
    }

    override fun tap(x: Float, y: Float, count: Int, button: Int): Boolean {
        if (button == Input.Buttons.RIGHT) { s.back(); return true }
        s.tapWorld(s.cam.tileX(x), s.cam.tileY(y))
        return true
    }

    override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
        if (pinching) return true
        if (ghostDrag) {
            val tx = s.cam.tileX(x); val ty = s.cam.tileY(y)
            val t = s.buildType
            if (t != null && t.lineBuild) s.line = s.planLine(dragStartTX, dragStartTY, tx, ty)
            else { s.ghostTX = tx; s.ghostTY = ty }
            return true
        }
        s.cam.x -= deltaX / s.cam.ppt
        s.cam.y += deltaY / s.cam.ppt
        return true
    }

    override fun panStop(x: Float, y: Float, pointer: Int, button: Int): Boolean {
        if (ghostDrag) {
            if (s.line != null) s.commitLine()
            ghostDrag = false
        }
        return false
    }

    override fun fling(velocityX: Float, velocityY: Float, button: Int): Boolean {
        if (ghostDrag || pinching) return false
        s.cam.velX = -velocityX / s.cam.ppt
        s.cam.velY = velocityY / s.cam.ppt
        return true
    }

    override fun pinch(ip1: Vector2, ip2: Vector2, p1: Vector2, p2: Vector2): Boolean {
        if (!pinching) {
            pinching = true
            ghostDrag = false; s.line = null
            pinchZoom = s.cam.zoom
            val mx = (ip1.x + ip2.x) / 2f; val my = (ip1.y + ip2.y) / 2f
            pinchWX = s.cam.worldX(mx); pinchWY = s.cam.worldY(my)
        }
        val d0 = ip1.dst(ip2); val d1 = p1.dst(p2)
        if (d0 > 1f) s.cam.zoom = pinchZoom * d1 / d0
        // Keep the world point that started under the fingers' midpoint under it.
        val mx = (p1.x + p2.x) / 2f; val my = (p1.y + p2.y) / 2f
        s.cam.x = pinchWX - (mx - s.cam.w / 2f) / s.cam.ppt
        s.cam.y = pinchWY - (s.cam.h / 2f - my) / s.cam.ppt
        return true
    }

    override fun pinchStop() { pinching = false }

    /** Keyboard / mouse wheel (desktop testing, Android back button). */
    val keys = object : InputAdapter() {
        override fun keyDown(keycode: Int): Boolean {
            when (keycode) {
                Input.Keys.BACK, Input.Keys.ESCAPE -> return game.back()
                Input.Keys.R -> if (s.mode == Mode.PLACE) s.rotateGhost() else s.selected?.let { s.factory.rotate(it) }
                Input.Keys.Z -> s.undo()
                Input.Keys.B -> s.toggleBulldoze()
                Input.Keys.SPACE -> s.cycleSpeed()
                Input.Keys.LEFT, Input.Keys.A -> s.cam.x -= 3f / s.cam.zoom
                Input.Keys.RIGHT, Input.Keys.D -> s.cam.x += 3f / s.cam.zoom
                Input.Keys.UP, Input.Keys.W -> s.cam.y += 3f / s.cam.zoom
                Input.Keys.DOWN, Input.Keys.S -> s.cam.y -= 3f / s.cam.zoom
                else -> return false
            }
            return true
        }

        override fun scrolled(amountX: Float, amountY: Float): Boolean {
            val mx = com.badlogic.gdx.Gdx.input.x.toFloat(); val my = com.badlogic.gdx.Gdx.input.y.toFloat()
            s.cam.zoomAt(if (amountY > 0) 1f / 1.15f else 1.15f, mx, my)
            return true
        }
    }
}
