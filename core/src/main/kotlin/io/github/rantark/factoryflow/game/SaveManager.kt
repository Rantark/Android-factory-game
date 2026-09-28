package io.github.rantark.factoryflow.game

import com.badlogic.gdx.Gdx
import io.github.rantark.factoryflow.sim.Factory
import io.github.rantark.factoryflow.sim.SaveGame

/**
 * Three save slots stored in the app's private storage. Writes go to a temporary
 * file first and are then moved into place, so a crash mid-save never corrupts a slot.
 */
class SaveManager {
    private val prefs by lazy { Gdx.app.getPreferences("factoryflow") }

    private fun file(slot: Int) = Gdx.files.local("saves/slot$slot.ffs")

    var lastSlot: Int
        get() = prefs.getInteger("lastSlot", 1)
        set(v) { prefs.putInteger("lastSlot", v); prefs.flush() }

    var soundOn: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) { prefs.putBoolean("sound", v); prefs.flush() }

    fun exists(slot: Int) = file(slot).exists()

    fun header(slot: Int): SaveGame.Header? {
        val f = file(slot)
        if (!f.exists()) return null
        return f.read().use { SaveGame.readHeader(it) }
    }

    fun save(slot: Int, factory: Factory, cam: GameCamera): Boolean = try {
        val tmp = Gdx.files.local("saves/slot$slot.tmp")
        tmp.write(false).use { SaveGame.write(factory, SaveGame.Camera(cam.x, cam.y, cam.zoom), it) }
        tmp.moveTo(file(slot))
        lastSlot = slot
        true
    } catch (e: Exception) {
        Gdx.app.error("save", "Saving slot $slot failed", e)
        false
    }

    fun load(slot: Int): Pair<Factory, SaveGame.Camera>? = try {
        file(slot).read().use { SaveGame.read(it) }
    } catch (e: Exception) {
        Gdx.app.error("save", "Loading slot $slot failed", e)
        null
    }
}
