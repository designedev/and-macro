package com.personal.screenmacro

import android.graphics.Bitmap
import com.personal.screenmacro.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

object RuntimeStore {
    val status = MutableStateFlow(RuntimeStatus())
    val logs = MutableStateFlow<List<ExecutionLog>>(emptyList())
    val accessibilityConnected = MutableStateFlow(false)
    val pendingCapture = MutableStateFlow<CapturedEditorFrame?>(null)
    val testResult = MutableStateFlow<TestPreview?>(null)
    fun log(macroId: String?, result: String, duration: Long = 0, error: String? = null) {
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
