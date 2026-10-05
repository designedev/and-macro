package com.personal.screenmacro.core

/** Metadata only: never retain captured pixels, OCR text, or matching coordinates. */
data class RecognitionSummary(
    val macroId: String, val name: String, val count: Int? = null,
    val durationMs: Long? = null, val checkedAt: Long? = null, val expired: Boolean = false, val ageMs: Long? = null
) {
    fun explanation(): String = when {
        count == null -> "아직 검사하지 않았습니다"
        count == 0 -> "조건을 찾지 못함 · 화면에 대상이 있는지 확인하세요"
        count > 1 -> "후보 ${count}개 · 검색 영역을 좁혀주세요"
        expired -> "후보 1개 검출 · 1초가 지나 클릭 보류 · 검색 영역을 줄여주세요"
        count == 1 -> "후보 1개 · 클릭은 우선순위와 대기 시간에 따라 결정"
        else -> "후보 ${count}개 · 검색 영역을 좁혀주세요"
    }
}
data class StopNotice(val time: Long, val reason: String)
