package com.personal.screenmacro

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.SystemClock
import android.view.WindowInsets
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.accessibility.accessibilityWindowLayer
import com.personal.screenmacro.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SystemNotificationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(command: String) = automation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    @Test fun realHeadsUpAndExpandedShadeWaitThenSameWindowReturns() {
        val context=ui.activity
        val manager=context.getSystemService(NotificationManager::class.java)
        val previous=automation.serviceInfo
        val previousFlags=previous.flags
        automation.serviceInfo=previous.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val metrics=context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics
        val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val content=Box(insets.left.toFloat(),insets.top.toFloat(),(metrics.bounds.width()-insets.right).toFloat(),(metrics.bounds.height()-insets.bottom).toFloat())
        fun layers()=automation.windows.map { accessibilityWindowLayer(it,context.packageName) }
        fun waitFor(predicate: () -> Boolean) {
            val until=SystemClock.uptimeMillis()+8000
            while (!predicate() && SystemClock.uptimeMillis()<until) SystemClock.sleep(100)
            assertTrue(predicate())
        }
        val notificationId=61277
        val channel="notification-window-test"
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        try {
            shell("cmd statusbar collapse")
            waitFor { !SystemUiPolicy.interrupts(layers(),content) }
            val original=layers().first { it.kind==WindowKind.APPLICATION && it.packageName==context.packageName && it.active }
            manager.createNotificationChannel(NotificationChannel(channel,"Synthetic window test",NotificationManager.IMPORTANCE_HIGH))
            manager.notify(notificationId,Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_macro)
                .setContentTitle("Synthetic macro notification").setContentText("Test only")
                .setDefaults(Notification.DEFAULT_SOUND).setTimeoutAfter(20000).build())
            waitFor { SystemUiPolicy.interrupts(layers(),content) }
            manager.cancel(notificationId)
            waitFor { !SystemUiPolicy.interrupts(layers(),content) }
            assertTrue(layers().any { it.id==original.id && it.packageName==original.packageName && it.active })
            shell("cmd statusbar expand-notifications")
            waitFor { SystemUiPolicy.interrupts(layers(),content) }
            shell("cmd statusbar collapse")
            waitFor { !SystemUiPolicy.interrupts(layers(),content) }
            assertTrue(layers().any { it.id==original.id && it.packageName==original.packageName && it.active })
        } finally {
            manager.cancel(notificationId); manager.deleteNotificationChannel(channel)
            shell("cmd statusbar collapse")
            previous.flags=previousFlags; automation.serviceInfo=previous
        }
    }
}
