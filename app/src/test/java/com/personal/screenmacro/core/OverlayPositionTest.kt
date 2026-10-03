package com.personal.screenmacro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    @Test fun offscreenPositionIsClampedInsideInsets() {
        assertEquals(OverlayPosition(8,28),OverlayPosition(-100,-100).constrained(8,28,1080,2300,240,180))
        assertEquals(OverlayPosition(840,2120),OverlayPosition(3000,4000).constrained(8,28,1080,2300,240,180))
    }
    @Test fun expandingNearEdgeKeepsStopButtonVisible() {
        val compact=OverlayPosition(852,2210)
        assertEquals(OverlayPosition(820,2090),compact.constrained(8,28,1080,2300,260,210))
    }
    @Test fun oversizedPanelAnchorsAtSafeOriginWithoutInvalidRange() {
        assertEquals(OverlayPosition(8,28),OverlayPosition(20,80).constrained(8,28,100,100,300,400))
    }
    @Test fun positionAlreadyWithinSafeAreaRemainsUnchanged() {
        assertEquals(OverlayPosition(120,340),OverlayPosition(120,340).constrained(8,28,1080,2300,240,180))
    }
}
