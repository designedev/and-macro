package com.personal.screenmacro

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.personal.screenmacro.core.Macro
import com.personal.screenmacro.ui.PriorityMacroList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PriorityDragTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun dragAtEdgeScrollsThroughOffscreenRowsAndCancelDoesNotSave() {
        val original = (0..7).map { Macro(id = "drag-$it", name = "항목 $it", query = "조건 $it", priority = it) }
        var saved: List<String>? = null
        var error: String? = null
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    PriorityMacroList(original, false, false, Modifier.fillMaxWidth().height(330.dp),
                        onSelect = { _, _ -> }, onReorder = { saved = it }, onInteraction = {}, onError = { error = it },
                        onStart = {}, onTest = {}, onEdit = {}, onDelete = {})
                }
            }
        }
        ui.onNodeWithTag("drag-drag-0").performTouchInput {
            down(center); moveBy(Offset(0f, 30f), delayMillis = 30); cancel()
        }
        ui.onNodeWithText("1순위 · 항목 0").assertExists()
        assertNull(saved)
        val height = ui.onNodeWithTag("priority-list").fetchSemanticsNode().boundsInRoot.height
        ui.onNodeWithTag("drag-drag-0").performTouchInput {
            down(center); moveBy(Offset(0f, 25f), delayMillis = 30)
            moveBy(Offset(0f, height * .8f - 25f), delayMillis = 150)
        }
        // Keep the finger down while real frames drive edge auto-scroll.
        ui.waitUntil(7000) { ui.onAllNodesWithText("8순위 · 항목 0").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("priority-list").performTouchInput { up() }
        ui.waitUntil(3000) { saved != null }
        assertNull(error)
        assertEquals(original.drop(1).map { it.id } + original.first().id, saved)
    }
}
