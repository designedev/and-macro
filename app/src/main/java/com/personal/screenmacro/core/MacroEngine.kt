package com.personal.screenmacro.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface EnginePort {
    fun now(): Long
    suspend fun pause(ms: Long)
    suspend fun observe(macro: Macro): Observation
    fun valid(observation: Observation): Boolean
    suspend fun tap(observation: Observation, onDelivery: (Long) -> Unit): Boolean
    fun status(state: EngineState, message: String)
    fun log(result: String, durationMs: Long = 0, error: String? = null)
}
class ClickSchedule(private val interval: Long, private val postDelay: Long) {
    init { require(interval in 1000..MAX_DELAY_MS && postDelay in 0..MAX_DELAY_MS) }
    var lastDelivery: Long? = null; private set
    var lastCompletion: Long? = null; private set
    fun delivered(at: Long) { lastDelivery = at }
    fun completed(at: Long) { lastCompletion = at }
    fun remaining(now: Long): Long = maxOf(
        lastDelivery?.let { (it + interval - now).coerceAtLeast(0) } ?: 0,
        lastCompletion?.let { (it + postDelay - now).coerceAtLeast(0) } ?: 0
    )
}
class MacroEngine(private val port: EnginePort) {
    private var running = false
    suspend fun run(macro: Macro) {
        check(!running) { "이미 실행 중입니다." }
        macro.validate()
        running = true
        val schedule = ClickSchedule(macro.intervalMs, macro.postDelayMs)
        var errors = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val wait = schedule.remaining(port.now())
                if (wait > 0) {
                    port.status(EngineState.COOLDOWN, "다음 클릭까지 대기")
                    port.pause(wait)
                }
                try {
                    port.status(EngineState.WATCHING, "새 화면에서 조건 확인")
                    var observation = port.observe(macro)
                    errors = 0
                    if (!port.valid(observation)) error("SESSION_INVALID")
                    val initial = observation.result
                    if (initial !is MatchResult.Unique) {
                        report(initial)
                        port.pause(500)
                        continue
                    }
                    if (discardExpired(observation)) continue
                    if (macro.preDelayMs > 0) {
                        port.status(EngineState.PRE_DELAY, "클릭 전 대기")
                        port.pause(macro.preDelayMs)
                        observation = port.observe(macro)
                        if (!port.valid(observation)) error("SESSION_INVALID")
                        if (observation.result !is MatchResult.Unique) { report(observation.result); port.pause(500); continue }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!port.valid(observation)) error("SESSION_INVALID")
                    if (discardExpired(observation)) continue
                    if (schedule.remaining(port.now()) > 0) continue
                    port.status(EngineState.CLICKING, "터치 전달")
                    val previousDelivery = schedule.lastDelivery
                    val success = port.tap(observation) { schedule.delivered(it) }
                    if (!success && schedule.lastDelivery == previousDelivery) schedule.delivered(port.now())
                    schedule.completed(port.now())
                    port.log(if (success) "GESTURE_COMPLETED" else "GESTURE_CANCELLED")
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: TransientWindowInterruptedException) { port.pause(250) }
                catch (_: ObservationChangedException) { port.pause(80) }
                catch (e: Exception) {
                    if (e.message == "SESSION_INVALID") throw e
                    errors++
                    port.log("RECOGNITION_ERROR", error = e.javaClass.simpleName)
                    if (errors >= 3) throw IllegalStateException("인식 오류가 3회 연속 발생했습니다.", e)
                    port.pause(500)
                }
            }
        } finally { running = false }
    }
    private suspend fun discardExpired(observation: Observation): Boolean {
        if (observation.isFresh(port.now())) return false
        val age = port.now() - observation.frameTime
        port.status(EngineState.WATCHING, "인식 결과가 오래되었습니다 (${age} ms) · 검색 영역을 줄여주세요")
        port.log("FRAME_EXPIRED", durationMs = age)
        port.pause(500)
        return true
    }
    private fun report(result: MatchResult) {
        when (result) {
            is MatchResult.Ambiguous -> { port.status(EngineState.WATCHING, "후보 ${result.count}개: 검색 영역을 좁히세요"); port.log("AMBIGUOUS") }
            MatchResult.Absent -> port.status(EngineState.WATCHING, "조건 없음 · 감시 중")
            else -> Unit
        }
    }
}
