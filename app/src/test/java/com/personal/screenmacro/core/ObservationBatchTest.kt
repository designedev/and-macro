package com.personal.screenmacro.core

import org.junit.Assert.*
import org.junit.Test

class ObservationBatchTest {
    private val first = Observation("session", 20, 100, 100, 0, WindowStamp("game", 1, 1), MatchResult.Unique(Box(1f, 2f, 10f, 20f)))
    @Test fun verifiesUnselectedResultsAndRejectsDifferentFramesWindowsAndOverlayRevisions() {
        val other = first.copy(result = MatchResult.Absent)
        assertTrue(listOf(first, other).sameFrame())
        assertFalse(emptyList<Observation>().sameFrame())
        listOf(other.copy(sessionId = "old"), other.copy(frameTime = 21), other.copy(width = 200),
            other.copy(rotation = 1), other.copy(window = first.window.copy(revision = 2)), other.copy(overlayRevision = 1),
            other.copy(result = MatchResult.Unique(Box(0f, 0f, 101f, 2f)))).forEach {
            assertFalse(listOf(first, it).sameFrame())
        }
    }
    @Test fun freshnessStillRejectsAnythingOverOneSecond() {
        assertTrue(first.isFresh(1020)); assertFalse(first.isFresh(1021))
        assertFalse(first.isFresh(19))
    }
    @Test fun finalGeometryRejectsOutsideAppAndObscuredCoordinates() {
        val app = WindowLayer(1, WindowKind.APPLICATION, "game", null, 0, Box(0f, 0f, 100f, 100f))
        val cover = app.copy(id = 2, kind = WindowKind.OTHER, layer = 1, bounds = Box(20f, 20f, 50f, 50f))
        assertTrue(WindowPolicy.canTap(app, listOf(app, cover), Box(1f, 1f, 10f, 10f), null))
        assertFalse(WindowPolicy.canTap(app, listOf(app, cover), Box(21f, 21f, 30f, 30f), null))
        assertFalse(WindowPolicy.canTap(app, listOf(app), Box(99f, 99f, 101f, 101f), null))
        assertFalse(WindowPolicy.canTap(app, listOf(app), Box(1f, 1f, 10f, 10f), Box(0f, 0f, 20f, 20f)))
    }
}
