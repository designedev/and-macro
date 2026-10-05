package com.personal.screenmacro.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface MultiEnginePort {
    fun now(): Long
    suspend fun pause(ms: Long)
    suspend fun observe(macros: List<Macro>): Map<String, Observation>
    fun valid(observation: Observation): Boolean
    fun validBatch(observations: Collection<Observation>): Boolean = observations.sameFrame() && observations.all(::valid)
    suspend fun tap(macro: Macro, observation: Observation, onDelivery: (Long) -> Unit): Boolean
    fun status(state: EngineState, message: String)
    fun log(macroId: String?, result: String, durationMs: Long = 0, error: String? = null)
}

/** One capture session, independent rule clocks, and exactly one gesture at a time. */
class MultiMacroEngine(private val port: MultiEnginePort, private val maxAgeMs: Long = DEFAULT_RESULT_AGE_MS) {
    init { require(validResultAge(maxAgeMs)) }
    private var running = false
    suspend fun run(macros: List<Macro>) {
        check(!running) { "이미 실행 중입니다." }
        require(macros.isNotEmpty() && macros.map { it.id }.distinct().size == macros.size)
        macros.forEach { it.validate() }
        val ordered = macros.sortedBy { it.priority }
        val schedules = ordered.associate { it.id to ClickSchedule(it.intervalMs, it.postDelayMs) }
        val preUntil = mutableMapOf<String, Long>()
        var errors = 0
        running = true
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val eligible = ordered.filter { schedules.getValue(it.id).remaining(port.now()) == 0L }
                if (eligible.isEmpty()) {
                    port.status(EngineState.COOLDOWN, "${ordered.size}개 매크로 · 다음 클릭까지 대기")
                    port.pause(schedules.values.minOf { it.remaining(port.now()) }.coerceAtLeast(1))
                    continue
                }
                try {
                    port.status(EngineState.WATCHING, "${ordered.size}개 매크로 · 새 화면에서 조건 확인")
                    val observations = port.observe(eligible)
                    val batch = eligible.map { observations[it.id] ?: error("인식 결과가 누락되었습니다.") }
                    if (!port.validBatch(batch)) error("SESSION_INVALID")
                    var ambiguous = 0
                    var expired = false
                    for (macro in eligible) {
                        val observation = observations[macro.id] ?: error("인식 결과가 누락되었습니다.")
                        when (val result = observation.result) {
                            MatchResult.Absent -> preUntil.remove(macro.id)
                            is MatchResult.Ambiguous -> {
                                preUntil.remove(macro.id); ambiguous++
                                port.log(macro.id, "AMBIGUOUS", error = "MULTIPLE_CANDIDATES_${result.count}")
                            }
                            is MatchResult.Unique -> {
                                if (!observation.isFresh(port.now(), maxAgeMs)) {
                                    expired = true
                                    port.log(macro.id, "FRAME_EXPIRED", port.now() - observation.frameTime)
                                    continue
                                }
                                preUntil.getOrPut(macro.id) { port.now() + macro.preDelayMs }
                            }
                        }
                    }
                    errors = 0
                    val chosen = eligible.firstNotNullOfOrNull { macro ->
                        val observation = observations.getValue(macro.id)
                        if (observation.result is MatchResult.Unique && observation.isFresh(port.now(), maxAgeMs) &&
                            preUntil[macro.id]?.let { port.now() >= it } == true) macro to observation else null
                    }
                    if (chosen == null) {
                        val wait = preUntil.values.map { it - port.now() }.filter { it > 0 }.minOrNull()
                        when {
                            expired -> port.status(EngineState.WATCHING, "인식 결과가 오래되었습니다 · 검색 영역을 줄여주세요")
                            wait != null -> port.status(EngineState.PRE_DELAY, "${ordered.size}개 매크로 · 클릭 전 대기 중에도 다른 조건 감시")
                            ambiguous > 0 -> port.status(EngineState.WATCHING, "후보가 여러 개인 조건 ${ambiguous}개 · 검색 영역을 좁히세요")
                            else -> port.status(EngineState.WATCHING, "${ordered.size}개 매크로 · 조건 없음 · 감시 중")
                        }
                        val cooldown = schedules.values.map { it.remaining(port.now()) }.filter { it > 0 }.minOrNull()
                        port.pause(minOf(500L, wait ?: 500L, cooldown ?: 500L).coerceAtLeast(1))
                        continue
                    }
                    val (macro, observation) = chosen
                    // A higher-ranked cooldown may end during recognition. Include it in
                    // the next fresh scan before dispatching a lower-ranked rule.
                    if (ordered.takeWhile { it.id != macro.id }.any {
                        it !in eligible && schedules.getValue(it.id).remaining(port.now()) == 0L
                    }) continue
                    currentCoroutineContext().ensureActive()
                    if (!port.valid(observation)) error("SESSION_INVALID")
                    if (!observation.isFresh(port.now(), maxAgeMs)) {
                        port.log(macro.id, "FRAME_EXPIRED", port.now() - observation.frameTime)
                        continue
                    }
                    val schedule = schedules.getValue(macro.id)
                    if (schedule.remaining(port.now()) > 0) continue
                    val rank = if (macro.priority == Int.MAX_VALUE) ordered.indexOf(macro) + 1 else macro.priority + 1
                    port.status(EngineState.CLICKING, "우선순위 $rank · 터치 전달")
                    val previous = schedule.lastDelivery
                    val success = port.tap(macro, observation) { schedule.delivered(it) }
                    if (!success && schedule.lastDelivery == previous) schedule.delivered(port.now())
                    schedule.completed(port.now())
                    preUntil.remove(macro.id)
                    port.log(macro.id, if (success) "GESTURE_COMPLETED" else "GESTURE_CANCELLED")
                    // Never carry another rule's coordinates across a touch. The next loop
                    // obtains a new frame and evaluates the newly eligible rules again.
                } catch (e: CancellationException) { throw e }
                catch (_: TransientWindowInterruptedException) { preUntil.clear(); port.pause(250) }
                catch (_: ObservationChangedException) { port.pause(80) }
                catch (e: Exception) {
                    if (e.message == "SESSION_INVALID") throw e
                    errors++
                    port.log(null, "RECOGNITION_ERROR", error = e.javaClass.simpleName)
                    if (errors >= 3) throw IllegalStateException("인식 오류가 3회 연속 발생했습니다.", e)
                    port.pause(500)
                }
            }
        } finally { running = false }
    }
}
