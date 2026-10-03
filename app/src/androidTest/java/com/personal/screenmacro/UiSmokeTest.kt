package com.personal.screenmacro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import com.personal.screenmacro.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.compose.ui.geometry.Offset

class UiSmokeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val file = File(dir, name).apply { parentFile?.mkdirs() }
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun offlinePermissionsSettingsValidationAndRoomPersistence() {
        val context = ui.activity
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        ui.onNodeWithText("새 매크로").assertExists()
        screenshot("macro-home.png")
        ui.onNodeWithText("새 매크로").performClick()
        ui.onNodeWithText("검색 범위: 전체 화면").assertExists()
        listOf("왼쪽", "위", "오른쪽", "아래").forEach { ui.onNodeWithText(it).assertDoesNotExist() }
        ui.onNodeWithText("찾을 문구 (한 줄 기준)").performTextInput("Confirm OK")
        ui.onNodeWithText("이름").performTextClearance()
        ui.onNodeWithText("이름").performTextInput("에뮬레이터 검증")
        ui.onNodeWithText("클릭 간격 (초 · 최소 1.0)").performScrollTo().performTextClearance()
        ui.onNodeWithText("클릭 간격 (초 · 최소 1.0)").performTextInput("0.9")
        ui.onNodeWithText("저장").performScrollTo().performClick()
        ui.onNodeWithText("입력 확인").assertExists()
        ui.onNodeWithText("확인", useUnmergedTree = true).performClick()
        ui.onNodeWithText("클릭 간격 (초 · 최소 1.0)").performScrollTo().performTextClearance()
        ui.onNodeWithText("클릭 간격 (초 · 최소 1.0)").performTextInput("3.0")
        screenshot("macro-editor.png")
        ui.onNodeWithText("저장").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("에뮬레이터 검증", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10000) { ui.onAllNodesWithText("에뮬레이터 검증", substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("macro-saved.png")
        val repository = (ui.activity.application as MacroApplication).repository
        val saved = runBlocking { repository.macros.first().first { it.name == "에뮬레이터 검증" } }
        assertEquals(SearchRegion(),saved.region)
        val custom=SearchRegion(.1f,.2f,.8f,.9f)
        runBlocking { repository.save(saved.copy(region=custom)) }
        ui.onNodeWithText("편집").performScrollTo().performClick()
        ui.onNodeWithText("검색 범위: 지정 영역").assertExists()
        ui.onNodeWithText("찾을 문구 (한 줄 기준)").assertTextContains("Confirm OK")
        ui.onNodeWithText("전체 화면으로 변경").performScrollTo().performClick()
        ui.onNodeWithText("검색 범위: 전체 화면").assertExists()
        ui.onNodeWithText("저장").performScrollTo().performClick()
        ui.waitUntil(10000) { runBlocking { repository.get(saved.id)?.region == SearchRegion() } }
        ui.onNodeWithText("삭제").performScrollTo().performClick()
        ui.onNodeWithText("매크로 삭제").assertExists()
        ui.onAllNodesWithText("삭제", useUnmergedTree = true).onLast().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("에뮬레이터 검증", substring = true).fetchSemanticsNodes().isEmpty() }
    }
    @Test fun dragPriorityAndCheckboxRemainSavedAfterActivityRestart() {
        val repository = (ui.activity.application as MacroApplication).repository
        val first = Macro(id = "ui-priority-first", name = "입장 매크로", query = "입장하기")
        val second = Macro(id = "ui-priority-second", name = "확인 매크로", query = "확인")
        try {
            ui.runOnIdle { RuntimeStore.accessibilityConnected.value = true }
            runBlocking { repository.save(first); repository.save(second) }
            ui.waitUntil(10000) { ui.onAllNodesWithTag("drag-${second.id}").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("select-${first.id}").performClick()
            ui.waitUntil(10000) { runBlocking { repository.get(first.id)?.selected == true } }
            ui.onNodeWithText("선택한 매크로 실행 (1개)").assertIsEnabled()
            val start = ui.onNodeWithTag("drag-${first.id}").fetchSemanticsNode().boundsInRoot.center
            val end = ui.onNodeWithTag("drag-${second.id}").fetchSemanticsNode().boundsInRoot.center
            ui.onNodeWithTag("drag-${first.id}").performTouchInput {
                down(center)
                moveBy(Offset(0f, 20f), delayMillis = 50)
                moveBy(Offset(0f, end.y - start.y + 20f), delayMillis = 150)
                advanceEventTime(100)
                up()
            }
            ui.waitUntil(10000) { runBlocking { repository.get(second.id)!!.priority < repository.get(first.id)!!.priority } }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("1순위 · 확인 매크로").assertExists()
            ui.onNodeWithTag("select-${first.id}").assertIsOn()
            screenshot("macro-priority-selected.png")
        } finally {
            runBlocking { repository.get(first.id)?.let { repository.delete(it) }; repository.get(second.id)?.let { repository.delete(it) } }
            ui.runOnIdle { RuntimeStore.accessibilityConnected.value = false }
        }
    }

}
