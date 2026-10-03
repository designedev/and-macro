package com.personal.screenmacro.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MacroEngineTest {
    private val macro = Macro(query = "확인")
    private class FakePort(val scheduler: TestCoroutineScheduler) : EnginePort {
        val deliveries = mutableListOf<Long>()
        val states = mutableListOf<EngineState>()
        var exists = true
        var ambiguous = false
        var alive = true
        var cancelledGesture = false
        var latency = 80L
        var observations = 0
        var overlayChanges = 0
        var notificationPauses = 0
        var notificationAtValidation = 0
        var recognitionDelay = 0L
        val recognitionDelays = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        var beforeTap: (() -> Unit)? = null
        override fun now() = scheduler.currentTime
        override suspend fun pause(ms: Long) { delay(ms) }
        override suspend fun observe(macro: Macro): Observation {
            if (notificationPauses > 0) { notificationPauses--; throw SystemUiInterruptedException() }
            if (overlayChanges > 0) { overlayChanges--; throw ObservationChangedException() }
            observations++
            val frameTime = now()
            delay(if (recognitionDelays.isNotEmpty()) recognitionDelays.removeAt(0) else recognitionDelay)
            val match = if (ambiguous) MatchResult.Ambiguous(2) else if (exists) MatchResult.Unique(Box(10f, 20f, 30f, 40f)) else MatchResult.Absent
            return Observation("session", frameTime, 100, 100, 0, WindowStamp("com.example.target", 1, 1), match)
        }
        override fun valid(observation: Observation): Boolean {
            if (notificationAtValidation > 0) { notificationAtValidation--; throw SystemUiInterruptedException() }
            return alive
        }
        override suspend fun tap(observation: Observation, onDelivery: (Long) -> Unit): Boolean {
            beforeTap?.invoke()
            currentCoroutineContext().ensureActive()
            if (!alive) return false
            onDelivery(now()); deliveries += now()
            delay(latency)
            return !cancelledGesture
        }
        override fun status(state: EngineState, message: String) { states += state }
        override fun log(result: String, durationMs: Long, error: String?) { logs += result }
    }
    @Test fun persistentElementRepeatsWithDefaultInterval() = runTest {
        val p = FakePort(testScheduler); val job = launch { MacroEngine(p).run(macro) }
        advanceTimeBy(15001); runCurrent(); job.cancelAndJoin()
        assertEquals(listOf(0L, 3000L, 6000L, 9000L, 12000L, 15000L), p.deliveries)
    }
    @Test fun defaultIntervalFiftyDeliveries() = runTest {
        val p = FakePort(testScheduler)
        val job = launch { MacroEngine(p).run(macro) }
        advanceTimeBy(150001); runCurrent(); job.cancelAndJoin()
        assertTrue(p.deliveries.size >= 50)
        assertTrue(p.deliveries.zipWithNext().all { (a, b) -> b - a >= 3000 })
    }
    @Test fun minimumIntervalRemainsValidForFiftyDeliveriesIncludingCancellation() = runTest {
        val p = FakePort(testScheduler).apply { cancelledGesture = true }
        val job = launch { MacroEngine(p).run(macro.copy(intervalMs = 1000)) }
        advanceTimeBy(51001); runCurrent(); job.cancelAndJoin()
        assertTrue(p.deliveries.size >= 50)
        assertTrue(p.deliveries.zipWithNext().all { (a, b) -> b - a >= 1000 })
    }
    @Test fun absentAndAmbiguousNeverTap() = runTest {
        val p = FakePort(testScheduler).apply { exists = false }
        val job = launch { MacroEngine(p).run(macro) }
        advanceTimeBy(3000); p.exists = true; p.ambiguous = true
        advanceTimeBy(3000); job.cancelAndJoin()
        assertTrue(p.deliveries.isEmpty())
    }
    @Test fun reappearanceHonorsLastDelivery() = runTest {
        val p = FakePort(testScheduler)
        val job = launch { MacroEngine(p).run(macro) }
        runCurrent(); advanceTimeBy(100); p.exists = false
        advanceTimeBy(2000); p.exists = true
        advanceTimeBy(901); runCurrent(); job.cancelAndJoin()
        assertEquals(listOf(0L, 3000L), p.deliveries)
    }
    @Test fun preDelayRechecksElementAndCancellationPreventsDelivery() = runTest {
        val p = FakePort(testScheduler)
        val job = launch { MacroEngine(p).run(macro.copy(preDelayMs = 1000)) }
        runCurrent(); advanceTimeBy(500); p.exists = false
        advanceTimeBy(700); job.cancelAndJoin()
        assertEquals(2, p.observations); assertTrue(p.deliveries.isEmpty())
        val p2 = FakePort(testScheduler); val j2 = launch { MacroEngine(p2).run(macro.copy(preDelayMs = 1000)) }
        runCurrent(); advanceTimeBy(100); j2.cancelAndJoin(); advanceTimeBy(5000)
        assertTrue(p2.deliveries.isEmpty())
    }
    @Test fun postDelayBeginsAtGestureCompletionAndNoBacklog() = runTest {
        val p = FakePort(testScheduler).apply { latency = 1700 }
        val job = launch { MacroEngine(p).run(macro.copy(intervalMs = 1000, postDelayMs = 500)) }
        advanceTimeBy(6601); runCurrent(); job.cancelAndJoin()
        assertEquals(listOf(0L, 2200L, 4400L, 6600L), p.deliveries)
    }
    @Test fun stopAtDispatchBoundaryAndSingleEngineGuard() = runTest {
        val p = FakePort(testScheduler)
        lateinit var job: Job
        p.beforeTap = { job.cancel() }
        job = launch { MacroEngine(p).run(macro) }
        runCurrent(); job.join(); assertTrue(p.deliveries.isEmpty())
        val p2 = FakePort(testScheduler); val engine = MacroEngine(p2)
        val first = launch { engine.run(macro) }; runCurrent()
        var rejected = false
        try { engine.run(macro) } catch (_: IllegalStateException) { rejected = true }
        first.cancelAndJoin(); assertTrue(rejected)
    }
    @Test fun invalidSessionNeverDispatches() = runTest {
        val p = FakePort(testScheduler).apply { alive = false }
        assertEquals("SESSION_INVALID", runCatching { MacroEngine(p).run(macro) }.exceptionOrNull()?.message)
        assertTrue(p.deliveries.isEmpty())
    }
    @Test fun slowFirstRecognitionIsDiscardedThenFreshResultCanClick() = runTest {
        val p = FakePort(testScheduler).apply { recognitionDelays += 1500L }
        val job = launch { MacroEngine(p).run(macro) }
        advanceTimeBy(2001); runCurrent(); job.cancelAndJoin()
        assertEquals(listOf(2000L), p.deliveries)
        assertEquals(1, p.logs.count { it == "FRAME_EXPIRED" })
        assertFalse(p.logs.contains("RECOGNITION_ERROR"))
    }
    @Test fun continuouslySlowRecognitionNeverDispatchesAndKeepsRetrying() = runTest {
        val p = FakePort(testScheduler).apply { recognitionDelay = 1500 }
        val job = launch { MacroEngine(p).run(macro) }
        advanceTimeBy(7501); runCurrent()
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertTrue(p.deliveries.isEmpty())
        assertEquals(4, p.logs.count { it == "FRAME_EXPIRED" })
    }
    @Test fun slowAbsentResultDoesNotInvalidateSession() = runTest {
        val p = FakePort(testScheduler).apply { recognitionDelay = 1500; exists = false }
        val job = launch { MacroEngine(p).run(macro) }
        advanceTimeBy(6001); runCurrent()
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertTrue(p.deliveries.isEmpty())
        assertTrue(p.logs.isEmpty())
    }
    @Test fun expiredRecheckAfterPreDelayNeverClicksItsCoordinates() = runTest {
        val p = FakePort(testScheduler).apply { recognitionDelays += listOf(0L, 1500L) }
        val job = launch { MacroEngine(p).run(macro.copy(preDelayMs = 1000)) }
        advanceTimeBy(4001); runCurrent(); job.cancelAndJoin()
        assertEquals(listOf(4000L), p.deliveries)
        assertEquals(1, p.logs.count { it == "FRAME_EXPIRED" })
    }
    @Test fun freshnessRejectsFutureAndOlderFrames() {
        val o = Observation("session", 1000, 100, 100, 0, WindowStamp("com.example", 1, 1), MatchResult.Absent)
        assertFalse(o.isFresh(999)); assertTrue(o.isFresh(1000)); assertTrue(o.isFresh(2000)); assertFalse(o.isFresh(2001))
    }
    @Test fun repeatedOverlayMovesRetryWithoutRecognitionErrorsOrStopping() = runTest {
        val p = FakePort(testScheduler).apply { overlayChanges = 5 }
        val job = launch { MacroEngine(p).run(macro) }
        advanceTimeBy(399); assertTrue(p.deliveries.isEmpty())
        advanceTimeBy(102); runCurrent(); assertTrue(job.isActive)
        assertTrue(p.deliveries.isNotEmpty()); assertFalse(p.logs.contains("RECOGNITION_ERROR"))
        job.cancelAndJoin()
    }
    @Test fun notificationWaitDoesNotBecomeThreeRecognitionErrorsAndResumesFresh() = runTest {
        val p=FakePort(testScheduler); p.notificationPauses=8
        val job=launch { MacroEngine(p).run(macro) }
        advanceTimeBy(1900); runCurrent(); assertTrue(p.deliveries.isEmpty())
        advanceTimeBy(3000); runCurrent(); job.cancelAndJoin()
        assertTrue(p.deliveries.isNotEmpty())
        assertTrue(p.deliveries.all { it>=2000 })
        assertFalse(p.logs.contains("RECOGNITION_ERROR"))
    }
    @Test fun notificationDuringResultValidationDiscardsItAndCancellationStopsWaiting() = runTest {
        val p=FakePort(testScheduler); p.notificationAtValidation=4
        val job=launch { MacroEngine(p).run(macro) }
        advanceTimeBy(900); runCurrent(); assertTrue(p.deliveries.isEmpty())
        advanceTimeBy(1600); runCurrent(); assertTrue(p.deliveries.isNotEmpty())
        p.notificationPauses=100
        job.cancelAndJoin(); val count=p.deliveries.size
        advanceTimeBy(5000); assertEquals(count,p.deliveries.size)
        assertFalse(p.logs.contains("RECOGNITION_ERROR"))
    }

}
