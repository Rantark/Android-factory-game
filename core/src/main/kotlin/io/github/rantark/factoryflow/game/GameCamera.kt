package io.github.rantark.factoryflow.game

import com.badlogic.gdx.graphics.OrthographicCamera
import io.github.rantark.factoryflow.world.World
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/**
 * Top-down camera. Position is in tiles; [zoom] runs 0.3×–3× where 1× shows about
 * eleven tiles across the short screen edge. Flings keep gliding with soft friction.
 */
class GameCamera {
    var x = World.START_X.toFloat()
    var y = World.START_Y.toFloat()
    var zoom = 1f
        set(v) { field = v.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    var w = 1f
    var h = 1f
    var velX = 0f
    var velY = 0f
    val ortho = OrthographicCamera()

    /** Screen pixels per tile. */
    val ppt get() = min(w, h) / 11f * zoom

    fun resize(width: Float, height: Float) { w = width; h = height }

    fun update(dt: Float) {
        if (velX != 0f || velY != 0f) {
            x += velX * dt; y += velY * dt
            val k = exp(-5f * dt)
            velX *= k; velY *= k
            if (velX * velX + velY * velY < 0.01f) { velX = 0f; velY = 0f }
        }
        x = x.coerceIn(0f, World.W.toFloat()); y = y.coerceIn(0f, World.H.toFloat())
        ortho.setToOrtho(false, w / ppt, h / ppt)
        ortho.position.set(x, y, 0f)
        ortho.update()
    }

    /** Screen (top-left origin, as delivered by input) → world tile coordinates. */
    fun worldX(sx: Float) = x + (sx - w / 2f) / ppt
    fun worldY(sy: Float) = y + (h / 2f - sy) / ppt
    fun tileX(sx: Float) = floor(worldX(sx)).toInt()
    fun tileY(sy: Float) = floor(worldY(sy)).toInt()

    /** Zoom by [factor] keeping the world point under screen (sx, sy) fixed. */
    fun zoomAt(factor: Float, sx: Float, sy: Float) {
        val wx = worldX(sx); val wy = worldY(sy)
        zoom *= factor
        x = wx - (sx - w / 2f) / ppt
        y = wy - (h / 2f - sy) / ppt
    }

    val left get() = x - w / 2f / ppt
    val right get() = x + w / 2f / ppt
    val bottom get() = y - h / 2f / ppt
    val top get() = y + h / 2f / ppt

    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 3f
    }
}
