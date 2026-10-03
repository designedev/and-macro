package com.personal.screenmacro.core

enum class WindowKind { APPLICATION, INPUT_METHOD, OTHER }
data class WindowLayer(
    val id: Int, val kind: WindowKind, val packageName: String?, val parentId: Int?,
    val layer: Int, val bounds: Box, val active: Boolean = false, val focused: Boolean = false,
    val ownOverlay: Boolean = false
)

/** Passive system UI and child panels obscure rectangles, not the entire app. */
object WindowPolicy {
    private fun ownedChild(window: WindowLayer, target: WindowLayer, windows: List<WindowLayer>): Boolean {
        if (window.packageName != null && window.packageName != target.packageName) return false
        val visited = mutableSetOf(window.id)
        var parent = window.parentId
        while (parent != null && visited.add(parent)) {
            if (parent == target.id) return true
            val ancestor = windows.firstOrNull { it.id == parent } ?: return false
            if (ancestor.packageName != null && ancestor.packageName != target.packageName) return false
            parent = ancestor.parentId
        }
        return false
    }
    fun blocks(window: WindowLayer, target: WindowLayer, windows: List<WindowLayer>): Boolean {
        if (window.id == target.id || window.ownOverlay) return false
        return when (window.kind) {
            WindowKind.INPUT_METHOD -> true
            WindowKind.APPLICATION -> !ownedChild(window, target, windows)
            WindowKind.OTHER -> window.active || window.focused
        }
    }
    fun exclusions(target: WindowLayer, windows: List<WindowLayer>): List<Box> = windows
        .filter { it.id != target.id && !it.ownOverlay && it.layer > target.layer }
        .map { it.bounds }.filter { it.valid() }
}
