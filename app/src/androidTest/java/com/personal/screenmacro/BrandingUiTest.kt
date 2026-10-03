package com.personal.screenmacro

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.ui.PermissionStrip
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrandingUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun save(bitmap: Bitmap, name: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        File(dir, name).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun permissionIconsShareOneRowRefreshStatesAndKeepActions() {
        var granted by mutableStateOf(false)
        val clicked = IntArray(4)
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme { PermissionStrip(granted, granted, granted, false, false,
                    { clicked[0]++ }, { clicked[1]++ }, { clicked[2]++ }, { clicked[3]++ }) }
            }
        }
        val tags = listOf("permission-accessibility", "permission-notification", "permission-brightness", "permission-help")
        val bounds = tags.map { ui.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.all { kotlin.math.abs(it.center.y - bounds.first().center.y) < 1f })
        tags.take(3).forEach { ui.onNodeWithTag(it).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "허용되지 않음")) }
        tags.forEach { ui.onNodeWithTag(it).assertIsEnabled().performClick() }
        assertArrayEquals(intArrayOf(1, 1, 1, 1), clicked)
        save(ui.onRoot().captureToImage().asAndroidBitmap(), "auto-permissions-denied.png")
        ui.runOnIdle { granted = true }
        tags.take(3).forEach { ui.onNodeWithTag(it).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "허용됨")) }
        ui.onNodeWithTag("permission-notification").assertIsNotEnabled()
        ui.onNodeWithTag("permission-accessibility").assertIsEnabled()
        ui.onNodeWithTag("permission-brightness").assertIsEnabled()
        save(ui.onRoot().captureToImage().asAndroidBitmap(), "auto-permissions-granted.png")
    }
    @Test fun autoLabelAndAdaptiveLauncherIconAreInstalled() {
        val activity = ui.activity
        assertEquals("AUTO", activity.packageManager.getApplicationLabel(activity.applicationInfo).toString())
        ui.onNodeWithText("AUTO").assertExists()
        val bitmap = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
        try {
            activity.packageManager.getApplicationIcon(activity.packageName).apply { setBounds(0, 0, 384, 384); draw(Canvas(bitmap)) }
            save(bitmap, "auto-launcher-icon.png")
            save(ui.onRoot().captureToImage().asAndroidBitmap(), "auto-home.png")
        } finally { bitmap.recycle() }
    }
}
