package com.personal.screenmacro.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.view.WindowManager
import android.view.WindowInsets
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.personal.screenmacro.RuntimeStore
import com.personal.screenmacro.capture.CaptureService
import com.personal.screenmacro.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MacroAccessibilityService : AccessibilityService() {
    var overlay: OverlayController? = null; private set
    private var revision = 0L
    private var identity: Pair<String, Int>? = null
    private var gesturePending = false
    private var lastWindowDiagnostic: String? = null
    var windowIssueCode = "NONE"; private set
    private fun rejectWindow(code: String): WindowStamp? { windowIssueCode = code; return null }
    fun windowIssueMessage() = when (windowIssueCode) {
        "OBSTRUCTED" -> "다른 앱의 창이나 키보드를 닫고 다시 시작하세요."
        "SYSTEM_UI" -> "알림 또는 알림창을 닫고 대상 앱에서 다시 실행하세요."
        "MAGNIFIED" -> "접근성 화면 확대를 해제하고 다시 시작하세요."
        "ROOT_UNAVAILABLE", "ROOT_MISMATCH" -> "활성 앱의 화면 정보를 확인할 수 없습니다. 대상 화면에서 다시 시작하세요."
        else -> "일반 앱 화면으로 이동한 뒤 다시 시작하세요."
    }
    private fun layer(window: AccessibilityWindowInfo) = accessibilityWindowLayer(window, packageName)
    override fun onServiceConnected() {
        instance = this
        overlay = OverlayController(this)
        RuntimeStore.accessibilityConnected.value = true
        applicationWindow()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Moving/collapsing our trusted panel does not replace the bound game window.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() != packageName) revision++
        val stamp = applicationWindow()
        if (CaptureService.instance?.watchingTarget == true && CaptureService.instance?.acceptsWindow(stamp) != true && !(windowIssueCode == "SYSTEM_UI" && CaptureService.instance?.canWaitForSystemUi == true)) CaptureService.instance?.stopSession("대상 앱 또는 활성 창이 변경되었습니다. 수동으로 재시작하세요.")
    }
    fun applicationWindow(): WindowStamp? {
        val list = windows
        val layers = list.map(::layer)
        val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val content = Box(insets.left.toFloat(), insets.top.toFloat(), (metrics.bounds.width()-insets.right).toFloat(), (metrics.bounds.height()-insets.bottom).toFloat())
        if (com.personal.screenmacro.BuildConfig.DEBUG) {
            val diagnostic = "WINDOW_CHECK content=$content interrupted=${SystemUiPolicy.interrupts(layers, content)} " +
                layers.joinToString { "id=${it.id} kind=${it.kind} bounds=${it.bounds} active=${it.active} focused=${it.focused}" }
            if (diagnostic != lastWindowDiagnostic) {
                lastWindowDiagnostic = diagnostic
                android.util.Log.i("ScreenMacro", diagnostic)
            }
        }
        if (SystemUiPolicy.interrupts(layers, content)) return rejectWindow("SYSTEM_UI")
        fun ownOverlay(w: AccessibilityWindowInfo) = w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && w.root?.packageName?.toString() == packageName
        val active = list.firstOrNull { it.isActive }
        val app = if (active != null && ownOverlay(active)) {
            list.firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } else active?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val pkg = app?.root?.packageName?.toString()
        val next = if (pkg != null) pkg to app.id else null
        if (identity != next) { identity = next; revision++ }
        if (app == null || pkg == null || !ApplicationPolicy.canAutomate(pkg, packageName)) return rejectWindow("NO_APPLICATION")
        val target = layers.first { it.id == app.id }
        if (layers.any { WindowPolicy.blocks(it, target, layers) }) return rejectWindow("OBSTRUCTED")
        val root = rootInActiveWindow ?: return rejectWindow("ROOT_UNAVAILABLE")
        val rootIsOwnOverlay = active != null && ownOverlay(active) && root.packageName?.toString() == packageName
        if (!rootIsOwnOverlay && (root.packageName?.toString() != pkg || root.windowId != app.id)) return rejectWindow("ROOT_MISMATCH")
        // Magnification transforms gesture coordinates; refuse unverified mappings.
        val config = magnificationController.magnificationConfig
        if ((config?.scale ?: 1f) != 1f) return rejectWindow("MAGNIFIED")
        windowIssueCode = "NONE"
        return WindowStamp(pkg, app.id, revision)
    }
    private fun applicationBounds(): Box? {
        val stamp = applicationWindow() ?: return null
        val window = windows.firstOrNull { it.id == stamp.windowId } ?: return null
        val rect = Rect(); window.getBoundsInScreen(rect)
        return Box(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()).takeIf { it.valid() }
    }
    private fun applicationContains(box: Box): Boolean {
        val bounds = applicationBounds() ?: return false
        return box.left >= bounds.left && box.top >= bounds.top && box.right <= bounds.right && box.bottom <= bounds.bottom
    }
    fun exclusions(width: Int = 0, height: Int = 0): List<Box> {
        val excluded = mutableListOf<Box>()
        overlay?.bounds()?.let { excluded.add(it) }
        val stamp = applicationWindow() ?: return listOf(Box(0f, 0f, width.toFloat(), height.toFloat()))
        val layers = windows.map(::layer)
        val target = layers.firstOrNull { it.id == stamp.windowId } ?: return listOf(Box(0f, 0f, width.toFloat(), height.toFloat()))
        excluded += WindowPolicy.exclusions(target, layers)
        if (width > 0 && height > 0) {
            val b = applicationBounds() ?: return listOf(Box(0f, 0f, width.toFloat(), height.toFloat()))
            listOf(Box(0f, 0f, width.toFloat(), b.top), Box(0f, b.bottom, width.toFloat(), height.toFloat()),
                Box(0f, b.top, b.left, b.bottom), Box(b.right, b.top, width.toFloat(), b.bottom)).filter { it.valid() }.forEach(excluded::add)
        }
        return excluded
    }
    suspend fun tap(observation: Observation, macroId: String, valid: () -> Boolean, onDelivery: (Long) -> Unit): Boolean {
        val match = observation.result as? MatchResult.Unique ?: return false
        return withTimeoutOrNull(2500) {
            suspendCancellableCoroutine { continuation ->
                if (gesturePending || !continuation.isActive || !valid() || applicationWindow() != observation.window || !applicationContains(match.box) || exclusions().any { it.intersects(match.box) }) {
                    continuation.resume(false); return@suspendCancellableCoroutine
                }
                val path = Path().apply { moveTo(match.box.centerX, match.box.centerY) }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
                if (!continuation.isActive || !valid()) { continuation.resume(false); return@suspendCancellableCoroutine }
                gesturePending = true
                // This check and dispatch run on the main looper with no suspension in between.
                val deliveredAt = SystemClock.elapsedRealtime()
                onDelivery(deliveredAt)
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { gesturePending = false; if (continuation.isActive) continuation.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { gesturePending = false; if (continuation.isActive) continuation.resume(false) }
                }, Handler(Looper.getMainLooper()))
                RuntimeStore.log(macroId, "DELIVERED_AT_$deliveredAt")
                if (!accepted) { gesturePending = false; if (continuation.isActive) continuation.resume(false) }
                // An already dispatched touch remains owned by Android until its callback.
            }
        } ?: run {
            CaptureService.instance?.stopSession("터치 완료 응답이 없습니다. 수동으로 재시작하세요.")
            false
        }
    }
    override fun onInterrupt() { CaptureService.instance?.stopSession("접근성 서비스가 중단되었습니다.") }
    override fun onDestroy() {
        CaptureService.instance?.stopSession("접근성 연결이 해제되었습니다.")
        overlay?.hide(); overlay = null
        instance = null; RuntimeStore.accessibilityConnected.value = false
        super.onDestroy()
    }
    companion object { var instance: MacroAccessibilityService? = null; private set }
}
