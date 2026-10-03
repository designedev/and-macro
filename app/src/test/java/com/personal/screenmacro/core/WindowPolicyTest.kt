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
    @Test fun passiveSystemBarsDoNotPauseButHeadsUpAndShadeDo() {
        val content=Box(0f,112f,1080f,2200f)
        val status=bar.copy(kind=WindowKind.SYSTEM_UI,packageName="com.android.systemui")
        val nav=status.copy(id=561,bounds=Box(0f,2200f,1080f,2340f))
        assertFalse(SystemUiPolicy.interrupts(listOf(game,status,nav),content))
        val headsUp=status.copy(bounds=Box(0f,0f,1080f,500f))
        assertTrue(SystemUiPolicy.interrupts(listOf(game,headsUp,nav),content))
        assertTrue(SystemUiPolicy.interrupts(listOf(game,status.copy(active=true)),content))
        assertTrue(SystemUiPolicy.interrupts(listOf(game,status.copy(focused=true)),content))
        // The underlying game may be absent while the expanded shade covers it.
        assertTrue(SystemUiPolicy.interrupts(listOf(status.copy(active=true,bounds=game.bounds)),content))
    }
    @Test fun unknownSystemWindowsAndForeignAppsAreNotGrantedNotificationException() {
        val content=Box(0f,112f,1080f,2200f)
        val unknown=bar.copy(active=true,bounds=game.bounds)
        assertFalse(SystemUiPolicy.interrupts(listOf(game,unknown),content))
        assertTrue(WindowPolicy.blocks(unknown,game,listOf(game,unknown)))
        val foreign=panel.copy(parentId=null,packageName="other.app",active=true,focused=true)
        assertFalse(SystemUiPolicy.interrupts(listOf(foreign,bar),content))
        assertTrue(WindowPolicy.blocks(foreign,game,listOf(game,foreign)))
        assertFalse(SystemUiPolicy.interrupts(listOf(game,bar.copy(kind=WindowKind.SYSTEM_UI,layer=-1)),content))
    }

    @Test fun galaxyPassiveAccessibilityButtonDoesNotPauseAndStillExcludesClicks() {
        val content = Box(0f, 112f, 1080f, 2200f)
        val button = accessibilityButton.copy(kind = WindowKind.SYSTEM_UI, packageName = "com.android.systemui")
        val status = bar.copy(kind = WindowKind.SYSTEM_UI, packageName = "com.android.systemui")
        val windows = listOf(game, status, button)
        assertFalse(SystemUiPolicy.interrupts(windows, content))
        assertFalse(windows.any { WindowPolicy.blocks(it, game, windows) })
        assertTrue(WindowPolicy.exclusions(game, windows).contains(button.bounds))
        assertTrue(SystemUiPolicy.interrupts(listOf(game, button.copy(active = true)), content))
        assertTrue(SystemUiPolicy.interrupts(listOf(game, button.copy(focused = true)), content))
        assertTrue(SystemUiPolicy.interrupts(windows + status.copy(id = 700, bounds = Box(20f, 112f, 1060f, 500f)), content))
    }

    @Test fun landscapeNotificationStillPausesAlongsideCompactSystemControl() {
        val content = Box(0f, 80f, 2340f, 1080f)
        val app = game.copy(bounds = content)
        val button = accessibilityButton.copy(kind = WindowKind.SYSTEM_UI, bounds = Box(2149f, 700f, 2340f, 891f))
        val banner = bar.copy(kind = WindowKind.SYSTEM_UI, bounds = Box(800f, 80f, 1540f, 400f))
        assertFalse(SystemUiPolicy.interrupts(listOf(app, button), content))
        assertTrue(SystemUiPolicy.interrupts(listOf(app, button, banner), content))
    }

}
