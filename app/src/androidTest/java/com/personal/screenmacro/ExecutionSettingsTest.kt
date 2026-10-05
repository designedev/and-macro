package com.personal.screenmacro

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.ui.ExecutionSettingsDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ExecutionSettingsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun defaultPersistedSettingAndSessionGuard() {
        val context = ui.activity
        val name = "execution-settings-test"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        var locked = false
        try {
            val settings = ExecutionSettings(context, { locked }, name)
            assertEquals(1000L, settings.resultAgeMs.value)
            settings.setResultAge(1500)
            assertEquals(1500L, ExecutionSettings(context, { false }, name).resultAgeMs.value)
            assertTrue(runCatching { settings.setResultAge(1501) }.isFailure)
            locked = true
            assertTrue(runCatching { settings.setResultAge(2000) }.isFailure)
            assertEquals(1500L, settings.resultAgeMs.value)
            preferences.edit().putLong("result_age_ms", 4000).commit()
            assertEquals(1000L, ExecutionSettings(context, { false }, name).resultAgeMs.value)
        } finally { preferences.edit().clear().commit() }
    }
    @Test fun sliderUsesTenthsSaveAndLockFollowSameValue() {
        var locked by mutableStateOf(false)
        var saved = 0L
        ui.runOnUiThread { ui.activity.setContent {
            MaterialTheme { ExecutionSettingsDialog(1000, locked, { saved = it }, {}) }
        } }
        ui.onNodeWithTag("result-age-value").assertTextEquals("1.0초")
        ui.onNodeWithTag("result-age-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1530f) }
        ui.onNodeWithTag("result-age-value").assertTextEquals("1.5초")
        ui.onNodeWithText("저장").performClick()
        ui.runOnIdle { assertEquals(1500L, saved); locked = true }
        ui.onNodeWithTag("result-age-slider").assertIsNotEnabled()
        ui.onNodeWithText("저장").assertIsNotEnabled()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        if (directory != null) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            File(directory, "result-age-settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
