package io.github.rantark.factoryflow.data

import io.github.rantark.factoryflow.data.Item.*

/** Toolbar categories. Colours tint the building bodies so categories read at a glance. */
enum class Category(val title: String, val color: Int) {
    EXTRACTION("Extract", 0xF2A541),
    TRANSPORT("Belts", 0xFFD23F),
    POWER("Power", 0x4FC3F7),
    PROCESSING("Process", 0xEF6F6C),
    STORAGE("Storage", 0xA3B18A),
    RESEARCH("Research", 0xB388FF),
}

/**
 * Static description of every placeable structure.
 * @param size footprint edge length in tiles (all buildings are square).
 * @param power electrical draw in kW while working (0 = not electric).
 * @param lineBuild true for things that can be drag-painted in a line (belts, pipes…).
 */
enum class BuildingType(
    val title: String,
    val category: Category?,
    val size: Int,
    val cost: List<Stack>,
    val power: Float = 0f,
    val unlocked: Boolean = true,
    val rotatable: Boolean = true,
    val lineBuild: Boolean = false,
    val desc: String = "",
) {
    // ---- Extraction -----------------------------------------------------------------
    MINING_DRILL("Mining Drill", Category.EXTRACTION, 2, c(IRON_PLATE to 10, GEAR to 5, CIRCUIT to 3), power = 90f,
        desc = "Mines solid ore beneath it and pushes it out the arrow side."),
    PUMP_JACK("Pump Jack", Category.EXTRACTION, 2, c(STEEL to 5, GEAR to 10, CIRCUIT to 5), power = 90f, unlocked = false,
        desc = "Extracts crude oil from an oil seep into adjacent pipes."),
    WATER_PUMP("Water Pump", Category.EXTRACTION, 1, c(IRON_PLATE to 5, GEAR to 2, CIRCUIT to 2), power = 30f, rotatable = false,
        desc = "Place on water. Pumps water into adjacent pipes."),

    // ---- Transport ------------------------------------------------------------------
    BELT("Conveyor Belt", Category.TRANSPORT, 1, c(IRON_PLATE to 1), lineBuild = true,
        desc = "Moves items. Drag from the ghost to paint a line."),
    UNDERGROUND("Underground Belt", Category.TRANSPORT, 1, c(IRON_PLATE to 5, GEAR to 2), unlocked = false,
        desc = "Place an entrance, then an exit up to 5 tiles ahead in the same direction."),
    SPLITTER("Splitter", Category.TRANSPORT, 1, c(IRON_PLATE to 5, CIRCUIT to 2), unlocked = false,
        desc = "Takes items from behind and shares them left, ahead and right."),
    MERGER("Merger", Category.TRANSPORT, 1, c(IRON_PLATE to 5, CIRCUIT to 2), unlocked = false,
        desc = "Joins belts from the back and sides into one output."),
    INSERTER("Inserter", Category.TRANSPORT, 1, c(IRON_PLATE to 1, GEAR to 1, CIRCUIT to 1), power = 13f,
        desc = "Moves items from the tile behind it to the tile in front."),
    LONG_INSERTER("Long-arm Inserter", Category.TRANSPORT, 1, c(IRON_PLATE to 2, GEAR to 2, CIRCUIT to 1), power = 20f, unlocked = false,
        desc = "Reaches two tiles behind and two tiles ahead."),
    FAST_INSERTER("Fast Inserter", Category.TRANSPORT, 1, c(IRON_PLATE to 2, GEAR to 2, CIRCUIT to 2), power = 46f, unlocked = false,
        desc = "A much quicker inserter."),
    FILTER_INSERTER("Filter Inserter", Category.TRANSPORT, 1, c(IRON_PLATE to 2, GEAR to 2, CIRCUIT to 4), power = 46f, unlocked = false,
        desc = "Fast inserter that only moves the item you choose."),
    PIPE_SEG("Pipe", Category.TRANSPORT, 1, c(IRON_PLATE to 1), rotatable = false, lineBuild = true,
        desc = "Carries one kind of fluid. Connects to pumps, tanks and plants."),

    // ---- Power ----------------------------------------------------------------------
    POLE("Power Pole", Category.POWER, 1, c(IRON_PLATE to 1, COPPER_PLATE to 2), rotatable = false, lineBuild = true,
        desc = "Powers machines within 2 tiles and wires to poles up to 8 tiles away."),
    BIG_POLE("Power Tower", Category.POWER, 1, c(STEEL to 4, COPPER_PLATE to 4), rotatable = false, unlocked = false,
        desc = "Long-range wire (18 tiles) for moving power across the map."),
    COAL_GEN("Coal Generator", Category.POWER, 2, c(IRON_PLATE to 10, GEAR to 5, STONE_BRICK to 10), rotatable = false,
        desc = "Burns coal for 900 kW. Feed it coal by belt or inserter."),
    SOLAR("Solar Panel", Category.POWER, 2, c(IRON_PLATE to 5, COPPER_PLATE to 10, CIRCUIT to 3), rotatable = false,
        desc = "Up to 60 kW in daylight, nothing at night."),
    ACCUMULATOR("Accumulator", Category.POWER, 2, c(IRON_PLATE to 2, BATTERY to 5), rotatable = false, unlocked = false,
        desc = "Stores 5 MJ. Charges from surplus, discharges at night."),

    // ---- Processing -----------------------------------------------------------------
    STONE_FURNACE("Stone Furnace", Category.PROCESSING, 2, c(STONE to 5),
        desc = "Burns coal to smelt ore. Picks its recipe from what you feed it."),
    ELECTRIC_FURNACE("Electric Furnace", Category.PROCESSING, 2, c(STEEL to 10, ADV_CIRCUIT to 5, STONE_BRICK to 10), power = 180f, unlocked = false,
        desc = "Twice as fast as a stone furnace, needs no fuel."),
    ASSEMBLER_1("Assembler Mk1", Category.PROCESSING, 3, c(IRON_PLATE to 9, GEAR to 5, CIRCUIT to 3), power = 75f,
        desc = "Crafts parts and science. Speed 0.5."),
    ASSEMBLER_2("Assembler Mk2", Category.PROCESSING, 3, c(STEEL to 10, ENGINE to 2, CIRCUIT to 5), power = 150f, unlocked = false,
        desc = "Speed 0.75."),
    ASSEMBLER_3("Assembler Mk3", Category.PROCESSING, 3, c(MACHINE_FRAME to 2, ADV_CIRCUIT to 4), power = 375f, unlocked = false,
        desc = "Speed 1.25."),
    ELECTRONICS("Electronics Factory", Category.PROCESSING, 3, c(MACHINE_FRAME to 1, CIRCUIT to 10, STEEL to 10), power = 250f, unlocked = false,
        desc = "Specialised circuit plant. Speed 1.5 on circuits and chips."),
    CHEM_PLANT("Chemical Plant", Category.PROCESSING, 3, c(STEEL to 5, GEAR to 5, CIRCUIT to 5, PIPE to 5), power = 210f, unlocked = false,
        desc = "Mixes fluids and solids: plastic, sulfur, acid, batteries, cracking."),
    REFINERY("Oil Refinery", Category.PROCESSING, 3, c(STEEL to 15, GEAR to 10, CIRCUIT to 10, PIPE to 10, STONE_BRICK to 10), power = 420f, unlocked = false,
        desc = "Splits crude oil into petroleum gas, light oil and heavy oil. Give each output its own pipe."),

    // ---- Storage --------------------------------------------------------------------
    CHEST_1("Iron Chest", Category.STORAGE, 1, c(IRON_PLATE to 8), rotatable = false,
        desc = "Holds 400 items."),
    CHEST_2("Steel Chest", Category.STORAGE, 1, c(STEEL to 8), rotatable = false, unlocked = false,
        desc = "Holds 1200 items."),
    CHEST_3("Vault", Category.STORAGE, 2, c(STEEL to 20, CIRCUIT to 10), rotatable = false, unlocked = false,
        desc = "Holds 6000 items."),
    TANK("Storage Tank", Category.STORAGE, 2, c(IRON_PLATE to 20, STEEL to 5), rotatable = false, unlocked = false,
        desc = "Stores 2500 units of one fluid."),

    // ---- Research -------------------------------------------------------------------
    LAB("Research Lab", Category.RESEARCH, 2, c(CIRCUIT to 10, GEAR to 10, IRON_PLATE to 10), power = 60f, rotatable = false,
        desc = "Consumes science packs to research the selected technology."),

    // ---- Special ----------------------------------------------------------------------
    CORE("Factory Core", null, 4, emptyList(), rotatable = false,
        desc = "Your headquarters. Every item delivered here becomes building stock. Supplies 400 kW."),
    ;

    val isInserter get() = this == INSERTER || this == LONG_INSERTER || this == FAST_INSERTER || this == FILTER_INSERTER
    val isBeltLike get() = this == BELT || this == UNDERGROUND
    val color get() = category?.color ?: 0xFFB703

    companion object {
        val ALL = entries.toTypedArray()
        fun inCategory(c: Category) = ALL.filter { it.category == c }
    }
}

private fun c(vararg p: Pair<Item, Int>) = p.map { Stack(it.first, it.second) }
