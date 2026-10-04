package com.personal.screenmacro

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.accessibility.accessibilityWindowLayer
import com.personal.screenmacro.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SystemKeyboardTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun realKeyboardWaitsWhileVisibleAndReturnsToSameAppWindow() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previous = automation.serviceInfo
        val previousFlags = previous.flags
        automation.serviceInfo = previous.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val activity = ui.activity
        val manager = activity.getSystemService(InputMethodManager::class.java)
        val metrics = activity.getSystemService(WindowManager::class.java).maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val content = Box(insets.left.toFloat(), insets.top.toFloat(), (metrics.bounds.width()-insets.right).toFloat(), (metrics.bounds.height()-insets.bottom).toFloat())
        fun layers() = automation.windows.map { accessibilityWindowLayer(it, activity.packageName) }
        fun waitFor(predicate: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 8000
            while (!predicate() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            assertTrue(predicate())
        }
        val field = EditText(activity)
        try {
            ui.runOnUiThread { activity.setContentView(field); field.requestFocus() }
            waitFor { layers().any { it.kind == WindowKind.APPLICATION && it.packageName == activity.packageName && it.focused } }
            val original = layers().first { it.kind == WindowKind.APPLICATION && it.packageName == activity.packageName && it.focused }
            ui.runOnUiThread { manager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
            waitFor { layers().any { KeyboardPolicy.visible(it, original, content) } }
            val visible = layers()
            assertTrue(visible.any { it.id == original.id && it.packageName == original.packageName })
            val target = SessionTarget().apply { bind(WindowStamp(original.packageName!!, original.id, 1)) }
            assertTrue(target.matches(WindowStamp(original.packageName!!, original.id, 2)))
            ui.runOnUiThread { manager.hideSoftInputFromWindow(field.windowToken, 0) }
            waitFor { layers().none { KeyboardPolicy.visible(it, original, content) } }
            waitFor { layers().any { it.id == original.id && it.packageName == original.packageName && it.active } }
            assertFalse(layers().any { WindowPolicy.blocks(it, original, layers()) })
        } finally {
            ui.runOnUiThread { manager.hideSoftInputFromWindow(field.windowToken, 0) }
            previous.flags = previousFlags; automation.serviceInfo = previous
        }
    }
}
