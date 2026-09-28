package io.github.rantark.factoryflow.data

import io.github.rantark.factoryflow.data.Fluid.*
import io.github.rantark.factoryflow.data.Item.*

/** Which family of machine can run a recipe. */
enum class MachineClass { SMELTER, ASSEMBLER, CHEMICAL, REFINERY, ELECTRONICS }

class Stack(val item: Item, val count: Int)
class FluidStack(val fluid: Fluid, val amount: Float)

/**
 * A crafting recipe. [time] is in seconds at crafting speed 1.
 * [unlocked] recipes are available from the start; others come from research.
 */
class Recipe(
    val id: String,
    val machines: Set<MachineClass>,
    val time: Float,
    val inputs: List<Stack>,
    val outputs: List<Stack>,
    val fluidIn: List<FluidStack> = emptyList(),
    val fluidOut: List<FluidStack> = emptyList(),
    val unlocked: Boolean = true,
) {
    var index = 0
        internal set

    /** Main product, used for the recipe's icon and name. */
    val mainItem: Item? get() = outputs.firstOrNull()?.item
    val mainFluid: Fluid? get() = fluidOut.firstOrNull()?.fluid
    val title: String get() = TITLES[id] ?: mainItem?.title ?: mainFluid?.title ?: id

    fun needs(item: Item): Int = inputs.firstOrNull { it.item == item }?.count ?: 0
    fun needsFluid(f: Fluid): Float = fluidIn.firstOrNull { it.fluid == f }?.amount ?: 0f

    companion object {
        private val TITLES = mapOf(
            "oil_processing" to "Oil Processing",
            "heavy_cracking" to "Heavy Oil Cracking",
            "light_cracking" to "Light Oil Cracking",
        )
    }
}

object Recipes {
    private val S = setOf(MachineClass.SMELTER)
    private val A = setOf(MachineClass.ASSEMBLER)
    private val AE = setOf(MachineClass.ASSEMBLER, MachineClass.ELECTRONICS)
    private val E = setOf(MachineClass.ELECTRONICS)
    private val C = setOf(MachineClass.CHEMICAL)
    private val R = setOf(MachineClass.REFINERY)

    private fun i(vararg p: Pair<Item, Int>) = p.map { Stack(it.first, it.second) }
    private fun f(vararg p: Pair<Fluid, Float>) = p.map { FluidStack(it.first, it.second) }

    val ALL: List<Recipe> = listOf(
        // ---- Tier 1: ore → plates ------------------------------------------------
        Recipe("iron_plate", S, 3.2f, i(IRON_ORE to 1), i(IRON_PLATE to 1)),
        Recipe("copper_plate", S, 3.2f, i(COPPER_ORE to 1), i(COPPER_PLATE to 1)),
        Recipe("stone_brick", S, 3.2f, i(STONE to 2), i(STONE_BRICK to 1)),
        // ---- Tier 2: plates + coal → steel, gears, wire ---------------------------
        Recipe("steel", S, 8f, i(IRON_PLATE to 3, COAL to 1), i(STEEL to 1), unlocked = false),
        Recipe("gear", A, 0.5f, i(IRON_PLATE to 2), i(GEAR to 1)),
        Recipe("wire", A, 0.5f, i(COPPER_PLATE to 1), i(WIRE to 2)),
        // ---- Tier 3: steel + wire → circuits, engines, pipes -----------------------
        Recipe("pipe", A, 0.5f, i(IRON_PLATE to 1), i(PIPE to 1)),
        Recipe("circuit", AE, 0.5f, i(IRON_PLATE to 1, WIRE to 3), i(CIRCUIT to 1)),
        Recipe("engine", A, 8f, i(STEEL to 1, GEAR to 1, PIPE to 2), i(ENGINE to 1), unlocked = false),
        // ---- Tier 4: circuits + engines → advanced parts ---------------------------
        Recipe("adv_circuit", E, 6f, i(CIRCUIT to 2, PLASTIC to 2, WIRE to 4), i(ADV_CIRCUIT to 1), unlocked = false),
        Recipe("machine_frame", A, 10f, i(STEEL to 2, ENGINE to 1, CIRCUIT to 3), i(MACHINE_FRAME to 1), unlocked = false),
        // ---- Chemistry --------------------------------------------------------------
        Recipe("oil_processing", R, 5f, emptyList(), emptyList(),
            fluidIn = f(CRUDE_OIL to 100f, WATER to 50f),
            fluidOut = f(PETROLEUM to 55f, LIGHT_OIL to 45f, HEAVY_OIL to 25f), unlocked = false),
        Recipe("plastic", C, 1f, i(COAL to 1), i(PLASTIC to 2), fluidIn = f(PETROLEUM to 20f), unlocked = false),
        Recipe("sulfur", C, 1f, emptyList(), i(SULFUR to 2), fluidIn = f(WATER to 30f, PETROLEUM to 30f), unlocked = false),
        Recipe("acid", C, 1f, i(SULFUR to 1, IRON_PLATE to 1), emptyList(),
            fluidIn = f(WATER to 100f), fluidOut = f(ACID to 50f), unlocked = false),
        Recipe("battery", C, 4f, i(IRON_PLATE to 1, COPPER_PLATE to 1), i(BATTERY to 1), fluidIn = f(ACID to 20f), unlocked = false),
        Recipe("heavy_cracking", C, 2f, emptyList(), emptyList(),
            fluidIn = f(HEAVY_OIL to 40f, WATER to 30f), fluidOut = f(LIGHT_OIL to 30f), unlocked = false),
        Recipe("light_cracking", C, 2f, emptyList(), emptyList(),
            fluidIn = f(LIGHT_OIL to 30f, WATER to 30f), fluidOut = f(PETROLEUM to 20f), unlocked = false),
        // ---- Tier 5: rare crystal → exotic components -----------------------------
        Recipe("crystal_shard", C, 4f, i(CRYSTAL to 1), i(CRYSTAL_SHARD to 2), fluidIn = f(ACID to 10f), unlocked = false),
        Recipe("quantum_chip", E, 10f, i(ADV_CIRCUIT to 1, CRYSTAL_SHARD to 2, BATTERY to 1), i(QUANTUM_CHIP to 1), unlocked = false),
        // ---- Science ----------------------------------------------------------------
        Recipe("sci_red", A, 5f, i(COPPER_PLATE to 1, GEAR to 1), i(SCI_RED to 1)),
        Recipe("sci_green", A, 6f, i(CIRCUIT to 1, GEAR to 1), i(SCI_GREEN to 1), unlocked = false),
        Recipe("sci_blue", A, 24f, i(ENGINE to 2, ADV_CIRCUIT to 3, SULFUR to 1), i(SCI_BLUE to 2), unlocked = false),
        Recipe("sci_purple", A, 21f, i(MACHINE_FRAME to 1, STEEL to 2, STONE_BRICK to 4), i(SCI_PURPLE to 3), unlocked = false),
        Recipe("sci_yellow", A, 21f, i(QUANTUM_CHIP to 2, BATTERY to 1, CRYSTAL_SHARD to 2), i(SCI_YELLOW to 3), unlocked = false),
    ).also { list -> list.forEachIndexed { idx, r -> r.index = idx } }

    private val byId = ALL.associateBy { it.id }
    operator fun get(id: String): Recipe = byId.getValue(id)
    fun find(id: String?): Recipe? = id?.let { byId[it] }

    fun forClass(c: MachineClass) = ALL.filter { c in it.machines }

    /** Furnaces pick their recipe automatically from whatever they are fed. */
    fun smeltingFor(item: Item, unlocked: (Recipe) -> Boolean): Recipe? =
        ALL.firstOrNull { MachineClass.SMELTER in it.machines && it.inputs.first().item == item && unlocked(it) }
}
