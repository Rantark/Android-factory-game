package io.github.rantark.factoryflow.data

import io.github.rantark.factoryflow.data.BuildingType as B
import io.github.rantark.factoryflow.data.Item.*

/** Global modifiers that technologies can raise. */
enum class Bonus { BELT_SPEED, MINING_SPEED, LAB_SPEED, SOLAR_OUTPUT, INSERTER_SPEED, CRAFT_SPEED }

/**
 * One node in the tech tree.
 * @param packs science packs consumed per research unit (one of each).
 * @param units number of units needed.
 * @param unitTime seconds per unit in a lab at speed 1.
 * @param col/row layout position in the tech-tree panel.
 */
class Tech(
    val id: String,
    val title: String,
    val packs: List<Item>,
    val units: Int,
    val unitTime: Float,
    val prereqs: List<String>,
    val col: Int,
    val row: Float,
    val buildings: List<B> = emptyList(),
    val recipes: List<String> = emptyList(),
    val bonus: Pair<Bonus, Float>? = null,
    val desc: String = "",
) {
    var index = 0
        internal set

    /** Short "what you get" text for the tech panel. */
    val unlockText: String
        get() {
            val parts = ArrayList<String>()
            buildings.forEach { parts.add(it.title) }
            recipes.forEach { parts.add(Recipes[it].title) }
            bonus?.let { (b, v) ->
                val pct = (v * 100).toInt()
                parts.add(when (b) {
                    Bonus.BELT_SPEED -> "Belt speed +$pct%"
                    Bonus.MINING_SPEED -> "Mining speed +$pct%"
                    Bonus.LAB_SPEED -> "Lab speed +$pct%"
                    Bonus.SOLAR_OUTPUT -> "Solar output +$pct%"
                    Bonus.INSERTER_SPEED -> "Inserter speed +$pct%"
                    Bonus.CRAFT_SPEED -> "Crafting speed +$pct%"
                })
            }
            if (parts.isEmpty() && desc.isNotEmpty()) parts.add(desc)
            return parts.joinToString(", ")
        }
}

object Techs {
    private val R = listOf(SCI_RED)
    private val RG = listOf(SCI_RED, SCI_GREEN)
    private val RGB = listOf(SCI_RED, SCI_GREEN, SCI_BLUE)
    private val RGBP = listOf(SCI_RED, SCI_GREEN, SCI_BLUE, SCI_PURPLE)
    private val ALL5 = listOf(SCI_RED, SCI_GREEN, SCI_BLUE, SCI_PURPLE, SCI_YELLOW)

    val ALL: List<Tech> = listOf(
        // Column 0 – red science only
        Tech("logistics", "Logistics", R, 10, 5f, emptyList(), 0, 0f, buildings = listOf(B.UNDERGROUND, B.SPLITTER, B.MERGER)),
        Tech("automation", "Automation", R, 10, 5f, emptyList(), 0, 1f, buildings = listOf(B.LONG_INSERTER)),
        Tech("steel", "Steel Processing", R, 20, 5f, emptyList(), 0, 2f, buildings = listOf(B.CHEST_2), recipes = listOf("steel")),
        Tech("logistic_science", "Logistic Science", R, 30, 5f, emptyList(), 0, 3f, recipes = listOf("sci_green")),
        Tech("mining_1", "Mining Productivity 1", R, 30, 10f, emptyList(), 0, 4f, bonus = Bonus.MINING_SPEED to 0.2f),
        // Column 1 – red + green
        Tech("logistics_2", "Logistics 2", RG, 40, 10f, listOf("logistics", "logistic_science"), 1, 0f, bonus = Bonus.BELT_SPEED to 0.5f),
        Tech("fast_inserter", "Fast Inserter", RG, 30, 10f, listOf("automation", "logistic_science"), 1, 1f, buildings = listOf(B.FAST_INSERTER)),
        Tech("engine", "Engines", RG, 40, 10f, listOf("steel", "logistic_science"), 1, 2f, recipes = listOf("engine")),
        Tech("fluid_handling", "Fluid Handling", RG, 30, 10f, listOf("logistic_science"), 1, 3f, buildings = listOf(B.TANK)),
        Tech("electric_distribution", "Power Towers", RG, 40, 10f, listOf("steel", "logistic_science"), 1, 4f, buildings = listOf(B.BIG_POLE)),
        Tech("lab_speed", "Research Speed", RG, 60, 15f, listOf("logistic_science"), 1, 5f, bonus = Bonus.LAB_SPEED to 0.5f),
        // Column 2
        Tech("automation_2", "Automation 2", RG, 60, 10f, listOf("engine"), 2, 1f, buildings = listOf(B.ASSEMBLER_2)),
        Tech("inserter_speed", "Inserter Capacity", RG, 50, 15f, listOf("fast_inserter"), 2, 0f, bonus = Bonus.INSERTER_SPEED to 0.3f),
        Tech("oil_processing", "Oil Processing", RG, 60, 10f, listOf("fluid_handling", "engine"), 2, 3f,
            buildings = listOf(B.PUMP_JACK, B.REFINERY), recipes = listOf("oil_processing")),
        Tech("machine_frames", "Machine Frames", RG, 60, 15f, listOf("engine"), 2, 2f, recipes = listOf("machine_frame")),
        Tech("vault", "Vault Storage", RG, 50, 10f, listOf("steel", "logistic_science"), 2, 5f, buildings = listOf(B.CHEST_3)),
        // Column 3
        Tech("plastics", "Plastics", RG, 60, 15f, listOf("oil_processing"), 3, 2.5f, buildings = listOf(B.CHEM_PLANT), recipes = listOf("plastic")),
        Tech("sulfur", "Sulfur Processing", RG, 60, 15f, listOf("oil_processing"), 3, 3.5f, recipes = listOf("sulfur", "acid")),
        Tech("adv_electronics", "Advanced Electronics", RG, 80, 15f, listOf("plastics", "machine_frames"), 3, 1.5f,
            buildings = listOf(B.ELECTRONICS), recipes = listOf("adv_circuit")),
        Tech("batteries", "Batteries", RG, 80, 15f, listOf("sulfur"), 3, 4.5f, recipes = listOf("battery")),
        // Column 4 – blue science
        Tech("chemical_science", "Chemical Science", RG, 100, 15f, listOf("adv_electronics", "sulfur"), 4, 2f, recipes = listOf("sci_blue")),
        Tech("energy_storage", "Energy Storage", RG, 100, 15f, listOf("batteries"), 4, 4.5f, buildings = listOf(B.ACCUMULATOR)),
        Tech("oil_cracking", "Oil Cracking", RGB, 80, 20f, listOf("chemical_science"), 4, 3.5f, recipes = listOf("heavy_cracking", "light_cracking")),
        Tech("filter_inserter", "Filter Inserter", RGB, 60, 15f, listOf("chemical_science", "fast_inserter"), 4, 0.5f, buildings = listOf(B.FILTER_INSERTER)),
        // Column 5
        Tech("adv_material", "Advanced Smelting", RGB, 100, 20f, listOf("chemical_science"), 5, 1f, buildings = listOf(B.ELECTRIC_FURNACE)),
        Tech("logistics_3", "Logistics 3", RGB, 150, 20f, listOf("chemical_science", "logistics_2"), 5, 0f, bonus = Bonus.BELT_SPEED to 0.5f),
        Tech("production_science", "Production Science", RGB, 120, 20f, listOf("chemical_science"), 5, 2f, recipes = listOf("sci_purple")),
        Tech("crystal_refining", "Crystal Refining", RGB, 150, 20f, listOf("chemical_science", "batteries"), 5, 3.5f, recipes = listOf("crystal_shard")),
        Tech("mining_2", "Mining Productivity 2", RGB, 150, 20f, listOf("mining_1", "chemical_science"), 5, 5f, bonus = Bonus.MINING_SPEED to 0.3f),
        // Column 6 – purple
        Tech("automation_3", "Automation 3", RGBP, 150, 25f, listOf("production_science", "adv_material"), 6, 1f, buildings = listOf(B.ASSEMBLER_3)),
        Tech("quantum", "Quantum Computing", RGBP, 200, 25f, listOf("production_science", "crystal_refining"), 6, 2.5f, recipes = listOf("quantum_chip")),
        Tech("crafting_speed", "Precision Assembly", RGBP, 200, 25f, listOf("production_science"), 6, 4f, bonus = Bonus.CRAFT_SPEED to 0.25f),
        // Column 7 – yellow
        Tech("utility_science", "Utility Science", RGBP, 200, 30f, listOf("quantum"), 7, 2.5f, recipes = listOf("sci_yellow")),
        // Column 8 – everything
        Tech("solar_efficiency", "Photon Harvesting", ALL5, 300, 30f, listOf("utility_science"), 8, 1.5f, bonus = Bonus.SOLAR_OUTPUT to 0.5f),
        Tech("hyperbelt", "Hyper Belts", ALL5, 400, 30f, listOf("utility_science", "logistics_3"), 8, 0.5f, bonus = Bonus.BELT_SPEED to 1.0f),
        Tech("ascension", "Factory Ascension", ALL5, 1000, 30f, listOf("solar_efficiency", "hyperbelt", "crafting_speed"), 9, 2.5f,
            desc = "The final research. Your factory runs itself."),
    ).also { list -> list.forEachIndexed { i, t -> t.index = i } }

    private val byId = ALL.associateBy { it.id }
    operator fun get(id: String) = byId.getValue(id)
}
