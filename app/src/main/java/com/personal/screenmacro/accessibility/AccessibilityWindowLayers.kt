package com.personal.screenmacro.accessibility

import android.graphics.Rect
import android.graphics.Region
import android.view.accessibility.AccessibilityWindowInfo
import com.personal.screenmacro.core.*

/** Snapshot only identity, geometry and focus; never read notification text. */
internal fun accessibilityWindowLayer(window: AccessibilityWindowInfo, ownPackage: String): WindowLayer {
    val pkg = window.root?.packageName?.toString()
    val rect = Rect(); window.getBoundsInScreen(rect)
    if (pkg == "com.android.systemui") {
        val region = Region(); window.getRegionInScreen(region)
        if (!region.isEmpty) rect.set(region.bounds)
    }
    val systemUi = pkg == "com.android.systemui" && window.type in listOf(AccessibilityWindowInfo.TYPE_SYSTEM, AccessibilityWindowInfo.TYPE_APPLICATION)
    return WindowLayer(window.id, if (systemUi) WindowKind.SYSTEM_UI else when(window.type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
        else -> WindowKind.OTHER
    }, pkg, window.parent?.id, window.layer, Box(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()), window.isActive, window.isFocused,
        window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && pkg == ownPackage)
}
