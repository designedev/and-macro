package com.personal.screenmacro

import android.graphics.Bitmap
import com.personal.screenmacro.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

object RuntimeStore {
    val status = MutableStateFlow(RuntimeStatus())
    val recognition = MutableStateFlow<List<RecognitionSummary>>(emptyList())
    val recognitionHint = MutableStateFlow<String?>(null)
    val lastStop = MutableStateFlow<StopNotice?>(null)
    private var recognitionErrors = 0
    fun beginDiagnostics(macros: List<Macro>) {
        recognition.value = macros.map { RecognitionSummary(it.id, it.name) }
        recognitionHint.value = null; recognitionErrors = 0
    }
    fun recognized(macro: Macro, result: MatchResult, duration: Long, expired: Boolean) {
        val count = when (result) { is MatchResult.Unique -> 1; is MatchResult.Ambiguous -> result.count; else -> 0 }
        recognition.value = recognition.value.map {
            if (it.macroId == macro.id) RecognitionSummary(macro.id, macro.name, count, duration, System.currentTimeMillis(), expired) else it
        }
        recognitionHint.value = null; recognitionErrors = 0
    }
    fun recognizedBatch(observations: Map<String, Observation>, duration: Long, now: Long) {
        val checkedAt = System.currentTimeMillis()
        recognition.value = recognition.value.map { row ->
            observations[row.macroId]?.let { observation ->
                val count = when (val result = observation.result) { is MatchResult.Unique -> 1; is MatchResult.Ambiguous -> result.count; else -> 0 }
                row.copy(count = count, durationMs = duration, checkedAt = checkedAt, expired = !observation.isFresh(now))
            } ?: row
        }
        recognitionHint.value = null; recognitionErrors = 0
    }
    val logs = MutableStateFlow<List<ExecutionLog>>(emptyList())
    val accessibilityConnected = MutableStateFlow(false)
    val pendingCapture = MutableStateFlow<CapturedEditorFrame?>(null)
    val testResult = MutableStateFlow<TestPreview?>(null)
    fun log(macroId: String?, result: String, duration: Long = 0, error: String? = null) {
        if (result == "RECOGNITION_ERROR") {
            recognitionErrors = (recognitionErrors + 1).coerceAtMost(3)
            recognitionHint.value = "인식 처리 오류 $recognitionErrors/3 · 다시 시도 중입니다. 캡처와 검색 영역을 확인하세요."
        }
        if (result == "FRAME_EXPIRED") recognition.value = recognition.value.map {
            if (it.macroId == macroId) it.copy(expired = true) else it
        }
        if (result == "GESTURE_CANCELLED") recognitionHint.value = "클릭이 완료되지 않았습니다. 화면·오버레이 상태를 확인하세요."
        synchronized(this) {
            val previous = logs.value
            logs.value = buildList(minOf(previous.size + 1, 1000)) {
                addAll(previous.subList(maxOf(0, previous.size - 999), previous.size))
                add(ExecutionLog(System.currentTimeMillis(), macroId, result, duration, error))
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.i("ScreenMacro", "$result macro=${macroId?.take(8) ?: "session"} durationMs=$duration error=${error ?: "none"}")
    }
    fun clearCapture() { pendingCapture.value?.bitmap?.recycle(); pendingCapture.value = null }
    fun clearTest() { testResult.value?.bitmap?.recycle(); testResult.value = null }
}
data class CapturedEditorFrame(val bitmap: Bitmap, val rotation: Int, val token: String = UUID.randomUUID().toString())
data class TestPreview(val bitmap: Bitmap, val result: MatchResult, val duration: Long, val query: String, val matchMode: MatchMode, val texts: List<TextCandidate>, val fresh: Boolean)
