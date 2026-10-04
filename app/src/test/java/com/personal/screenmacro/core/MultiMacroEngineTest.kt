package com.personal.screenmacro.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiMacroEngineTest {
    private val high = Macro(id = "high", query = "입장하기", priority = 0)
    private val low = Macro(id = "low", query = "확인", priority = 1)
    private data class Delivery(val id: String, val at: Long, val frame: Long)
    private class FakePort(val clock: TestCoroutineScheduler) : MultiEnginePort {
        val deliveries = mutableListOf<Delivery>()
        val logs = mutableListOf<String>()
        val results = mutableMapOf<String, MatchResult>()
        var observations = 0L
        var overlayChanges = 0
        var notificationPauses = 0
        var notificationAtValidation = 0
        var alive = true
        var latency = 80L
        var recognitionLatency = 0L
        var inGesture = 0
        var failRecognition = false
        var cancelledGesture = false
        var onTap: ((Macro) -> Unit)? = null
        var beforeTap: (() -> Unit)? = null
        override fun now() = clock.currentTime
        override suspend fun pause(ms: Long) { delay(ms) }
        override suspend fun observe(macros: List<Macro>): Map<String, Observation> {
            if (notificationPauses > 0) { notificationPauses--; throw TransientWindowInterruptedException() }
            if (overlayChanges > 0) { overlayChanges--; throw ObservationChangedException() }
            if (failRecognition) error("frame timeout")
            observations++
            val at = now(); delay(recognitionLatency)
            return macros.associate { it.id to Observation("frame-$observations", at, 100, 100, 0,
                WindowStamp("com.example", 1, 1), results[it.id] ?: MatchResult.Unique(Box(10f, 10f, 30f, 30f))) }
        }
        override fun valid(observation: Observation): Boolean {
            if (notificationAtValidation > 0) { notificationAtValidation--; throw TransientWindowInterruptedException() }
            return alive
        }
        override suspend fun tap(macro: Macro, observation: Observation, onDelivery: (Long) -> Unit): Boolean {
            beforeTap?.invoke(); currentCoroutineContext().ensureActive()
            assertEquals(0, inGesture)
            assertTrue(observation.isFresh(now()))
            inGesture++
            try {
                onDelivery(now())
                deliveries += Delivery(macro.id, now(), observation.sessionId.removePrefix("frame-").toLong())
                onTap?.invoke(macro)
                delay(latency)
                return !cancelledGesture
            } finally { inGesture-- }
        }
        override fun status(state: EngineState, message: String) = Unit
        override fun log(macroId: String?, result: String, durationMs: Long, error: String?) { logs += result }
    }
    @Test fun rankOverridesInputOrderAndCooldownLetsLowerRuleRun() = runTest {
        val p = FakePort(testScheduler)
        val job = launch { MultiMacroEngine(p).run(listOf(low, high)) }
        advanceTimeBy(161); runCurrent(); job.cancelAndJoin()
        assertEquals(listOf("high", "low"), p.deliveries.map { it.id })
        assertTrue(p.deliveries[1].frame > p.deliveries[0].frame)
    }
    @Test fun absentAndAmbiguousHigherPriorityDoNotBlockLower() = runTest {
        for (result in listOf(MatchResult.Absent, MatchResult.Ambiguous(2))) {
            val p = FakePort(testScheduler).apply { results["high"] = result }
            val job = launch { MultiMacroEngine(p).run(listOf(high, low)) }
            advanceTimeBy(81); runCurrent(); job.cancelAndJoin()
            assertEquals(listOf("low"), p.deliveries.map { it.id })
        }
    }
    @Test fun preDelayIsIndependentAndRechecksFreshFrame() = runTest {
        val p = FakePort(testScheduler)
        val job = launch { MultiMacroEngine(p).run(listOf(high.copy(preDelayMs = 1000), low.copy(intervalMs = 1000))) }
        advanceTimeBy(1081); runCurrent(); job.cancelAndJoin()
        assertEquals(Delivery("low", 0, 1), p.deliveries.first())
        val delivery = p.deliveries.first { it.id == "high" }
        assertEquals(1000, delivery.at)
        assertTrue(delivery.frame > 1)
    }
    @Test fun postDelayIsIndependentAndStartsAtCompletion() = runTest {
        val p = FakePort(testScheduler)
        val job = launch { MultiMacroEngine(p).run(listOf(high.copy(intervalMs = 1000, postDelayMs = 5000), low.copy(intervalMs = 1000))) }
        advanceTimeBy(10161); runCurrent(); job.cancelAndJoin()
        val h = p.deliveries.filter { it.id == "high" }
        assertTrue(h.size >= 2)
        assertTrue(h.zipWithNext().all { (a, b) -> b.at - a.at >= 5080 })
        assertTrue(p.deliveries.count { it.id == "low" } > h.size)
    }
    @Test fun firstClickCanRemoveOtherConditionSoOldCoordinatesAreNeverQueued() = runTest {
        val p = FakePort(testScheduler).apply { onTap = { if (it.id == "high") results["low"] = MatchResult.Absent } }
        val job = launch { MultiMacroEngine(p).run(listOf(high, low)) }
        advanceTimeBy(4000); job.cancelAndJoin()
        assertTrue(p.deliveries.all { it.id == "high" })
        assertTrue(p.observations > p.deliveries.size)
    }
    @Test fun persistentRulesKeepTheirOwnIntervalsEvenWhenGestureIsCancelled() = runTest {
        val p = FakePort(testScheduler).apply { cancelledGesture = true; latency = 240 }
        val job = launch { MultiMacroEngine(p).run(listOf(high.copy(intervalMs = 1000), low.copy(intervalMs = 3000))) }
        advanceTimeBy(30001); runCurrent(); job.cancelAndJoin()
        for ((id, interval) in listOf("high" to 1000L, "low" to 3000L)) {
            val deliveries = p.deliveries.filter { it.id == id }
            assertTrue(deliveries.size >= 5)
            assertTrue(deliveries.zipWithNext().all { (a, b) -> b.at - a.at >= interval })
        }
        assertEquals(0, p.inGesture)
    }
    @Test fun expiredFramesNeverDispatchAndDoNotStopTheGroup() = runTest {
        val p = FakePort(testScheduler).apply { recognitionLatency = 1500 }
        val job = launch { MultiMacroEngine(p).run(listOf(high, low)) }
        advanceTimeBy(7501); runCurrent(); assertTrue(job.isActive); job.cancelAndJoin()
        assertTrue(p.deliveries.isEmpty()); assertTrue(p.logs.contains("FRAME_EXPIRED"))
    }
    @Test fun invalidSessionStopsBeforeAnyClick() = runTest {
        val p = FakePort(testScheduler).apply { alive = false }
        assertEquals("SESSION_INVALID", runCatching { MultiMacroEngine(p).run(listOf(high, low)) }.exceptionOrNull()?.message)
        assertTrue(p.deliveries.isEmpty())
    }
    @Test fun cancellationAtDispatchBoundaryStopsAllRules() = runTest {
        val p = FakePort(testScheduler)
        lateinit var job: Job
        p.beforeTap = { job.cancel() }
        job = launch { MultiMacroEngine(p).run(listOf(high, low)) }
        runCurrent(); job.join(); advanceTimeBy(10000)
        assertTrue(p.deliveries.isEmpty())
    }
    @Test fun threeFrameErrorsStopWholeGroupWithReason() = runTest {
        val p = FakePort(testScheduler).apply { failRecognition = true }
        val error = runCatching { MultiMacroEngine(p).run(listOf(high, low)) }.exceptionOrNull()
        assertEquals("인식 오류가 3회 연속 발생했습니다.", error?.message)
        assertEquals(3, p.logs.count { it == "RECOGNITION_ERROR" })
    }
    @Test fun duplicateIdsAndConcurrentRunAreRejected() = runTest {
        val p = FakePort(testScheduler); val engine = MultiMacroEngine(p)
        assertTrue(runCatching { engine.run(listOf(high, high)) }.isFailure)
        val job = launch { engine.run(listOf(high, low)) }; runCurrent()
        assertTrue(runCatching { engine.run(listOf(high, low)) }.isFailure)
        job.cancelAndJoin()
    }
    @Test fun higherPriorityBecomingReadyDuringRecognitionPreemptsLower() = runTest {
        val p = FakePort(testScheduler).apply { onTap = { if (it.id == "high") recognitionLatency = 950 } }
        val job = launch { MultiMacroEngine(p).run(listOf(high.copy(intervalMs = 1000), low)) }
        advanceTimeBy(2061); runCurrent(); job.cancelAndJoin()
        assertEquals(listOf("high", "high"), p.deliveries.map { it.id })
        assertEquals(listOf(0L, 1980L), p.deliveries.map { it.at })
    }
    @Test fun repeatedOverlayMovesRetryWithoutRecognitionErrorsOrStopping() = runTest {
        val p = FakePort(testScheduler).apply { overlayChanges = 5 }
        val job = launch { MultiMacroEngine(p).run(listOf(high, low)) }
        advanceTimeBy(399); assertTrue(p.deliveries.isEmpty())
        advanceTimeBy(102); runCurrent(); assertTrue(job.isActive)
        assertTrue(p.deliveries.isNotEmpty()); assertFalse(p.logs.contains("RECOGNITION_ERROR"))
        job.cancelAndJoin()
    }
    @Test fun notificationWaitDoesNotBecomeThreeRecognitionErrorsAndResumesFresh() = runTest {
        val p=FakePort(testScheduler); p.notificationPauses=8
        val job=launch { MultiMacroEngine(p).run(listOf(high,low)) }
        advanceTimeBy(1900); runCurrent(); assertTrue(p.deliveries.isEmpty())
        advanceTimeBy(3000); runCurrent(); job.cancelAndJoin()
        assertTrue(p.deliveries.isNotEmpty())
        assertTrue(p.deliveries.all { it.at>=2000 && it.frame>0 })
        assertFalse(p.logs.contains("RECOGNITION_ERROR"))
    }
    @Test fun notificationDuringResultValidationDiscardsItAndCancellationStopsWaiting() = runTest {
        val p=FakePort(testScheduler); p.notificationAtValidation=4
        val job=launch { MultiMacroEngine(p).run(listOf(high,low)) }
        advanceTimeBy(900); runCurrent(); assertTrue(p.deliveries.isEmpty())
        advanceTimeBy(1600); runCurrent(); assertTrue(p.deliveries.isNotEmpty())
        p.notificationPauses=100
        job.cancelAndJoin(); val count=p.deliveries.size
        advanceTimeBy(5000); assertEquals(count,p.deliveries.size)
        assertFalse(p.logs.contains("RECOGNITION_ERROR"))
    }

    @Test fun notificationRestartsPreClickWaitButKeepsIndependentCooldowns() = runTest {
        val p=FakePort(testScheduler)
        p.results[low.id]=MatchResult.Absent
        val job=launch { MultiMacroEngine(p).run(listOf(high.copy(preDelayMs=1000),low)) }
        advanceTimeBy(499); p.notificationPauses=8
        advanceTimeBy(2901); runCurrent(); assertTrue(p.deliveries.isEmpty())
        advanceTimeBy(201); runCurrent(); job.cancelAndJoin()
        assertEquals(3500L,p.deliveries.first().at)
        assertFalse(p.logs.contains("RECOGNITION_ERROR"))
    }

}
