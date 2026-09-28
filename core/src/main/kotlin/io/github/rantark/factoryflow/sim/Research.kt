package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.Bonus
import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Recipe
import io.github.rantark.factoryflow.data.Recipes
import io.github.rantark.factoryflow.data.Tech
import io.github.rantark.factoryflow.data.Techs

/** Tech-tree state: finished techs, current target, and the unlocks they grant. */
class Research(private val f: Factory) {
    val done = BooleanArray(Techs.ALL.size)
    val progress = IntArray(Techs.ALL.size)
    var current: Tech? = null
    /** True when the player picked [current] (otherwise it was auto-selected). */
    var chosen = false
    private val buildingUnlocked = BooleanArray(BuildingType.ALL.size) { BuildingType.ALL[it].unlocked }
    private val recipeUnlocked = BooleanArray(Recipes.ALL.size) { Recipes.ALL[it].unlocked }
    private val bonuses = FloatArray(Bonus.entries.size)

    fun buildingUnlocked(t: BuildingType) = buildingUnlocked[t.ordinal]
    fun recipeUnlocked(r: Recipe) = recipeUnlocked[r.index]
    fun bonus(b: Bonus) = bonuses[b.ordinal]

    fun available(t: Tech) = !done[t.index] && t.prereqs.all { done[Techs[it].index] }

    fun select(t: Tech) {
        if (!available(t)) return
        current = t; chosen = true
    }

    /** One research unit finished in some lab. */
    fun addUnit(t: Tech) {
        if (done[t.index]) return
        progress[t.index]++
        if (progress[t.index] >= t.units) complete(t)
    }

    fun complete(t: Tech, silent: Boolean = false) {
        done[t.index] = true
        progress[t.index] = t.units
        applyUnlocks(t)
        if (!silent) f.emit(GameEvent.RESEARCH_DONE, "Research complete: ${t.title}")
        if (current === t) { current = null; chosen = false }
        if (current == null) autoPick()
    }

    private fun applyUnlocks(t: Tech) {
        t.buildings.forEach { buildingUnlocked[it.ordinal] = true }
        t.recipes.forEach { recipeUnlocked[Recipes[it].index] = true }
        t.bonus?.let { (b, v) -> bonuses[b.ordinal] += v }
    }

    /** With nothing selected, labs work on the cheapest available tech. */
    fun autoPick() {
        current = Techs.ALL.filter { available(it) }
            .minByOrNull { it.packs.size * 1000 + it.units * it.unitTime }
        chosen = false
    }

    /** Rebuild derived unlock tables from [done] (after loading a save). */
    fun reapply() {
        for (i in buildingUnlocked.indices) buildingUnlocked[i] = BuildingType.ALL[i].unlocked
        for (i in recipeUnlocked.indices) recipeUnlocked[i] = Recipes.ALL[i].unlocked
        bonuses.fill(0f)
        for (t in Techs.ALL) if (done[t.index]) applyUnlocks(t)
    }
}
