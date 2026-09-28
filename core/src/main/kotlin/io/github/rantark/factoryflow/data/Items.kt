package io.github.rantark.factoryflow.data

/** Shape family used when drawing an item icon (on belts, in panels, …). */
enum class ItemShape { ROCK, PLATE, BRICK, BAR, GEAR, COIL, CHIP, TUBE, ENGINE, BEAD, CAPSULE, FRAME, CRYSTAL, FLASK, POWDER }

/**
 * Every solid item in the game. The ordinal is used as a compact id in the simulation
 * (belts store items as bytes), so only append new entries to keep saves compatible.
 */
enum class Item(val title: String, val color: Int, val shape: ItemShape, val accent: Int = color) {
    IRON_ORE("Iron Ore", 0x8FA3B8, ItemShape.ROCK, 0x5E6E80),
    COPPER_ORE("Copper Ore", 0xE0834A, ItemShape.ROCK, 0x9C4F25),
    COAL("Coal", 0x3A3A40, ItemShape.ROCK, 0x1E1E22),
    STONE("Stone", 0xC7B08A, ItemShape.ROCK, 0x8C7A5C),
    CRYSTAL("Rare Crystal", 0xC77DFF, ItemShape.CRYSTAL, 0x7B2CBF),
    IRON_PLATE("Iron Plate", 0xC9D4E0, ItemShape.PLATE, 0x8A98A8),
    COPPER_PLATE("Copper Plate", 0xF2A365, ItemShape.PLATE, 0xB86B35),
    STONE_BRICK("Stone Brick", 0xD9C29C, ItemShape.BRICK, 0xA38B64),
    STEEL("Steel Beam", 0x9FB4C7, ItemShape.BAR, 0x5B7085),
    GEAR("Gear", 0xB8C4D0, ItemShape.GEAR, 0x6E7C8A),
    WIRE("Copper Wire", 0xFFB26B, ItemShape.COIL, 0xC46A2A),
    PIPE("Pipe", 0x9AA5B1, ItemShape.TUBE, 0x5C6670),
    CIRCUIT("Circuit", 0x4CC26B, ItemShape.CHIP, 0x1F6B35),
    ENGINE("Engine Unit", 0x7E8FA3, ItemShape.ENGINE, 0xF2A541),
    PLASTIC("Plastic", 0xF5F5F0, ItemShape.BEAD, 0xB8B8B0),
    SULFUR("Sulfur", 0xF7E04A, ItemShape.POWDER, 0xB8A020),
    BATTERY("Battery", 0x5BC0EB, ItemShape.CAPSULE, 0x2A6F99),
    ADV_CIRCUIT("Adv. Circuit", 0xE0524D, ItemShape.CHIP, 0x8A1E1B),
    MACHINE_FRAME("Machine Frame", 0x9BA7C0, ItemShape.FRAME, 0xF2A541),
    CRYSTAL_SHARD("Crystal Shard", 0xE0AAFF, ItemShape.CRYSTAL, 0x9D4EDD),
    QUANTUM_CHIP("Quantum Chip", 0x72EFDD, ItemShape.CHIP, 0x3A0CA3),
    SCI_RED("Red Science", 0xE63946, ItemShape.FLASK),
    SCI_GREEN("Green Science", 0x52B788, ItemShape.FLASK),
    SCI_BLUE("Blue Science", 0x4895EF, ItemShape.FLASK),
    SCI_PURPLE("Purple Science", 0x9D4EDD, ItemShape.FLASK),
    SCI_YELLOW("Yellow Science", 0xFFD166, ItemShape.FLASK);

    val id get() = ordinal
    val isScience get() = shape == ItemShape.FLASK

    companion object {
        val ALL = entries.toTypedArray()
        val COUNT = ALL.size
        val SCIENCE = listOf(SCI_RED, SCI_GREEN, SCI_BLUE, SCI_PURPLE, SCI_YELLOW)
    }
}

/** Fluids travel through pipes and are stored in tanks rather than on belts. */
enum class Fluid(val title: String, val color: Int) {
    WATER("Water", 0x3FA7F5),
    CRUDE_OIL("Crude Oil", 0x4A3B52),
    HEAVY_OIL("Heavy Oil", 0xC9722C),
    LIGHT_OIL("Light Oil", 0xF2C14E),
    PETROLEUM("Petroleum Gas", 0xB57EDC),
    ACID("Sulfuric Acid", 0xC6E84A);

    companion object {
        val ALL = entries.toTypedArray()
        val COUNT = ALL.size
    }
}
