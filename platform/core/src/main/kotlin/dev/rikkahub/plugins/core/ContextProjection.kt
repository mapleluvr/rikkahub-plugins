package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.ContextItem
import dev.rikkahub.plugins.protocol.ProjectionPlan

/** Applies a bounded omission plan to a host-authenticated immutable frame. */
object ContextProjection {
    fun apply(source: List<ContextItem>, plan: ProjectionPlan): List<ContextItem> {
        val indexed = source.associateBy { it.id }
        require(indexed.size == source.size) { "Duplicate context identity" }
        require(plan.omit.all { it in indexed }) { "Projection references unknown input" }
        require(plan.omit.none { indexed.getValue(it).protected }) { "Projection crosses protected input" }
        source.filter { it.toolGroup != null }.groupBy { it.toolGroup }.values.forEach { group ->
            val omitted = group.count { it.id in plan.omit }
            require(omitted == 0 || omitted == group.size) { "Projection splits a tool group" }
        }
        return source.filterNot { it.id in plan.omit }
    }
}
