package com.personal.screenmacro.core

import org.junit.Assert.*
import org.junit.Test

class BrightnessSessionTest {
    private class Port : BrightnessPort {
        var permission = true
        var state = BrightnessSnapshot(183, 1)
        var journal: BrightnessSnapshot? = null
        var saveWorks = true
        var failLevel: Int? = null
        var failMode: Int? = null
        var clearWorks = true
        val writes = mutableListOf<String>()
        override fun allowed() = permission
        override fun current() = state
        override fun pending() = journal
        override fun save(snapshot: BrightnessSnapshot): Boolean { if (!saveWorks) return false; journal = snapshot; writes += "save"; return true }
        override fun clear(): Boolean { if (!clearWorks) return false; journal = null; writes += "clear"; return true }
        override fun level(value: Int): Boolean { writes += "level:$value"; if (failLevel == value) return false; state = state.copy(level = value); return true }
        override fun mode(value: Int): Boolean { writes += "mode:$value"; if (failMode == value) return false; state = state.copy(mode = value); return true }
    }
    @Test fun minimumIsJournaledBeforeWritesAndBothOriginalSettingsReturn() {
        val p = Port(); val session = BrightnessSession(p)
        session.begin()
        assertEquals(BrightnessSnapshot(1, 0), p.state)
        assertEquals(listOf("save", "mode:0", "level:1"), p.writes)
        assertEquals(BrightnessSnapshot(183, 1), p.journal)
        assertTrue(session.restore()); assertEquals(BrightnessSnapshot(183, 1), p.state)
        assertNull(p.journal); assertTrue(session.restore())
    }
    @Test fun deniedPermissionAndJournalFailureNeverChangeBrightness() {
        val p = Port(); p.permission = false
        assertThrows(IllegalStateException::class.java) { BrightnessSession(p).begin() }
        assertTrue(p.writes.isEmpty()); assertNull(p.journal)
        p.permission = true; p.saveWorks = false
        assertThrows(IllegalStateException::class.java) { BrightnessSession(p).begin() }
        assertTrue(p.writes.isEmpty()); assertEquals(BrightnessSnapshot(183,1), p.state)
    }
    @Test fun partialStartFailureRollsBackModeAndLevel() {
        val p = Port(); p.failLevel = 1
        assertThrows(IllegalStateException::class.java) { BrightnessSession(p).begin() }
        assertEquals(BrightnessSnapshot(183,1), p.state); assertNull(p.journal)
    }
    @Test fun processRestartUsesStoredOriginalRatherThanMinimum() {
        val p = Port(); BrightnessSession(p).begin()
        assertTrue(BrightnessSession(p).restore())
        assertEquals(BrightnessSnapshot(183,1), p.state); assertNull(p.journal)
    }
    @Test fun permissionRevocationRetainsRecoveryUntilPermissionReturns() {
        val p = Port(); val session = BrightnessSession(p); session.begin()
        p.permission = false; assertFalse(session.restore()); assertNotNull(p.journal)
        p.permission = true; assertTrue(session.restore()); assertEquals(BrightnessSnapshot(183,1), p.state)
    }
    @Test fun failedRestoreStillTriesBothAndKeepsOriginalForRetry() {
        val p = Port(); val session = BrightnessSession(p); session.begin(); p.failLevel = 183
        assertFalse(session.restore()); assertEquals(1,p.state.mode); assertNotNull(p.journal)
        p.failLevel = null; assertTrue(session.restore()); assertEquals(BrightnessSnapshot(183,1),p.state)
    }
    @Test fun failedJournalClearCanRetryAndDuplicateBeginCannotOverwriteOriginal() {
        val p = Port(); val session = BrightnessSession(p); session.begin()
        assertThrows(IllegalStateException::class.java) { session.begin() }
        assertEquals(BrightnessSnapshot(183,1),p.journal)
        p.clearWorks = false; assertFalse(session.restore()); assertNotNull(p.journal)
        p.clearWorks = true; assertTrue(session.restore())
    }
    @Test fun manualRestoreAndExitRestoreAreIdempotent() {
        val p = Port(); p.state = BrightnessSnapshot(84,0)
        val session = BrightnessSession(p); session.begin(); assertTrue(session.restore())
        val count=p.writes.size; assertTrue(session.restore()); assertEquals(count,p.writes.size)
        assertEquals(BrightnessSnapshot(84,0),p.state)
    }
}
