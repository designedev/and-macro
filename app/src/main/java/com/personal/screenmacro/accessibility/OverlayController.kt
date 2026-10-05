package com.personal.screenmacro.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.personal.screenmacro.MainActivity
import com.personal.screenmacro.RuntimeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.personal.screenmacro.capture.CaptureService
import com.personal.screenmacro.core.Box
import com.personal.screenmacro.core.OverlayPosition
import kotlin.math.abs
import kotlin.math.roundToInt

/** One small trusted window: drag its header, leaving the game outside it touchable. */
class OverlayController(private val context: Context,
    private val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    private val token: IBinder? = null,
    private val recoverBrightness: () -> Boolean = { CaptureService.instance?.restoreBrightness() == true },
    private val dimBrightness: () -> Boolean = { CaptureService.instance?.dimBrightness() == true }) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("overlay_position", Context.MODE_PRIVATE)
    private var view: LinearLayout? = null
    internal val panelView: View? get() = view
    private var params: WindowManager.LayoutParams? = null
    private var label: TextView? = null
    private var action: TextView? = null
    private var restore: TextView? = null
    private var details: LinearLayout? = null
    private var diagnosticText: TextView? = null
    private var finishedReason: String? = null
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val refreshDetails = object : Runnable {
        override fun run() {
            if (!running || view == null) return
            if (!collapsed) renderDiagnostics()
            animation.postDelayed(this, 500)
        }
    }
    private fun renderDiagnostics() {
        val text = buildString {
            append(finishedReason?.let { "실행 중단\n$it\n\n클릭·캡처가 종료되었습니다. 내용을 확인한 뒤 닫아주세요." }
                ?: RuntimeStore.status.value.message)
            RuntimeStore.recognitionHint.value?.let { append("\n\n$it") }
            val rows = RuntimeStore.recognition.value
            if (rows.isNotEmpty()) {
                append("\n\n최근 검사 결과")
                if (rows.size > 1) append(" · 처리 시간은 함께 검사한 그룹 기준")
                rows.forEachIndexed { index, row ->
                    append("\n\n${index + 1}. ${row.name}\n${row.explanation()}")
                    row.checkedAt?.let { append("\n${clockFormat.format(Date(it))} · 인식 ${row.durationMs} ms") }
                    row.ageMs?.let { append(" · 캡처 후 ${it} ms") }
                }
            }
        }
        if (diagnosticText?.text?.toString() != text) diagnosticText?.text = text
    }
    private var toggle: TextView? = null
    private var compactBrightness: TextView? = null
    private var dimmed = false
    private var brightnessClickRestores: Boolean? = null
    private val animation = Handler(Looper.getMainLooper())
    private var spinnerIndex = 0
    private val spinnerFrames = arrayOf("[ | ]", "[ / ]", "[ - ]", "[ \\ ]")
    private val spinner = object : Runnable {
        override fun run() {
            if (!running || view == null) return
            label?.text = spinnerFrames[spinnerIndex]
            spinnerIndex = (spinnerIndex + 1) % spinnerFrames.size
            animation.postDelayed(this, 250)
        }
    }
    private fun recover() {
        if (dimmed && recoverBrightness()) brightnessState(false)
    }
    private var title: TextView? = null
    private var collapsed = false
    private var running = false
    var interacting = false; private set
    var revision = 0L; private set
    private var gestureStart: OverlayPosition? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun surface(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun button(text: String, description: String, fill: Int, foreground: Int, click: () -> Unit) = TextView(context).apply {
        this.text = text; textSize = 12f; gravity = Gravity.CENTER; setTextColor(foreground)
        setTypeface(null, Typeface.BOLD); setPadding(dp(10), 0, dp(10), 0)
        background = RippleDrawable(ColorStateList.valueOf(0x3364DAB6), surface(fill, 12), null)
        contentDescription = description; isClickable = true; isFocusable = true
        setOnClickListener { click() }
        minHeight = dp(44)
    }
    fun show(mode: String) {
        hide()
        running = false; collapsed = false; finishedReason = null
        val panel = object : LinearLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (running && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    brightnessClickRestores = restore?.takeIf { it.isShown }?.let { button ->
                        val rect = android.graphics.Rect(0, 0, button.width, button.height)
                        offsetDescendantRectToMyCoords(button, rect)
                        if (rect.contains(event.x.toInt(), event.y.toInt())) dimmed else null
                    }
                    interacting = true; revision++
                    recover()
                }
                return try { super.dispatchTouchEvent(event) }
                finally {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        interacting = false; revision++
                        // TextView may post its click until after dispatchTouchEvent returns.
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL) brightnessClickRestores = null
                    }
                }
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(10))
            background = surface(0xFF111B2E.toInt(), 20, 0xFF334155.toInt())
            elevation = dp(8).toFloat(); clipToOutline = true; tag = "macro-overlay"
            isClickable = true // Retain UP/CANCEL even when the touch starts on empty card space.
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44); tag = "overlay-drag"
            contentDescription = "매크로 패널 이동 · 드래그하세요"
        }
        handle.addView(TextView(context).apply {
            text = "⠿"; textSize = 21f; setTextColor(0xFF64748B.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), dp(44)))
        title = TextView(context).apply {
            text = "AUTO"; textSize = 13f; setTextColor(0xFFE2E8F0.toInt()); setTypeface(null, Typeface.BOLD)
            maxLines = 1; gravity = Gravity.CENTER_VERTICAL
        }.also { handle.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f)) }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(44), 1f))
        toggle = button("⌃", "패널 접기", Color.TRANSPARENT, 0xFF94A3B8.toInt()) {
            if (running || finishedReason != null) setCollapsed(!collapsed)
        }.apply { isEnabled = false; alpha = 0.35f; tag = "overlay-toggle" }
        header.addView(toggle, LinearLayout.LayoutParams(dp(40), dp(44)))
        compactBrightness = button("밝게", "기존 화면 밝기 복구", 0xFF233149.toInt(), 0xFFE2E8F0.toInt()) {
            recover()
        }.apply { visibility = View.GONE; tag = "overlay-brightness-compact" }
        header.addView(compactBrightness, LinearLayout.LayoutParams(dp(52), dp(44)))
        panel.addView(header)
        label = TextView(context).apply {
            text = "화면 캡처 준비 중"; textSize = 11f; setTextColor(0xFF94A3B8.toInt())
            maxLines = 2; minLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), dp(6)); tag = "overlay-status"
        }.also { panel.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "overlay-details" }
        details = content
        diagnosticText = TextView(context).apply {
            textSize = 11f; setTextColor(0xFFCBD5E1.toInt()); setPadding(dp(4), dp(4), dp(4), dp(10))
            tag = "overlay-diagnostic-text"
        }
        // Keep panel bounds stable when timestamps/messages change during recognition.
        val diagnostics = ScrollView(context).apply {
            tag = "overlay-diagnostics"; isFillViewport = false; addView(diagnosticText)
        }
        content.addView(diagnostics, LinearLayout.LayoutParams(-1, dp(180)))
        action = button(if (mode == "REGISTER") "기준 이미지 캡처" else if (mode == "TEST") "현재 앱에서 인식 테스트" else "현재 앱에서 실행",
            "대상 앱에서 실행", 0xFF64DAB6.toInt(), 0xFF08251F.toInt()) {
            val accepted = if (mode == "REGISTER") CaptureService.instance?.captureForEditor() == true
                else CaptureService.instance?.beginFromOverlay() == true
            if (accepted) action?.isEnabled = false
        }.apply { isEnabled = false; alpha = 0.4f; tag = "overlay-start" }
        content.addView(action, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
        val controls = LinearLayout(context)
        restore = button("밝게", "기존 화면 밝기 복구", 0xFF233149.toInt(), 0xFFE2E8F0.toInt()) {
            val restoreOnly = brightnessClickRestores ?: dimmed
            brightnessClickRestores = null
            if (restoreOnly) recover()
            else if (dimBrightness()) { brightnessState(true); setCollapsed(true) }
        }.apply { visibility = View.GONE; tag = "overlay-brightness" }
        controls.addView(restore, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        controls.addView(button("정지", "매크로 정지", 0xFF392231.toInt(), 0xFFFDA4AF.toInt()) {
            if (finishedReason != null) hide()
            else CaptureService.instance?.stopSession("사용자가 정지했습니다.", retainNotice = false)
        }.apply { tag = "overlay-stop" }, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(controls); panel.addView(content)
        val area = safeArea()
        val p = WindowManager.LayoutParams(dp(248), WindowManager.LayoutParams.WRAP_CONTENT, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; this.token = this@OverlayController.token
            x = preferences.getInt("x", maxOf(area.left, area.right - dp(260)))
            y = preferences.getInt("y", area.top + dp(16))
        }
        params = p; view = panel
        installDrag(handle)
        panel.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r-l != oldR-oldL || b-t != oldB-oldT) { revision++; constrain() }
        }
        manager.addView(panel, p)
        panel.post { constrain() }
    }
    private fun safeArea(): android.graphics.Rect {
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return android.graphics.Rect(insets.left + dp(4), insets.top + dp(4),
            metrics.bounds.width() - insets.right - dp(4), metrics.bounds.height() - insets.bottom - dp(4))
    }
    private fun constrain() {
        val v = view ?: return; val p = params ?: return; val area = safeArea()
        val next = OverlayPosition(p.x, p.y).constrained(area.left, area.top, area.right, area.bottom,
            if (v.width > 0) v.width else p.width, v.height)
        if (next.x != p.x || next.y != p.y) {
            p.x = next.x; p.y = next.y; revision++
            if (v.isAttachedToWindow) manager.updateViewLayout(v, p)
        }
    }
    @Suppress("ClickableViewAccessibility")
    private fun installDrag(handle: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var rawX = 0f; var rawY = 0f; var moved = false
        handle.setOnTouchListener { v, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    interacting = true; moved = false; revision++
                    rawX = event.rawX; rawY = event.rawY; gestureStart = OverlayPosition(p.x, p.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX-rawX; val dy = event.rawY-rawY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        gestureStart?.let { p.x = it.x + dx.roundToInt(); p.y = it.y + dy.roundToInt() }
                        constrain(); revision++
                        view?.let { if (it.isAttachedToWindow) manager.updateViewLayout(it, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        gestureStart?.let { p.x = it.x; p.y = it.y }; constrain()
                        view?.let { if (it.isAttachedToWindow) manager.updateViewLayout(it, p) }
                    } else if (moved) preferences.edit().putInt("x", p.x).putInt("y", p.y).apply()
                    else v.performClick()
                    gestureStart = null; interacting = false; revision++; true
                }
                else -> true
            }
        }
        handle.isClickable = true
        handle.setOnClickListener { if (running || finishedReason != null) setCollapsed(!collapsed) }
        // TalkBack can move the card without a pointer drag.
        handle.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                listOf("왼쪽으로 이동", "오른쪽으로 이동", "위로 이동", "아래로 이동").forEachIndexed { i, text ->
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01020000+i, text))
                }
            }
            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                val index = action-0x01020000
                if (index !in 0..3) return super.performAccessibilityAction(host, action, args)
                params?.let { p ->
                    p.x += if (index==0) -dp(48) else if (index==1) dp(48) else 0
                    p.y += if (index==2) -dp(48) else if (index==3) dp(48) else 0
                    constrain(); revision++; view?.let { manager.updateViewLayout(it, p) }
                    preferences.edit().putInt("x", p.x).putInt("y", p.y).apply()
                }
                return true
            }
        }
    }
    private fun setCollapsed(value: Boolean) {
        collapsed = value; revision++
        details?.visibility = if (value) View.GONE else View.VISIBLE
        compactBrightness?.visibility = if (running && value) View.VISIBLE else View.GONE
        title?.text = if (finishedReason != null) "● 중단됨" else if (value) "● 실행 중" else "AUTO"
        title?.setTextColor(if (finishedReason != null) 0xFFFBBF24.toInt() else if (value) 0xFF64DAB6.toInt() else 0xFFE2E8F0.toInt())
        toggle?.text = if (value) "⌄" else "⌃"
        toggle?.contentDescription = if (value) "패널 펼치기" else "패널 접기"
        if (finishedReason != null) label?.text = if (value) "실행 중단 · 펼쳐서 이유 확인" else "실행 중단 · 아래 안내 확인"
        label?.minLines = if (running || value) 1 else 2; label?.maxLines = if (running || value) 1 else 2
        params?.let { p -> p.width = dp(if (value) 228 else 248); view?.let { manager.updateViewLayout(it, p) } }
        if (!value) renderDiagnostics()
        view?.post { constrain() }
    }
    fun running(dimmed: Boolean) {
        running = true; action?.visibility = View.GONE
        restore?.visibility = View.VISIBLE
        brightnessState(dimmed)
        toggle?.isEnabled = true; toggle?.alpha = 1f
        label?.typeface = Typeface.MONOSPACE
        label?.gravity = Gravity.CENTER
        label?.contentDescription = "매크로 실행 중"
        setCollapsed(true)
        animation.removeCallbacks(spinner); spinnerIndex = 0; spinner.run()
        animation.removeCallbacks(refreshDetails); refreshDetails.run()
    }
    fun brightnessState(value: Boolean) {
        dimmed = value
        compactBrightness?.isEnabled = value
        compactBrightness?.alpha = if (value) 1f else 0.5f
        compactBrightness?.text = if (value) "밝게" else "밝음"
        compactBrightness?.contentDescription = if (value) "기존 화면 밝기 복구" else "기존 밝기로 복구됨"
        restore?.text = if (value) "밝게" else "어둡게"
        restore?.contentDescription = if (value) "기존 화면 밝기 복구" else "화면을 최저 밝기로 변경"
    }
    fun message(text: String) {
        if (!running && finishedReason == null) label?.text = text
    }
    fun finished(reason: String) {
        if (view == null) return
        animation.removeCallbacks(spinner); animation.removeCallbacks(refreshDetails)
        running = false; dimmed = false; finishedReason = reason
        action?.visibility = View.GONE; restore?.visibility = View.GONE
        compactBrightness?.visibility = View.GONE
        toggle?.isEnabled = true; toggle?.alpha = 1f
        label?.apply {
            text = "실행 중단 · 펼쳐서 이유 확인"; typeface = Typeface.DEFAULT
            gravity = Gravity.START; contentDescription = text
        }
        (view?.findViewWithTag<View>("overlay-stop") as? TextView)?.apply {
            text = "닫기"; contentDescription = "중단 안내 닫기"
        }
        diagnosticText?.setTextColor(0xFFFDE68A.toInt())
        renderDiagnostics(); setCollapsed(collapsed)
    }
    fun ready() { action?.isEnabled = true; action?.alpha = 1f }
    fun invisible() { view?.visibility = View.INVISIBLE; revision++ }
    fun visible() { view?.visibility = View.VISIBLE; revision++ }
    fun bounds(): Box? {
        val v = view ?: return null
        val location = IntArray(2); v.getLocationOnScreen(location)
        return Box(location[0].toFloat(), location[1].toFloat(), (location[0]+v.width).toFloat(), (location[1]+v.height).toFloat())
    }
    fun hide() {
        animation.removeCallbacks(spinner); animation.removeCallbacks(refreshDetails); running = false; finishedReason = null; dimmed = false; brightnessClickRestores = null
        interacting = false; gestureStart = null; revision++
        view?.let { runCatching { manager.removeView(it) } }
        view = null; params = null; label = null; action = null; restore = null; details = null; diagnosticText = null; toggle = null; compactBrightness = null; title = null
    }
    fun openApp() { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
}
