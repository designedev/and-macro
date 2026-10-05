package com.personal.screenmacro.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
    private var lastDiagnosticContent: Box? = null
    private var lastDiagnosticLayers: List<WindowLayer>? = null
    private data class VerifiedWindow(val stamp: WindowStamp, val target: WindowLayer, val layers: List<WindowLayer>)
    private var verifiedWindow: VerifiedWindow? = null
    var windowIssueCode = "NONE"; private set
    var interruptedWindow: WindowStamp? = null; private set
    private fun rejectWindow(code: String): WindowStamp? { windowIssueCode = code; return null }
    fun windowIssueMessage() = when (windowIssueCode) {
        "OBSTRUCTED" -> "다른 앱의 창을 닫고 다시 시작하세요."
        "KEYBOARD" -> "키보드를 닫고 대상 앱에서 다시 실행하세요."
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
        if (CaptureService.instance?.watchingTarget == true && CaptureService.instance?.acceptsWindow(stamp) != true && CaptureService.instance?.canWaitForWindow(this) != true) CaptureService.instance?.stopSession("대상 앱 또는 활성 창이 변경되었습니다. 수동으로 재시작하세요.")
    }
    fun applicationWindow(): WindowStamp? {
        interruptedWindow = null; verifiedWindow = null
        val list = windows
        val layers = list.map(::layer)
        val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val content = Box(insets.left.toFloat(), insets.top.toFloat(), (metrics.bounds.width()-insets.right).toFloat(), (metrics.bounds.height()-insets.bottom).toFloat())
        val interrupted = SystemUiPolicy.interrupts(layers, content)
        if (com.personal.screenmacro.BuildConfig.DEBUG && (content != lastDiagnosticContent || layers != lastDiagnosticLayers)) {
            lastDiagnosticContent = content; lastDiagnosticLayers = layers
            val diagnostic = "WINDOW_CHECK content=$content interrupted=$interrupted " +
                layers.joinToString { "id=${it.id} kind=${it.kind} bounds=${it.bounds} active=${it.active} focused=${it.focused}" }
            android.util.Log.i("ScreenMacro", diagnostic)
        }
        if (interrupted) return rejectWindow("SYSTEM_UI")
        fun ownOverlay(w: AccessibilityWindowInfo) = layers.firstOrNull { it.id == w.id }?.ownOverlay == true
        val active = list.firstOrNull { it.isActive }
        val app = if (active != null && (ownOverlay(active) || active.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD)) {
            list.firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } else active?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val pkg = app?.let { selected -> layers.firstOrNull { it.id == selected.id }?.packageName }
        val next = if (pkg != null) pkg to app.id else null
        if (identity != next) { identity = next; revision++ }
        if (app == null || pkg == null || !ApplicationPolicy.canAutomate(pkg, packageName)) return rejectWindow("NO_APPLICATION")
        val target = layers.first { it.id == app.id }
        // A foreign app still ends the session, even when an IME is also present.
        if (layers.any { it.kind != WindowKind.INPUT_METHOD && WindowPolicy.blocks(it, target, layers) }) return rejectWindow("OBSTRUCTED")
        if (layers.any { KeyboardPolicy.visible(it, target, content) }) {
            interruptedWindow = WindowStamp(pkg, app.id, revision)
            return rejectWindow("KEYBOARD")
        }
        val root = rootInActiveWindow ?: return rejectWindow("ROOT_UNAVAILABLE")
        val rootIsOwnOverlay = active != null && ownOverlay(active) && root.packageName?.toString() == packageName
        if (!rootIsOwnOverlay && (root.packageName?.toString() != pkg || root.windowId != app.id)) return rejectWindow("ROOT_MISMATCH")
        // Magnification transforms gesture coordinates; refuse unverified mappings.
        val config = magnificationController.magnificationConfig
        if ((config?.scale ?: 1f) != 1f) return rejectWindow("MAGNIFIED")
        windowIssueCode = "NONE"
        return WindowStamp(pkg, app.id, revision).also { verifiedWindow = VerifiedWindow(it, target, layers) }
    }
    fun exclusions(width: Int = 0, height: Int = 0, checked: WindowStamp? = null): List<Box> {
        // `checked` is used only immediately after checkedWindow, without suspension.
        // Never reuse this snapshot for final gesture validation or another frame.
        val stamp = checked ?: applicationWindow()
        val snapshot = verifiedWindow?.takeIf { it.stamp == stamp && it.target.bounds.valid() }
            ?: return listOf(Box(0f, 0f, width.toFloat(), height.toFloat()))
        val excluded = mutableListOf<Box>()
        overlay?.bounds()?.let { excluded.add(it) }
        excluded += WindowPolicy.exclusions(snapshot.target, snapshot.layers)
        if (width > 0 && height > 0) {
            val b = snapshot.target.bounds
            listOf(Box(0f, 0f, width.toFloat(), b.top), Box(0f, b.bottom, width.toFloat(), height.toFloat()),
                Box(0f, b.top, b.left, b.bottom), Box(b.right, b.top, width.toFloat(), b.bottom)).filter { it.valid() }.forEach(excluded::add)
        }
        return excluded
    }
    suspend fun tap(observation: Observation, macroId: String, valid: () -> Boolean, onDelivery: (Long) -> Unit): Boolean {
        val match = observation.result as? MatchResult.Unique ?: return false
        return withTimeoutOrNull(2500) {
            suspendCancellableCoroutine { continuation ->
                // One new window snapshot verifies identity, app bounds and obscured areas.
                // CaptureService's `valid` callback independently checks current session,
                // geometry, overlay interaction/revision and freshness before dispatch.
                val current = if (!gesturePending && continuation.isActive && valid()) applicationWindow() else null
                val snapshot = verifiedWindow
                if (current != observation.window || snapshot == null ||
                    !WindowPolicy.canTap(snapshot.target, snapshot.layers, match.box, overlay?.bounds())) {
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
