package com.personal.screenmacro.core

/** No app allowlist. Only our UI and OS settings/permission surfaces are excluded. */
object ApplicationPolicy {
    fun canAutomate(packageName: String, ownPackage: String): Boolean = packageName.isNotBlank() &&
        packageName != ownPackage && packageName !in setOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller"
        ) && !packageName.endsWith(".permissioncontroller") && !packageName.endsWith(".packageinstaller")
}

/** A session binds to the ordinary foreground window chosen by pressing the overlay. */
class SessionTarget {
    private var identity: Pair<String, Int>? = null
    fun bind(window: WindowStamp) { check(identity == null); identity = window.packageName to window.windowId }
    fun matches(window: WindowStamp?): Boolean = window != null && identity == (window.packageName to window.windowId)
    fun clear() { identity = null }
}
