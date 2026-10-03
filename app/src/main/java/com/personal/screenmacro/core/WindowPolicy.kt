package com.personal.screenmacro.core

enum class WindowKind { APPLICATION, INPUT_METHOD, SYSTEM_UI, OTHER }
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
            WindowKind.SYSTEM_UI, WindowKind.OTHER -> window.active || window.focused
        }
    }
    fun exclusions(target: WindowLayer, windows: List<WindowLayer>): List<Box> = windows
        .filter { it.id != target.id && !it.ownOverlay && it.layer > target.layer }
        .map { it.bounds }.filter { it.valid() }
}

/** Notification surfaces may briefly own focus; passive status/navigation bars do not. */
object SystemUiPolicy {
    fun interrupts(windows: List<WindowLayer>, content: Box): Boolean {
        val app = windows.filter { it.kind == WindowKind.APPLICATION && !it.ownOverlay }
            .firstOrNull { it.focused || it.active }
        return windows.any { window ->
            // SystemUI also owns passive floating accessibility controls. They only
            // exclude their rectangle from clicks; they must not pause the session.
            // Focused/active system windows still pause regardless of their size.
            val compactLimit = minOf(content.right - content.left, content.bottom - content.top) / 4f
            val compactFloatingControl = window.bounds.valid() &&
                window.bounds.right - window.bounds.left <= compactLimit
            window.kind == WindowKind.SYSTEM_UI && !window.ownOverlay &&
                (window.active || window.focused ||
                    (!compactFloatingControl && window.bounds.valid() && window.bounds.intersects(content) && (app == null || window.layer > app.layer)))
        }
    }
}
