package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Item
import io.github.rantark.factoryflow.world.World

/** Creates fresh games. */
object Scenario {
    /** Starting building stock – enough to set up the first mining and smelting lines. */
    private val START_STOCK = mapOf(
        Item.IRON_PLATE to 400, Item.COPPER_PLATE to 200, Item.GEAR to 80, Item.CIRCUIT to 60,
        Item.STONE to 120, Item.STONE_BRICK to 60, Item.COAL to 20,
    )

    fun newGame(seed: Long): Factory {
        val f = Factory(World(seed))
        val core = Core(World.START_X - 2, World.START_Y - 2)
        f.insert(core)
        for ((k, v) in START_STOCK) f.stock[k.id] = v
        f.research.autoPick()
        return f
    }

    /** Is there room for [type] centred on tile (tx, ty)? */
    fun fits(f: Factory, type: BuildingType, tx: Int, ty: Int) =
        f.placementProblem(type, f.anchorX(type, tx), f.anchorY(type, ty), checkCost = false) == null
}
