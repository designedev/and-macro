package com.personal.screenmacro.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun absentAmbiguousAndExpiredExplainDifferentActions() {
        val row = RecognitionSummary("id", "입장")
        assertTrue(row.explanation().contains("아직 검사"))
        assertTrue(row.copy(count = 0).explanation().contains("조건을 찾지 못함"))
        assertTrue(row.copy(count = 1).explanation().contains("대기 시간"))
        assertTrue(row.copy(count = 3).explanation().contains("검색 영역을 좁혀"))
        assertTrue(row.copy(count = 1, expired = true).explanation().contains("클릭 보류"))
    }
}
