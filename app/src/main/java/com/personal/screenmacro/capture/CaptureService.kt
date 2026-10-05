package com.personal.screenmacro.capture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.personal.screenmacro.*
import com.personal.screenmacro.accessibility.MacroAccessibilityService
import com.personal.screenmacro.core.*
import com.personal.screenmacro.recognition.Matchers
import kotlinx.coroutines.*

class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var source: ScreenCaptureSource? = null
    private var work: Job? = null
    private var mode = ""
    private var macros: List<Macro> = emptyList()
    private val macro get() = macros.firstOrNull()
    private var ending = false
    private var ready = false
    private data class RecognizedFrame(val observation: Observation, val bitmap: android.graphics.Bitmap?, val texts: List<TextCandidate>, val duration: Long)
    private val target = SessionTarget()
    fun acceptsWindow(stamp: WindowStamp?) = target.matches(stamp)
    var watchingTarget = false; private set
    private val repository get() = (application as MacroApplication).repository
    private val stopNotices get() = (application as MacroApplication).stopNotices
    private val brightness get() = (application as MacroApplication).brightness
    private val canWaitForTransientWindow get() = mode == "RUN" && watchingTarget && !ending
    fun canWaitForWindow(access: MacroAccessibilityService): Boolean = canWaitForTransientWindow &&
        (access.windowIssueCode == "SYSTEM_UI" ||
            (access.windowIssueCode == "KEYBOARD" && target.matches(access.interruptedWindow)))
    private var waitingReason: String? = null
    private val matcher by lazy { Matchers(repository) }
    private var matcherCreated = false
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { stopSession("화면이 꺼지거나 잠겼습니다. 수동으로 재시작하세요.") } }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) stopSession("디스플레이가 해제되었습니다.") }
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY && source?.sizeConfirmed == true && !geometryValid()) stopSession("화면 방향·해상도가 변경되었습니다. 기준 이미지를 확인하고 재시작하세요.") }
    }
    override fun onCreate() {
        super.onCreate()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(mainLooper))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSession("사용자가 정지했습니다.", retainNotice = false); return START_NOT_STICKY }
        if (instance != null || source != null || intent == null) { stopSelf(startId); return START_NOT_STICKY }
        instance = this
        mode = intent.getStringExtra("mode") ?: ""
        setStatus(EngineState.STARTING, "화면 캡처 준비 중 · 대상 앱으로 이동하세요")
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(NotificationChannel("capture", "매크로 실행", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, CaptureService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "capture").setSmallIcon(R.drawable.ic_macro).setContentTitle("AUTO")
                .setContentText("화면 캡처 중 · 오버레이에서 실행 또는 정지").setContentIntent(open).setOngoing(true).addAction(0, "정지", stop).build()
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val access = MacroAccessibilityService.instance ?: error("접근성 서비스를 활성화하세요.")
            val consent = intent.getParcelableExtra("consent", Intent::class.java) ?: error("캡처 동의가 없습니다.")
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent) ?: error("캡처 세션 획득 실패")
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            source = ScreenCaptureSource(projection, bounds.width(), bounds.height(), rotation(), resources.configuration.densityDpi) { stopSession(it, true) }
            RuntimeStore.beginDiagnostics(emptyList())
            access.overlay?.show(mode)
            work = scope.launch {
                try {
                    if (mode != "REGISTER") {
                        val ids = intent.getStringArrayListExtra("macroIds") ?: intent.getStringExtra("macroId")?.let { arrayListOf(it) } ?: error("매크로 없음")
                        macros = repository.getOrdered(ids)
                        RuntimeStore.beginDiagnostics(macros)
                        check(mode != "TEST" || macros.size == 1) { "인식 테스트는 한 매크로씩 진행하세요." }
                    }
                    withTimeout(5000) { while (source?.sizeConfirmed != true) delay(50) }
                    if (macros.any { it.type == RecognitionType.TEXT }) {
                        setStatus(EngineState.STARTING, "문구 인식 준비 중 · 잠시 기다려주세요")
                        matcherCreated = true
                        withTimeout(30000) { matcher.warmUpText() }
                    }
                    check(live()) { "캡처 세션이 종료되었습니다. 다시 시작하세요." }
                    work = null
                    ready = true
                    setStatus(EngineState.STARTING, "준비 완료${if (macros.size > 1) " · ${macros.size}개 매크로" else ""} · 대상 앱에서 버튼 누르기")
                    RuntimeStore.log(macro?.id, "READY")
                    access.overlay?.ready()
                } catch (e: CancellationException) { if (e is TimeoutCancellationException) stopSession("화면 캡처·문구 인식 준비 시간이 초과되었습니다. 다시 시작하세요.", true); else throw e }
                catch (e: Exception) { stopSession(e.message ?: "초기화 실패", true) }
            }
        } catch (e: Exception) { stopSession(e.message ?: "화면 캡처 시작 실패", true) }
        return START_NOT_STICKY
    }
    private fun rotation() = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1
    private fun geometryValid(): Boolean {
        val s = source ?: return false
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return s.active && s.sizeConfirmed && s.width == bounds.width() && s.height == bounds.height() && s.rotation == rotation()
    }
    private fun live() = !ending && instance === this && geometryValid() &&
        getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun checkedWindow(access: MacroAccessibilityService): WindowStamp {
        val current = access.applicationWindow()
        if (canWaitForWindow(access)) {
            val reason = access.windowIssueCode
            if (waitingReason != reason) {
                waitingReason = reason
                RuntimeStore.log(macro?.id, "${reason}_WAIT")
            }
            val message = if (reason == "KEYBOARD") "키보드 대기 · 닫으면 재개" else "알림 대기 · 대상 앱으로 돌아오면 재개"
            setStatus(EngineState.WATCHING, message)
            throw TransientWindowInterruptedException()
        }
        check(current != null && target.matches(current)) { "SESSION_INVALID" }
        waitingReason?.let { reason ->
            waitingReason = null
            RuntimeStore.log(macro?.id, "${reason}_RESUMED")
        }
        return current
    }
    private fun valid(o: Observation): Boolean {
        val s = source ?: return false
        val match = o.result as? MatchResult.Unique
        val within = match == null || (match.box.valid() && match.box.left >= 0 && match.box.top >= 0 && match.box.right <= o.width && match.box.bottom <= o.height)
        if (!live() || o.sessionId != s.sessionId || o.width != s.width || o.height != s.height || o.rotation != s.rotation || !within || !target.matches(o.window)) return false
        val access = MacroAccessibilityService.instance ?: return false
        val current = checkedWindow(access)
        // A transient notification/window revision invalidates coordinates, not the session.
        if (current != o.window) throw ObservationChangedException()
        return true
    }
    private suspend fun stableOverlay(access: MacroAccessibilityService): Long {
        while (access.overlay?.interacting == true) { check(live()) { "SESSION_INVALID" }; delay(50) }
        return access.overlay?.revision ?: 0
    }
    private fun touchReady(o: Observation): Boolean {
        val overlay = MacroAccessibilityService.instance?.overlay
        return overlay?.interacting != true && (overlay?.revision ?: 0) == o.overlayRevision &&
            runCatching { valid(o) }.getOrDefault(false) && o.isFresh(SystemClock.elapsedRealtime()) && watchingTarget
    }
    private suspend fun observe(m: Macro, preview: Boolean = false): RecognizedFrame {
        check(live()) { "SESSION_INVALID" }
        val access = MacroAccessibilityService.instance ?: error("SESSION_INVALID")
        val overlayRevision = stableOverlay(access)
        val window = checkedWindow(access)
        val s = source ?: error("SESSION_INVALID")
        if (m.type == RecognitionType.IMAGE) check(m.referenceWidth == s.width && m.referenceHeight == s.height && m.referenceRotation == s.rotation) { "기준 이미지 등록 당시 화면 크기·방향과 다릅니다. 재등록하세요." }
        val frame = try { s.fresh(window) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { checkedWindow(access); throw e }
        var retain = false
        try {
            check(live()) { "SESSION_INVALID" }
            if (checkedWindow(access) != window) throw ObservationChangedException()
            val excluded = access.exclusions(s.width, s.height)
            val start = SystemClock.elapsedRealtime()
            matcherCreated = true
            val outcome = matcher.findDetailed(frame.bitmap, m, excluded)
            val result = outcome.result
            val observation = Observation(frame.session, frame.time, frame.bitmap.width, frame.bitmap.height, frame.rotation, window, result, overlayRevision)
            val duration = SystemClock.elapsedRealtime() - start
            // Session validity and frame age are separate: dry runs may show slow results,
            // while the engine and the final dispatch gate must reject expired coordinates.
            if (overlayRevision != (access.overlay?.revision ?: 0)) throw ObservationChangedException()
            check(valid(observation)) { "SESSION_INVALID" }
            RuntimeStore.recognized(m, result, duration, !observation.isFresh(SystemClock.elapsedRealtime()))
            RuntimeStore.log(m.id, when(result) { is MatchResult.Unique -> "MATCH_UNIQUE"; is MatchResult.Ambiguous -> "MATCH_AMBIGUOUS"; else -> "MATCH_ABSENT" }, duration)
            if (m.type == RecognitionType.TEXT) RuntimeStore.log(m.id, "OCR_LINES_${outcome.texts.size}", duration)
            if (preview) retain = true
            return RecognizedFrame(observation, if (retain) frame.bitmap else null, if (preview) outcome.texts else emptyList(), duration)
        } finally { if (!retain) frame.bitmap.recycle() }
    }
    private suspend fun observeAll(rules: List<Macro>): Map<String, Observation> {
        check(live()) { "SESSION_INVALID" }
        val access = MacroAccessibilityService.instance ?: error("SESSION_INVALID")
        val overlayRevision = stableOverlay(access)
        val window = checkedWindow(access)
        val s = source ?: error("SESSION_INVALID")
        rules.filter { it.type == RecognitionType.IMAGE }.forEach {
            check(it.referenceWidth == s.width && it.referenceHeight == s.height && it.referenceRotation == s.rotation) { "기준 이미지 등록 당시 화면 크기·방향과 다릅니다. 재등록하세요." }
        }
        val frame = try { s.fresh(window) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { checkedWindow(access); throw e }
        try {
            check(live()) { "SESSION_INVALID" }
            if (checkedWindow(access) != window) throw ObservationChangedException()
            val excluded = access.exclusions(s.width, s.height)
            val started = SystemClock.elapsedRealtime()
            matcherCreated = true
            val outcomes = matcher.findAll(frame.bitmap, rules, excluded)
            val duration = SystemClock.elapsedRealtime() - started
            if (overlayRevision != (access.overlay?.revision ?: 0)) throw ObservationChangedException()
            return rules.associate { rule ->
                val result = outcomes.getValue(rule.id).result
                val observation = Observation(frame.session, frame.time, frame.bitmap.width, frame.bitmap.height, frame.rotation, window, result, overlayRevision)
                check(valid(observation)) { "SESSION_INVALID" }
                RuntimeStore.log(rule.id, when (result) { is MatchResult.Unique -> "MATCH_UNIQUE"; is MatchResult.Ambiguous -> "MATCH_AMBIGUOUS"; else -> "MATCH_ABSENT" }, duration)
                rule.id to observation
            }.also { RuntimeStore.recognizedBatch(it, duration, SystemClock.elapsedRealtime()) }
        } finally { frame.bitmap.recycle() }
    }
    fun beginFromOverlay(): Boolean {
        if (!ready || work?.isActive == true || watchingTarget) return false
        if (!live()) { stopSession("캡처 세션이 유효하지 않습니다. 다시 시작하세요.", true); return false }
        val m = macro ?: run { stopSession("매크로 설정을 불러오지 못했습니다.", true); return false }
        val access = MacroAccessibilityService.instance ?: run { stopSession("접근성 연결을 확인하세요.", true); return false }
        val window = access.applicationWindow()
        if (window == null) { RuntimeStore.log(m.id, "WINDOW_UNAVAILABLE_${access.windowIssueCode}"); stopSession(access.windowIssueMessage(), true); return false }
        if (mode == "RUN") {
            try { brightness.begin(); RuntimeStore.log(m.id, "BRIGHTNESS_MINIMUM") }
            catch (e: Exception) { stopSession(e.message ?: "밝기를 변경하지 못했습니다.", true); return false }
            stopNotices.begin()
            access.overlay?.running(true)
        }
        ready = false
        target.bind(window)
        watchingTarget = true
        RuntimeStore.log(m.id, if (mode == "TEST") "TEST_STARTED" else "RUN_STARTED")
        work = scope.launch {
            try {
                if (mode == "TEST") {
                    setStatus(EngineState.WATCHING, "인식 테스트 · 클릭 없음")
                    val found = observe(m, true)
                    RuntimeStore.clearTest()
                    RuntimeStore.testResult.value = TestPreview(found.bitmap!!, found.observation.result, found.duration, m.query, m.matchMode, found.texts, found.observation.isFresh(SystemClock.elapsedRealtime()))
                    watchingTarget = false
                    stopSession("인식 테스트 완료 · 클릭하지 않았습니다.", retainNotice = false)
                    access.overlay?.openApp()
                } else if (macros.size > 1) {
                    MultiMacroEngine(object : MultiEnginePort {
                        override fun now() = SystemClock.elapsedRealtime()
                        override suspend fun pause(ms: Long) { delay(ms) }
                        override suspend fun observe(macros: List<Macro>) = observeAll(macros)
                        override fun valid(observation: Observation) = this@CaptureService.valid(observation)
                        override suspend fun tap(macro: Macro, observation: Observation, onDelivery: (Long) -> Unit) = access.tap(observation, macro.id,
                            { touchReady(observation) }, onDelivery)
                        override fun status(state: EngineState, message: String) = setStatus(state, message)
                        override fun log(macroId: String?, result: String, durationMs: Long, error: String?) { RuntimeStore.log(macroId, result, durationMs, error) }
                    }).run(macros)
                } else {
                    MacroEngine(object : EnginePort {
                        override fun now() = SystemClock.elapsedRealtime()
                        override suspend fun pause(ms: Long) { delay(ms) }
                        override suspend fun observe(macro: Macro) = this@CaptureService.observe(macro).observation
                        override fun valid(observation: Observation) = this@CaptureService.valid(observation)
                        override suspend fun tap(observation: Observation, onDelivery: (Long) -> Unit) = access.tap(observation, m.id, { touchReady(observation) }, { at ->
                            onDelivery(at)
                        })
                        override fun status(state: EngineState, message: String) = setStatus(state, message)
                        override fun log(result: String, durationMs: Long, error: String?) { RuntimeStore.log(m.id, result, durationMs, error) }
                    }).run(m)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { stopSession(if (e.message == "SESSION_INVALID") "화면 또는 활성 창이 바뀌었습니다. 수동 재시작이 필요합니다." else e.message ?: "실행 오류", true) }
        }
        return true
    }
    fun captureForEditor(): Boolean {
        if (!ready || work?.isActive == true) return false
        if (!live()) { stopSession("캡처 세션이 유효하지 않습니다. 다시 시작하세요.", true); return false }
        val access = MacroAccessibilityService.instance ?: run { stopSession("접근성 연결을 확인하세요.", true); return false }
        ready = false
        work = scope.launch {
            try {
                val window = access.applicationWindow() ?: error("대상 앱의 일반 화면에서 캡처하세요.")
                target.bind(window)
                watchingTarget = true
                access.overlay?.invisible()
                delay(200)
                val frame = source!!.fresh(window)
                if (!live() || access.applicationWindow() != window) { frame.bitmap.recycle(); error("캡처 도중 활성 화면이 바뀌었습니다.") }
                RuntimeStore.clearCapture()
                RuntimeStore.pendingCapture.value = CapturedEditorFrame(frame.bitmap, frame.rotation)
                watchingTarget = false
                stopSession("기준 화면 캡처 완료 · 앱에서 영역을 자르세요", retainNotice = false)
                access.overlay?.openApp()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { stopSession(e.message ?: "캡처 실패", true) }
        }
        return true
    }
    private fun setStatus(state: EngineState, message: String) {
        RuntimeStore.status.value = RuntimeStatus(state, message, !ending)
        MacroAccessibilityService.instance?.overlay?.message(message)
        if (BuildConfig.DEBUG) android.util.Log.i("ScreenMacro", "STATE_$state")
    }
    fun restoreBrightness(): Boolean {
        val restored = runCatching { brightness.restore() }.getOrDefault(false)
        RuntimeStore.log(macro?.id, if (restored) "BRIGHTNESS_RESTORED" else "BRIGHTNESS_RESTORE_FAILED")
        if (restored) MacroAccessibilityService.instance?.overlay?.brightnessState(false)
        if (!restored) android.widget.Toast.makeText(this, "밝기 복구 실패 · 시스템 설정 변경 권한을 확인하세요.", android.widget.Toast.LENGTH_LONG).show()
        return restored
    }
    fun dimBrightness(): Boolean {
        if (mode != "RUN" || ending || !watchingTarget || !live()) return false
        return try {
            brightness.begin()
            RuntimeStore.log(macro?.id, "BRIGHTNESS_MINIMUM")
            MacroAccessibilityService.instance?.overlay?.brightnessState(true)
            true
        } catch (e: Exception) {
            if (!brightness.pending) MacroAccessibilityService.instance?.overlay?.brightnessState(false)
            android.widget.Toast.makeText(this, e.message ?: "밝기를 변경하지 못했습니다.", android.widget.Toast.LENGTH_LONG).show()
            false
        }
    }
    fun stopSession(reason: String, error: Boolean = false, retainNotice: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) { Handler(mainLooper).post { stopSession(reason, error, retainNotice) }; return }
        if (ending) return
        ending = true; ready = false; watchingTarget = false; target.clear()
        if (BuildConfig.DEBUG) android.util.Log.i("ScreenMacro", "SESSION_STOP: $reason")
        if (error) android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show()
        RuntimeStore.status.value = RuntimeStatus(EngineState.STOPPING, reason, true)
        work?.cancel(); work = null
        val restored = if (mode == "RUN") restoreBrightness() else true
        source?.close(); source = null
        val finalReason = if (restored) reason else "$reason · 밝기 복구 실패: 권한을 확인하세요."
        val showNotice = retainNotice || !restored
        RuntimeStore.lastStop.value = stopNotices.finish(if (showNotice) finalReason else null)
        if (showNotice) MacroAccessibilityService.instance?.overlay?.finished(finalReason)
        else MacroAccessibilityService.instance?.overlay?.hide()
        RuntimeStore.log(macro?.id, "STOPPED", error = if (error) "SESSION_STOP" else null)
        RuntimeStore.status.value = RuntimeStatus(if (error || !restored) EngineState.ERROR else EngineState.IDLE,
            finalReason, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (!ending) stopSession("서비스가 종료되었습니다. 자동 재시작하지 않습니다.")
        runCatching { unregisterReceiver(receiver) }
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        scope.cancel()
        if (matcherCreated) scope.coroutineContext[Job]?.invokeOnCompletion { matcher.close() }
        if (instance === this) instance = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object { var instance: CaptureService? = null; private set }
}
