package com.personal.screenmacro.core

import org.junit.Assert.*
import org.junit.Test

class ResultAgeTest {
    private val observation = Observation("session", 100, 10, 10, 0, WindowStamp("game", 1, 1), MatchResult.Absent)
    @Test fun acceptsOnlyApprovedRangeAndStep() {
        (1000L..3000L step 100).forEach { assertTrue(validResultAge(it)) }
        listOf(0L, 999L, 1001L, 3050L, Long.MAX_VALUE).forEach { assertFalse(validResultAge(it)) }
        assertEquals("1.0", resultAgeSeconds(1000)); assertEquals("1.5", resultAgeSeconds(1500))
    }
    @Test fun configuredBoundaryIsInclusiveAndFutureFramesRemainInvalid() {
        assertFalse(observation.isFresh(1300))
        assertTrue(observation.isFresh(1600, 1500)); assertFalse(observation.isFresh(1601, 1500))
        assertFalse(observation.isFresh(99, 3000))
        assertTrue(runCatching { observation.isFresh(100, 3500) }.isFailure)
    }
    @Test fun noticeUsesSameConfiguredThresholdAndKeepsAbsentSeparate() {
        val summary = RecognitionSummary("id", "확인", count = 1, expired = true, maxAgeMs = 1500)
        assertTrue(summary.explanation().contains("1.5초"))
        assertTrue(summary.copy(count = 0).explanation().contains("찾지 못함"))
    }
}
