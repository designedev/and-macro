package com.personal.screenmacro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.personal.screenmacro.capture.CaptureService
import com.personal.screenmacro.ui.AppScreen
import com.personal.screenmacro.core.*

class MainActivity : ComponentActivity() {
    private var requestedMode = ""
    private var requestedIds = arrayListOf<String>()
    private var message by mutableStateOf<String?>(null)
    private var brightnessAllowed by mutableStateOf(false)
    private var awaitingBrightness = false
    private val brightnessSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshBrightness()
        if (awaitingBrightness) {
            awaitingBrightness = false
            if (brightnessAllowed) requestCapture(requestedMode, requestedIds)
            else message = "시스템 설정 변경을 허용한 뒤 매크로를 실행하세요."
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message = "알림 표시가 제한됩니다. 실행 중 오버레이 정지 버튼을 사용하세요."
    }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) { message = "화면 캡처 동의가 취소되었습니다."; return@registerForActivityResult }
        if (RuntimeStore.status.value.busy || CaptureService.instance != null) { message = "현재 세션을 정지한 뒤 다시 시작하세요."; return@registerForActivityResult }
        startForegroundService(Intent(this, CaptureService::class.java).putExtra("consent", result.data).putExtra("mode", requestedMode).putStringArrayListExtra("macroIds", requestedIds))
        message = "대상 앱으로 이동한 뒤 오버레이 버튼을 누르세요."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedMode = savedInstanceState?.getString("requestedMode") ?: ""
        requestedIds = savedInstanceState?.getStringArrayList("requestedIds") ?: arrayListOf()
        awaitingBrightness = savedInstanceState?.getBoolean("awaitingBrightness") ?: false
        refreshBrightness()
        setContent {
            MaterialTheme {
                AppScreen((application as MacroApplication).repository, message,
                    onDismissMessage = { message = null },
                    onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onNotification = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    notificationGranted = { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
                    brightnessGranted = brightnessAllowed,
                    onBrightness = { openBrightnessSettings() },
                    onCapture = { mode, ids -> requestCapture(mode, ids) })
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("requestedMode", requestedMode); outState.putStringArrayList("requestedIds", requestedIds)
        outState.putBoolean("awaitingBrightness", awaitingBrightness)
        super.onSaveInstanceState(outState)
    }
    private fun requestCapture(mode: String, ids: List<String>) {
        if (RuntimeStore.status.value.busy || CaptureService.instance != null) { message = "현재 실행을 먼저 정지하세요."; return }
        if (!RuntimeStore.accessibilityConnected.value) { message = "접근성 서비스를 먼저 활성화하세요."; return }
        if (mode != "REGISTER" && ids.isEmpty()) { message = "실행할 매크로를 선택하세요."; return }
        requestedMode = mode; requestedIds = ArrayList(ids)
        if (mode == "RUN" && !Settings.System.canWrite(this)) {
            awaitingBrightness = true
            openBrightnessSettings()
            return
        }
        val brightness = (application as MacroApplication).brightness
        if (mode == "RUN" && brightness.pending && !brightness.restore()) {
            message = "이전 밝기를 복구하지 못했습니다. 밝기 권한을 확인하세요."; return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        capture.launch(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
    }
    private fun openBrightnessSettings() {
        runCatching { brightnessSettings.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            .onFailure { awaitingBrightness = false; message = "시스템 설정 변경 화면을 열 수 없습니다. 앱의 특별 접근 권한에서 허용하세요." }
    }
    private fun refreshBrightness() {
        brightnessAllowed = Settings.System.canWrite(this)
        val brightness = (application as MacroApplication).brightness
        if (CaptureService.instance == null && !RuntimeStore.status.value.busy && brightness.pending) {
            message = if (brightness.restore()) "이전 실행의 밝기를 복구했습니다."
                else "밝기 복구가 필요합니다. 시스템 설정 변경 권한을 허용하세요."
        }
    }
    override fun onResume() { super.onResume(); refreshBrightness() }
    override fun onDestroy() {
        if (isFinishing) { RuntimeStore.clearCapture(); RuntimeStore.clearTest() }
        super.onDestroy()
    }
}
