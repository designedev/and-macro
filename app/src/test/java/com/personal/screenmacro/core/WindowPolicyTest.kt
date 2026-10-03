package com.personal.screenmacro.core

import org.junit.Assert.*
import org.junit.Test

class WindowPolicyTest {
    private val game = WindowLayer(552, WindowKind.APPLICATION, "com.nexon.devcat.mmgalaxy", null, 0, Box(0f, 0f, 1080f, 2340f), active = true, focused = true)
    private val panel = WindowLayer(548, WindowKind.APPLICATION, game.packageName, game.id, 1, Box(0f, 112f, 1f, 2340f))
    private val bar = WindowLayer(560, WindowKind.OTHER, null, null, 2, Box(0f, 0f, 1080f, 112f))
    private val accessibilityButton = WindowLayer(563, WindowKind.OTHER, null, null, 3, Box(889f, 1324f, 1080f, 1515f))
    @Test fun actualDeviceWindowLayoutAllowsGameAndExcludesObscuredRectangles() {
        val windows = listOf(game, panel, bar, accessibilityButton)
        assertFalse(windows.any { WindowPolicy.blocks(it, game, windows) })
        val excluded = WindowPolicy.exclusions(game, windows)
        assertEquals(listOf(panel.bounds, bar.bounds, accessibilityButton.bounds), excluded)
        assertTrue(excluded.any { it.intersects(Box(920f, 1350f, 1020f, 1450f)) })
        assertFalse(excluded.any { it.intersects(Box(300f, 1800f, 700f, 1900f)) })
    }
    @Test fun childWithUnavailableRootStillRequiresVerifiedParent() {
        val child = panel.copy(packageName = null)
        val windows = listOf(game, child)
        assertFalse(WindowPolicy.blocks(child, game, windows))
        assertTrue(WindowPolicy.blocks(child.copy(parentId = null), game, windows))
        assertTrue(WindowPolicy.blocks(child.copy(packageName = "other.app"), game, windows))
    }
    @Test fun keyboardAndUnrelatedApplicationStillBlock() {
        val keyboard = bar.copy(kind = WindowKind.INPUT_METHOD)
        val foreign = panel.copy(packageName = "other.app", parentId = null)
        val unrelatedSamePackage = panel.copy(parentId = null)
        listOf(keyboard, foreign, unrelatedSamePackage).forEach {
            assertTrue(WindowPolicy.blocks(it, game, listOf(game, it)))
        }
    }
    @Test fun focusedOrActiveSystemUiBlocksWhilePassiveFullscreenUiExcludesEverything() {
        assertTrue(WindowPolicy.blocks(bar.copy(active = true), game, listOf(game, bar)))
        assertTrue(WindowPolicy.blocks(bar.copy(focused = true), game, listOf(game, bar)))
        val full = bar.copy(bounds = game.bounds)
        assertFalse(WindowPolicy.blocks(full, game, listOf(game, full)))
        assertTrue(WindowPolicy.exclusions(game, listOf(game, full)).any { it.intersects(Box(300f, 1800f, 700f, 1900f)) })
    }
    @Test fun ownOverlayIsHandledSeparatelyAndCannotBlockSelection() {
        val overlay = bar.copy(ownOverlay = true, active = true)
        assertFalse(WindowPolicy.blocks(overlay, game, listOf(game, overlay)))
        assertTrue(WindowPolicy.exclusions(game, listOf(game, overlay)).isEmpty())
    }
    @Test fun invalidParentCyclesDoNotAllowUnrelatedPanels() {
        val a = panel.copy(parentId = 549)
        val b = panel.copy(id = 549, parentId = a.id)
        assertTrue(WindowPolicy.blocks(a, game, listOf(game, a, b)))
    }
}
