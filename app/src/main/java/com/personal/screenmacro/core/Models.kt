package com.personal.screenmacro.core

import java.math.BigDecimal
import java.text.Normalizer
import java.util.UUID

const val MAX_DELAY_MS = 86_400_000L

enum class RecognitionType { IMAGE, TEXT }
enum class MatchMode { EXACT, CONTAINS }
enum class EngineState { IDLE, STARTING, WATCHING, PRE_DELAY, CLICKING, COOLDOWN, STOPPING, ERROR }
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() } && width > 0 && height > 0
    fun intersects(b: Box) = left < b.right && right > b.left && top < b.bottom && bottom > b.top
    fun iou(b: Box): Float {
        val overlap = (minOf(right, b.right) - maxOf(left, b.left)).coerceAtLeast(0f) *
            (minOf(bottom, b.bottom) - maxOf(top, b.top)).coerceAtLeast(0f)
        return overlap / (width * height + b.width * b.height - overlap).coerceAtLeast(1f)
    }
}
data class SearchRegion(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    fun valid() = Box(left, top, right, bottom).valid() && listOf(left, top, right, bottom).all { it in 0f..1f }
    fun pixels(width: Int, height: Int): Box {
        require(valid() && width > 0 && height > 0)
        return Box((left * width).toInt().toFloat(), (top * height).toInt().toFloat(),
            (right * width).toInt().toFloat(), (bottom * height).toInt().toFloat())
    }
}
data class Macro(
    val id: String = UUID.randomUUID().toString(), val name: String = "새 매크로",
    val type: RecognitionType = RecognitionType.TEXT,
    val region: SearchRegion = SearchRegion(), val intervalMs: Long = 3000,
    val preDelayMs: Long = 0, val postDelayMs: Long = 0,
    val query: String = "", val matchMode: MatchMode = MatchMode.EXACT, val ignoreCase: Boolean = false,
    val templatePath: String? = null, val threshold: Double = 0.90,
    val referenceWidth: Int = 0, val referenceHeight: Int = 0, val referenceRotation: Int = 0,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis(),
    val priority: Int = Int.MAX_VALUE, val selected: Boolean = false
) {
    fun validate(templateExists: (String) -> Boolean = { true }) {
        require(name.isNotBlank() && name.length <= 100) { "이름은 1~100자로 입력하세요." }
        require(intervalMs in 1000..MAX_DELAY_MS) { "클릭 간격은 1초~24시간입니다." }
        require(preDelayMs in 0..MAX_DELAY_MS && postDelayMs in 0..MAX_DELAY_MS) { "대기는 0초~24시간입니다." }
        require(region.valid()) { "검색 영역을 확인하세요." }
        require(priority >= 0) { "우선순위를 확인하세요." }
        if (type == RecognitionType.TEXT) require(normalizeText(query).isNotBlank() && query.length <= 500) { "찾을 문구를 입력하세요 (최대 500자)." }
        else {
            require(threshold.isFinite() && threshold in 0.5..0.99) { "유사도는 0.50~0.99입니다." }
            require(templatePath != null && templateExists(templatePath)) { "기준 이미지를 등록하세요." }
            require(referenceWidth > 0 && referenceHeight > 0 && referenceRotation in 0..3) { "기준 화면 정보가 없습니다." }
        }
    }
}
fun secondsToMillis(input: String, minimum: Long = 0): Long {
    val seconds = input.toBigDecimalOrNull() ?: error("숫자를 입력하세요.")
    require(seconds.remainder(BigDecimal("0.1")).compareTo(BigDecimal.ZERO) == 0) { "0.1초 단위로 입력하세요." }
    val ms = try { seconds.multiply(BigDecimal(1000)).longValueExact() } catch (_: ArithmeticException) { error("시간이 너무 큽니다.") }
    require(ms in minimum..MAX_DELAY_MS) { "허용 범위: ${minimum / 1000.0}초~86400초" }
    return ms
}
fun normalizeText(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
fun textMatches(value: String, macro: Macro): Boolean {
    val actual = normalizeText(value)
    val query = normalizeText(macro.query)
    // Only Latin English case is ignored; Korean and all other code points are preserved.
    fun fold(s: String) = if (macro.ignoreCase) s.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("") else s
    return if (macro.matchMode == MatchMode.EXACT) fold(actual) == fold(query) else fold(actual).contains(fold(query))
}
data class TextCandidate(val text: String, val box: Box)
fun mergeTextCandidates(candidates: List<TextCandidate>): List<TextCandidate> {
    val unique = mutableListOf<TextCandidate>()
    candidates.forEach { candidate ->
        if (unique.none { normalizeText(it.text) == normalizeText(candidate.text) && it.box.iou(candidate.box) >= 0.5f }) unique.add(candidate)
    }
    return unique
}
sealed interface MatchResult {
    data object Absent : MatchResult
    data class Unique(val box: Box) : MatchResult
    data class Ambiguous(val count: Int) : MatchResult
}
data class WindowStamp(val packageName: String, val windowId: Int, val revision: Long)
data class Observation(val sessionId: String, val frameTime: Long, val width: Int, val height: Int, val rotation: Int, val window: WindowStamp, val result: MatchResult, val overlayRevision: Long = 0)
fun Observation.isFresh(now: Long) = now - frameTime in 0..1000
data class RuntimeStatus(val state: EngineState = EngineState.IDLE, val message: String = "대기 중", val busy: Boolean = false)
data class ExecutionLog(val time: Long, val macroId: String?, val result: String, val durationMs: Long = 0, val errorCode: String? = null)
