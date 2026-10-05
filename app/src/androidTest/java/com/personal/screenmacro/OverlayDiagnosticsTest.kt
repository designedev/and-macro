package com.personal.screenmacro

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.accessibility.OverlayController
import com.personal.screenmacro.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OverlayDiagnosticsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun collapsedSpinnerExpandedResultsAndStoppedNoticeCanBeDismissed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var overlay: OverlayController? = null
        fun node(tag: String): View = overlay!!.panelView!!.findViewWithTag(tag)
        val oldStatus = RuntimeStore.status.value
        val oldRecognition = RuntimeStore.recognition.value
        val oldHint = RuntimeStore.recognitionHint.value
        try {
            ui.runOnIdle {
                val macros = listOf(Macro(id = "one", name = "입장하기"), Macro(id = "two", name = "확인"))
                RuntimeStore.beginDiagnostics(macros)
                RuntimeStore.recognized(macros[0], MatchResult.Absent, 320, false)
                RuntimeStore.recognized(macros[1], MatchResult.Ambiguous(2), 320, false)
                RuntimeStore.status.value = RuntimeStatus(EngineState.WATCHING, "화면 감시 중", true)
                overlay = OverlayController(ui.activity, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, ui.activity.window.decorView.windowToken)
                overlay!!.show("RUN"); overlay!!.running(false)
                assertFalse(node("overlay-diagnostics").isShown)
                assertFalse(node("overlay-stop").isShown)
                node("overlay-toggle").performClick()
            }
            instrumentation.waitForIdleSync()
            ui.runOnIdle {
                val text = (node("overlay-diagnostic-text") as TextView).text.toString()
                assertTrue(text.contains("입장하기")); assertTrue(text.contains("조건을 찾지 못함"))
                assertTrue(text.contains("후보 2개")); assertTrue(text.contains("320 ms"))
                assertTrue(text.contains("그룹 기준"))
                node("overlay-toggle").performClick()
                overlay!!.finished("인식 오류가 3회 연속 발생했습니다.")
                assertFalse(node("overlay-stop").isShown)
                assertTrue((node("overlay-status") as TextView).text.contains("중단"))
                node("overlay-toggle").performClick()
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600)
            ui.runOnIdle {
                assertEquals("닫기", (node("overlay-stop") as TextView).text.toString())
                assertFalse(node("overlay-brightness").isShown)
                assertFalse(node("overlay-start").isShown)
                assertTrue((node("overlay-diagnostic-text") as TextView).text.contains("3회 연속"))
                assertTrue((node("overlay-status") as TextView).text.contains("중단"))
            }
            val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            if (directory != null) {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
                File(directory, "overlay-stopped-diagnostics.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            ui.runOnIdle { node("overlay-stop").performClick(); assertNull(overlay!!.panelView) }
        } finally {
            ui.runOnIdle {
                overlay?.hide(); RuntimeStore.status.value = oldStatus
                RuntimeStore.recognition.value = oldRecognition; RuntimeStore.recognitionHint.value = oldHint
            }
        }
    }
    @Test fun latestTerminationSurvivesRecreationAndNormalStopDoesNotOverwriteIt() {
        val context = ui.activity
        val name = "stop-notice-test"
        val preferences = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val store = StopNoticeStore(context, name)
            store.begin(); store.finish("캡처가 종료되었습니다.")
            val notice = StopNoticeStore(context, name).latest()!!
            assertEquals("캡처가 종료되었습니다.", notice.reason)
            store.begin(); store.finish()
            assertEquals(notice, StopNoticeStore(context, name).recoverInterrupted())
            store.begin()
            val recovered = StopNoticeStore(context, name).recoverInterrupted()!!
            assertTrue(recovered.reason.contains("정상 종료되지"))
            assertEquals(recovered, StopNoticeStore(context, name).recoverInterrupted())
        } finally { preferences.edit().clear().commit() }
    }
}
