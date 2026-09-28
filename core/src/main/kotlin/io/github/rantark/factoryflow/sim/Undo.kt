package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.BuildingType

/** One reversible change: a building that was placed or removed. */
class UndoOp(val placed: Boolean, val type: BuildingType, val x: Int, val y: Int, val dir: Int, val config: String?)

/** Remembers the last [LIMIT] player actions (a dragged belt line counts as one). */
class UndoHistory(private val f: Factory) {
    private val actions = ArrayDeque<List<UndoOp>>()
    val size get() = actions.size

    fun record(ops: List<UndoOp>) {
        if (ops.isEmpty()) return
        actions.addLast(ops)
        while (actions.size > LIMIT) actions.removeFirst()
    }

    /** Revert the most recent action. Returns a short description, or null if none. */
    fun undo(): String? {
        val ops = actions.removeLastOrNull() ?: return null
        var n = 0
        for (op in ops.asReversed()) {
            if (op.placed) {
                val b = f.at(op.x, op.y)
                if (b != null && b.type == op.type && b.x == op.x && b.y == op.y) { f.remove(b); n++ }
            } else {
                val b = f.place(op.type, op.x, op.y, op.dir)
                if (b != null) { b.applyConfig(op.config); n++ }
            }
        }
        val verb = if (ops.first().placed) "Removed" else "Restored"
        return "$verb ${if (ops.size == 1) ops[0].type.title else "$n buildings"}"
    }

    fun clear() = actions.clear()

    companion object { const val LIMIT = 10 }
}
