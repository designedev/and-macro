package com.personal.screenmacro

import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.accessibility.OverlayController
import com.personal.screenmacro.brightness.BrightnessController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrightnessOverlayTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun screenshot(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        // Wait for the changed floating window to reach the display compositor.
        val frame = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            ui.activity.window.decorView.postOnAnimation {
                ui.activity.window.decorView.postOnAnimation { frame.countDown() }
            }
        }
        assertTrue(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(directory, name).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun systemMinimumManualRecoveryAndRestartRecovery() {
        val context = ui.activity
        val originalLevel=Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS)
        val originalMode=Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE)
        shell("appops set ${context.packageName} WRITE_SETTINGS allow")
        try {
            ui.runOnIdle {
                assertTrue(Settings.System.canWrite(context))
                assertTrue(Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,0))
                assertTrue(Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,142))
                val brightness=BrightnessController(context)
                brightness.begin()
                assertEquals(1,Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS))
                assertEquals(0,Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE))
                assertTrue(brightness.pending)
                assertTrue(brightness.restore()); assertFalse(brightness.pending)
                assertEquals(142,Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS))
                assertTrue(Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,1))
                // Capture the actual saved level, since adaptive brightness can update it.
                val before=Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS)
                brightness.begin()
                assertEquals(1,Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS))
                assertTrue(BrightnessController(context).restore())
                assertEquals(1,Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE))
                assertFalse(brightness.pending)
                assertTrue(before > 0)
                // A new activity after interruption must recover the persisted original.
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,0)
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,142)
                brightness.begin()
            }
            ui.activityRule.scenario.recreate()
            ui.runOnIdle {
                assertEquals(142,Settings.System.getInt(ui.activity.contentResolver,Settings.System.SCREEN_BRIGHTNESS))
                assertFalse(BrightnessController(ui.activity).pending)
            }
        } finally {
            ui.runOnIdle {
                BrightnessController(ui.activity).restore()
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,originalLevel)
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,originalMode)
            }
            shell("appops set ${context.packageName} WRITE_SETTINGS default")
        }
    }
    @Test fun cardTouchRestoresImmediatelyWithoutStoppingOrAutomaticallyDimmingAgain() {
        val context = ui.activity
        val originalLevel=Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS)
        val originalMode=Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE)
        val brightness=BrightnessController(context)
        var installed: OverlayController?=null
        var restores=0; var dims=0
        fun node(tag: String): View = installed!!.panelView!!.findViewWithTag(tag)
        fun touch(action: Int, target: View) {
            val panel=installed!!.panelView!!
            val rect=android.graphics.Rect(0,0,target.width,target.height)
            (panel as android.view.ViewGroup).offsetDescendantRectToMyCoords(target,rect)
            val now=SystemClock.uptimeMillis()
            MotionEvent.obtain(now,now,action,rect.exactCenterX(),rect.exactCenterY(),0).also { event ->
                assertTrue(panel.dispatchTouchEvent(event)); event.recycle()
            }
        }
        shell("appops set ${context.packageName} WRITE_SETTINGS allow")
        try {
            ui.runOnIdle {
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,0)
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,142)
                installed=OverlayController(context,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,context.window.decorView.windowToken,
                    recoverBrightness={ restores++; brightness.restore() },dimBrightness={ dims++; brightness.begin(); true })
                installed!!.show("RUN"); brightness.begin(); installed!!.running(true)
            }
            instrumentation.waitForIdleSync()
            var firstSpinner=""
            ui.runOnIdle {
                val status=node("overlay-status") as android.widget.TextView
                firstSpinner=status.text.toString()
                installed!!.message("조건 없음. 감시 중"); installed!!.message("새 화면에서 조건 확인")
                assertEquals(firstSpinner,status.text.toString())
                assertFalse(node("overlay-stop").isShown)
                touch(MotionEvent.ACTION_DOWN,status)
                assertEquals(142,Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS))
                assertEquals(1,restores); assertTrue(installed!!.interacting)
                touch(MotionEvent.ACTION_UP,status)
                assertFalse(installed!!.interacting); assertEquals(0,dims)
                node("overlay-toggle").performClick()
            }
            instrumentation.waitForIdleSync()
            ui.runOnIdle {
                assertTrue(node("overlay-stop").isShown)
                assertEquals("어둡게",(node("overlay-brightness") as android.widget.TextView).text.toString())
                touch(MotionEvent.ACTION_DOWN,node("overlay-brightness"))
                touch(MotionEvent.ACTION_UP,node("overlay-brightness"))
            }
            instrumentation.waitForIdleSync()
            ui.runOnIdle {
                assertEquals(1,dims); assertTrue(brightness.pending)
                assertEquals(1,Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS))
                assertFalse(node("overlay-stop").isShown)
                assertEquals(View.GONE,node("overlay-details").visibility)
                assertTrue(node("overlay-brightness-compact").isShown)
                // After re-dimming, only the collapsed card is touchable; it restores.
                touch(MotionEvent.ACTION_DOWN,node("overlay-brightness-compact"))
                touch(MotionEvent.ACTION_UP,node("overlay-brightness-compact"))
            }
            instrumentation.waitForIdleSync()
            ui.runOnIdle {
                assertEquals(2,restores); assertEquals(1,dims)
                assertEquals(142,Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS))
            }
            SystemClock.sleep(800)
            ui.runOnIdle {
                assertEquals(1,dims); assertFalse(brightness.pending)
                assertTrue(node("overlay-status").isShown)
                assertTrue((node("overlay-status") as android.widget.TextView).text.toString() in listOf("[ | ]","[ / ]","[ - ]","[ \\ ]"))
                installed!!.hide()
            }
        } finally {
            ui.runOnIdle {
                installed?.hide(); brightness.restore()
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,originalLevel)
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,originalMode)
            }
            shell("appops set ${context.packageName} WRITE_SETTINGS default")
        }
    }
    @Test fun floatingCardDragsCancelsClampsRemembersPositionAndExpands() {
        var installed: OverlayController? = null
        fun overlay() = installed!!
        fun node(tag: String): View = overlay().panelView!!.findViewWithTag(tag)
        fun pointer(action: Int, x: Float, y: Float) {
            val time=SystemClock.uptimeMillis()
            MotionEvent.obtain(time,time,action,x,y,0).also { event -> node("overlay-drag").dispatchTouchEvent(event); event.recycle() }
        }
        try {
            ui.runOnIdle {
                ui.activity.getSharedPreferences("overlay_position",0).edit().clear().commit()
                installed=OverlayController(ui.activity,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,ui.activity.window.decorView.windowToken)
                overlay().show("RUN"); overlay().ready(); overlay().message("준비 완료 · 대상 앱에서 실행")
            }
            instrumentation.waitForIdleSync()
            screenshot("overlay-ready.png")
            var initialTop=0f
            ui.runOnIdle {
                initialTop=overlay().bounds()!!.top
                pointer(MotionEvent.ACTION_DOWN,20f,20f); assertTrue(overlay().interacting)
                pointer(MotionEvent.ACTION_MOVE,-300f,350f); pointer(MotionEvent.ACTION_UP,-300f,350f)
                assertFalse(overlay().interacting)
            }
            instrumentation.waitForIdleSync()
            ui.runOnIdle {
                val moved=overlay().bounds()!!; assertNotEquals(initialTop,moved.top)
                assertTrue(moved.left>=0); assertTrue(moved.top>=0)
                val revision=overlay().revision
                pointer(MotionEvent.ACTION_DOWN,20f,20f)
                pointer(MotionEvent.ACTION_MOVE,100f,250f); pointer(MotionEvent.ACTION_CANCEL,100f,250f)
                assertFalse(overlay().interacting); assertTrue(overlay().revision>revision)
            }
            instrumentation.waitForIdleSync()
            var savedTop=0f; var savedLeft=0f
            ui.runOnIdle { savedTop=overlay().bounds()!!.top; savedLeft=overlay().bounds()!!.left; overlay().hide(); overlay().show("RUN"); overlay().ready(); overlay().message("준비 완료 · 대상 앱에서 실행") }
            instrumentation.waitForIdleSync()
            ui.runOnIdle {
                assertEquals(savedTop,overlay().bounds()!!.top,2f); assertEquals(savedLeft,overlay().bounds()!!.left,2f)
                overlay().running(true); overlay().message("2개 매크로 · 조건 감시 중")
            }
            instrumentation.waitForIdleSync()
            screenshot("overlay-running.png")
            ui.runOnIdle {
                assertNull(overlay().panelView!!.findViewWithTag<View>("overlay-stop-compact"))
                assertFalse(node("overlay-stop").isShown)
                assertTrue(node("overlay-brightness-compact").isShown)
                assertEquals(View.GONE,node("overlay-details").visibility)
                node("overlay-toggle").performClick()
            }
            instrumentation.waitForIdleSync()
            screenshot("overlay-expanded.png")
            ui.runOnIdle {
                assertEquals(View.VISIBLE,node("overlay-details").visibility)
                assertEquals(View.VISIBLE,node("overlay-brightness").visibility)
                assertEquals(View.GONE,node("overlay-start").visibility)
                pointer(MotionEvent.ACTION_DOWN,20f,20f); pointer(MotionEvent.ACTION_MOVE,10000f,10000f); pointer(MotionEvent.ACTION_UP,10000f,10000f)
            }
            instrumentation.waitForIdleSync()
            ui.runOnIdle {
                val area=ui.activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                val box=overlay().bounds()!!
                assertTrue(box.right<=area.width()+2); assertTrue(box.bottom<=area.height()+2)
            }
        } finally { ui.runOnIdle { installed?.hide() } }
    }
}
