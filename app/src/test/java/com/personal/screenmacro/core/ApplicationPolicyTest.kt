package com.personal.screenmacro.core

import org.junit.Assert.*
import org.junit.Test

class ApplicationPolicyTest {
    @Test fun ordinaryAppsHaveNoPackageAllowlist() {
        listOf("com.nexon.devcat.mm", "com.nexon.devcat.mmgalaxy", "com.example.unrelated.app").forEach {
            assertTrue(ApplicationPolicy.canAutomate(it, "com.personal.screenmacro"))
        }
    }
    @Test fun ownAndSystemPermissionScreensAreExcluded() {
        listOf("", "com.personal.screenmacro", "android", "com.android.systemui", "com.android.settings",
            "com.google.android.permissioncontroller", "com.vendor.permissioncontroller", "com.android.packageinstaller").forEach {
            assertFalse(ApplicationPolicy.canAutomate(it, "com.personal.screenmacro"))
        }
    }
    @Test fun sessionAllowsFreshRevisionsButStopsAtAppOrWindowChange() {
        val target = SessionTarget()
        val window = WindowStamp("com.example.app", 42, 1)
        assertFalse(target.matches(window))
        target.bind(window)
        assertTrue(target.matches(window.copy(revision = 2)))
        assertFalse(target.matches(window.copy(packageName = "com.example.other")))
        assertFalse(target.matches(window.copy(windowId = 43)))
        assertFalse(target.matches(null))
        target.clear(); assertFalse(target.matches(window))
    }
}
