package com.personal.screenmacro.core
import org.junit.Assert.*
import org.junit.Test
class ModelsTest {
    @Test fun rejectInvalidStoredAndEditedSettings() {
        val m = Macro(query = "확인")
        m.validate()
        listOf(m.copy(intervalMs = 999), m.copy(preDelayMs = -1), m.copy(postDelayMs = -1), m.copy(query = "  "),
            m.copy(region = SearchRegion(right = 2f)),
            m.copy(type = RecognitionType.IMAGE), m.copy(region = SearchRegion(left = Float.NaN))).forEach {
            assertTrue(runCatching { it.validate() }.isFailure)
        }
        val image = m.copy(type = RecognitionType.IMAGE, templatePath = "image.png", referenceWidth = 100, referenceHeight = 100)
        assertTrue(runCatching { image.validate { false } }.isFailure)
    }
    @Test fun decimalTimesNeverRoundBelowMinimumOrOverflow() {
        assertEquals(1000L, secondsToMillis("1.0", 1000)); assertEquals(3100L, secondsToMillis("3.1", 1000))
        listOf("0.9", "NaN", "abc", "1.01", "999999999999999999999", "-1").forEach { assertTrue(runCatching { secondsToMillis(it, 1000) }.isFailure) }
    }
    @Test fun normalizedCoordinatesAndBoundingBoxGeometry() {
        assertEquals(Box(100f, 100f, 300f, 200f), SearchRegion(.25f, .2f, .75f, .4f).pixels(400, 500))
        val a = Box(10f, 10f, 30f, 30f)
        assertEquals(20f, a.centerX); assertTrue(a.intersects(Box(20f, 20f, 40f, 40f)))
        assertFalse(a.intersects(Box(30f, 10f, 40f, 20f)))
    }
    @Test fun koreanNfcLatinCaseAndContains() {
        val m = Macro(query = "확인 OK")
        assertTrue(textMatches("  확인 OK  ", m)); assertFalse(textMatches("확인 ok", m))
        assertTrue(textMatches("확인 ok", m.copy(ignoreCase = true)))
        assertTrue(textMatches("버튼: 확인 OK 완료", m.copy(matchMode = MatchMode.CONTAINS)))
        assertTrue(textMatches("\u1100\u1161", Macro(query = "가")))
    }
    @Test fun duplicatesMergeOnlySameTextAtSameLocation() {
        val a = TextCandidate(" 확인 ", Box(10f, 10f, 40f, 30f))
        val near = TextCandidate("확인", Box(11f, 10f, 41f, 30f))
        val far = TextCandidate("확인", Box(60f, 10f, 90f, 30f))
        assertEquals(2, mergeTextCandidates(listOf(a, near, far)).size)
        assertEquals(2, mergeTextCandidates(listOf(a, near.copy(text = "취소"))).size)
    }
}
