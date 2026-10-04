package com.personal.screenmacro.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.screenmacro.*
import com.personal.screenmacro.capture.CaptureService
import com.personal.screenmacro.core.*
import com.personal.screenmacro.data.MacroRepository
import com.personal.screenmacro.recognition.Matchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppScreen(repository: MacroRepository, message: String?, onDismissMessage: () -> Unit,
    onAccessibility: () -> Unit, onNotification: () -> Unit, notificationGranted: () -> Boolean,
    brightnessGranted: Boolean, onBrightness: () -> Unit,
    onCapture: (String, List<String>) -> Unit) {
    val macros by repository.macros.collectAsState(initial = emptyList())
    val status by RuntimeStore.status.collectAsState()
    val connected by RuntimeStore.accessibilityConnected.collectAsState()
    val preview by RuntimeStore.testResult.collectAsState()
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var newId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var delete by remember { mutableStateOf<Macro?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val locked = status.busy || managing || reordering
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); onDismissMessage() } }
    Scaffold(topBar = { TopAppBar(title = { Text("AUTO", fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = 2.sp) }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status.message, style = MaterialTheme.typography.titleSmall)
                Text("${status.state} · 화면 인식·자동 클릭", style = MaterialTheme.typography.bodySmall)
                if (status.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { CaptureService.instance?.stopSession("사용자가 정지했습니다.") }) { Text("정지") }
                }
            } }
            when {
                editorId != null -> {
                    val editing = if (editorId == "NEW") Macro(id = newId) else macros.find { it.id == editorId }
                    if (editing != null) EditorScreen(editing, repository, status.busy, onCapture, onSave = {
                        editorId = null; newId = UUID.randomUUID().toString()
                    }, onCancel = { editorId = null; RuntimeStore.clearCapture() })
                    else Text("설정을 불러오는 중…")
                }
                preview != null -> {
                    BackHandler { RuntimeStore.clearTest() }
                    Text("인식 테스트 · 터치 없음", style = MaterialTheme.typography.titleMedium)
                    val result = preview!!.result
                    Text(when (result) { is MatchResult.Unique -> "유일한 후보 · 중심 (${result.box.centerX.toInt()}, ${result.box.centerY.toInt()})"; is MatchResult.Ambiguous -> "후보 ${result.count}개 · 클릭 불가"; else -> "조건 없음" })
                    Text("처리 시간 ${preview!!.duration} ms")
                    if (!preview!!.fresh) Text("1초를 초과한 인식 결과입니다. 실행 시 클릭하지 않고 다시 인식합니다. 검색 영역을 줄여주세요.", style = MaterialTheme.typography.bodySmall)
                    if (preview!!.query.isNotEmpty()) {
                        Text("찾을 문구: ${preview!!.query} · ${if (preview!!.matchMode == MatchMode.EXACT) "정확히 일치" else "포함"}", style = MaterialTheme.typography.bodySmall)
                        Text("읽은 문구 ${preview!!.texts.size}개 · 아래 목록은 스크롤할 수 있습니다", style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                            items(preview!!.texts) { candidate -> Text(candidate.text, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (preview!!.texts.isEmpty()) Text("검색 영역 안에서 문구를 읽지 못했습니다. 캡처 화면과 오버레이 위치를 확인하세요.", style = MaterialTheme.typography.bodySmall)
                    }
                    FrameCanvas(preview!!.bitmap, (result as? MatchResult.Unique)?.box, Modifier.fillMaxWidth().weight(1f))
                    Button(onClick = { RuntimeStore.clearTest() }) { Text("목록으로") }
                }
                showLogs -> {
                    val logs by RuntimeStore.logs.collectAsState()
                    BackHandler { showLogs = false }
                    OutlinedButton(onClick = { showLogs = false }) { Text("목록으로") }
                    Text("최근 ${logs.size}건 · 앱 종료 시 삭제", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(logs.asReversed()) { log ->
                            Text("${SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA).format(Date(log.time))}  ${log.result}\n${log.macroId?.take(8) ?: "세션"} · ${log.durationMs} ms ${log.errorCode ?: ""}", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                }
                else -> {
                    PermissionStrip(connected, notificationGranted(), brightnessGranted, locked, showHelp,
                        onAccessibility, onNotification, onBrightness, { showHelp = !showHelp })
                    if (showHelp || !connected) {
                        Text("접근성으로 활성 앱 확인과 터치를 실행합니다. 화면은 기기 안에서 처리하며 인터넷을 사용하지 않습니다. 접근성 활성화가 제한되면 설정 → 앱 → AUTO → 우측 메뉴 → 제한된 설정 허용을 확인하세요. 실행 중에는 최저 밝기로 낮추고 종료하면 원래 밝기로 복구합니다. 아이콘을 길게 누르면 항목과 상태를 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { editorId = "NEW" }, enabled = !locked) { Text("새 매크로") }
                        TextButton(onClick = { showLogs = true }, enabled = !reordering) { Text("실행 로그") }
                    }
                    val selected = macros.filter { it.selected }
                    Button(onClick = { onCapture("RUN", selected.map { it.id }) }, enabled = connected && !locked && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("선택한 매크로 실행 (${selected.size}개)")
                    }
                    Text("체크한 매크로를 함께 감시합니다. 오른쪽 ☰를 드래그하면 위쪽부터 우선 클릭합니다.", style = MaterialTheme.typography.bodySmall)
                    if (macros.isEmpty()) Text("찾을 문구를 입력하거나 대상 앱 화면에서 기준 이미지를 등록하세요.")
                    PriorityMacroList(macros, status.busy || managing, connected, Modifier.weight(1f),
                        onSelect = { m, selectedValue ->
                            managing = true
                            scope.launch {
                                try { repository.setSelected(m.id, selectedValue) }
                                catch (e: Exception) { error = e.message }
                                finally { managing = false }
                            }
                        },
                        onReorder = { ids -> repository.reorder(ids) },
                        onInteraction = { reordering = it }, onError = { error = it },
                        onStart = { onCapture("RUN", listOf(it.id)) }, onTest = { onCapture("TEST", listOf(it.id)) },
                        onEdit = { editorId = it.id }, onDelete = { delete = it })
                }
            }
        }
    }
    delete?.let { m -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("매크로 삭제") }, text = { Text("${m.name}과 기준 이미지를 삭제합니다.") },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { repository.delete(m) }.onFailure { error = it.message }; delete = null } }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("취소") } }) }
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text("확인 필요") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { error = null }) { Text("확인") } }) }
}

@Composable private fun EditorScreen(m: Macro, repository: MacroRepository, busy: Boolean, onCapture: (String, List<String>) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable(m.id) { mutableStateOf(m.name) }
    var type by rememberSaveable(m.id) { mutableStateOf(m.type.name) }
    var query by rememberSaveable(m.id) { mutableStateOf(m.query) }
    var contains by rememberSaveable(m.id) { mutableStateOf(m.matchMode == MatchMode.CONTAINS) }
    var ignoreCase by rememberSaveable(m.id) { mutableStateOf(m.ignoreCase) }
    var interval by rememberSaveable(m.id) { mutableStateOf((m.intervalMs / 1000.0).toString()) }
    var pre by rememberSaveable(m.id) { mutableStateOf((m.preDelayMs / 1000.0).toString()) }
    var post by rememberSaveable(m.id) { mutableStateOf((m.postDelayMs / 1000.0).toString()) }
    var threshold by rememberSaveable(m.id) { mutableFloatStateOf(m.threshold.toFloat()) }
    var template by rememberSaveable(m.id) { mutableStateOf(m.templatePath) }
    var width by rememberSaveable(m.id) { mutableIntStateOf(m.referenceWidth) }
    var height by rememberSaveable(m.id) { mutableIntStateOf(m.referenceHeight) }
    var rotation by rememberSaveable(m.id) { mutableIntStateOf(m.referenceRotation) }
    var left by rememberSaveable(m.id) { mutableStateOf(m.region.left.toString()) }
    var top by rememberSaveable(m.id) { mutableStateOf(m.region.top.toString()) }
    var right by rememberSaveable(m.id) { mutableStateOf(m.region.right.toString()) }
    var bottom by rememberSaveable(m.id) { mutableStateOf(m.region.bottom.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val captured by RuntimeStore.pendingCapture.collectAsState()
    val scope = rememberCoroutineScope()
    var thumbnail by remember(template) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(template) { template?.let { runCatching { repository.bitmap(it) }.onSuccess { thumbnail = it } } }
    DisposableEffect(thumbnail) { val b = thumbnail; onDispose { b?.recycle() } }
    fun cancel() { if (template != m.templatePath) template?.let(repository::discard); onCancel() }
    BackHandler(enabled = !busy && !saving) { if (captured != null) RuntimeStore.clearCapture() else cancel() }
    if (captured != null) {
        CropScreen(captured!!, type == "IMAGE", onCancel = { RuntimeStore.clearCapture() }, onAccept = { templateRegion, search ->
            scope.launch {
                try {
                    saving = true
                    val frame = captured ?: error("캡처 화면이 없습니다.")
                    val bitmap = frame.bitmap
                    if (type == "IMAGE") {
                        val box = templateRegion.pixels(bitmap.width, bitmap.height)
                        val crop = Bitmap.createBitmap(bitmap, box.left.toInt(), box.top.toInt(), box.width.toInt(), box.height.toInt())
                        try {
                            withContext(Dispatchers.Default) { Matchers.validateTemplate(crop) }
                            val saved = repository.saveTemplate(crop)
                            if (template != m.templatePath) template?.let(repository::discard)
                            template = saved
                        } finally { if (crop !== bitmap) crop.recycle() }
                        width = bitmap.width; height = bitmap.height; rotation = frame.rotation
                    }
                    left = search.left.toString(); top = search.top.toString(); right = search.right.toString(); bottom = search.bottom.toString()
                    RuntimeStore.clearCapture()
                } catch (e: Exception) { error = e.message }
                finally { saving = false }
            }
        }, busy = saving)
    } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("매크로 편집", style = MaterialTheme.typography.titleLarge)
        Field(name, { name = it }, "이름", false, !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == "TEXT", onClick = { type = "TEXT" }, label = { Text("문구 OCR") }, enabled = !busy && !saving)
            FilterChip(selected = type == "IMAGE", onClick = { type = "IMAGE" }, label = { Text("이미지") }, enabled = !busy && !saving)
        }
        if (type == "TEXT") {
            Field(query, { query = it }, "찾을 문구 (한 줄 기준)", false, !busy)
            Toggle("포함 일치 (해당 줄 중심 클릭)", contains, { contains = it }, !busy)
            Toggle("영어 대소문자 무시", ignoreCase, { ignoreCase = it }, !busy)
        } else {
            thumbnail?.let { Image(it.asImageBitmap(), "기준 이미지", Modifier.fillMaxWidth().heightIn(max = 150.dp)) }
            Text(if (template == null) "기준 이미지가 없습니다." else "기준 화면: ${width}×${height} · 방향 $rotation")
            Text("유사도 임계값 ${String.format(Locale.US, "%.2f", threshold)} (확률 아님)")
            Slider(value = threshold, onValueChange = { threshold = it }, valueRange = .5f.. .99f, steps = 48, enabled = !busy && !saving)
        }
        OutlinedButton(onClick = { onCapture("REGISTER", emptyList()) }, enabled = !busy && !saving) { Text(if (type == "IMAGE") "대상 앱 화면에서 이미지·검색 영역 등록" else "검색 범위 드래그 지정 (선택)") }
        val fullScreenSearch = SearchRegion(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()) == SearchRegion()
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (fullScreenSearch) "검색 범위: 전체 화면" else "검색 범위: 지정 영역", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { left = "0.0"; top = "0.0"; right = "1.0"; bottom = "1.0" }, enabled = !busy && !saving && !fullScreenSearch) {
                Text("전체 화면으로 변경")
            }
        }
        Text("기본은 전체 화면입니다. 같은 대상이 여러 곳에 보이거나 인식이 느릴 때만 캡처 화면에서 드래그해 범위를 좁히세요.", style = MaterialTheme.typography.bodySmall)
        Field(interval, { interval = it }, "클릭 간격 (초 · 최소 1.0)", true, !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(pre, { pre = it }, "클릭 전 대기 (초)", true, !busy, Modifier.weight(1f)); Field(post, { post = it }, "클릭 후 대기 (초)", true, !busy, Modifier.weight(1f))
        }
        Text("0.1초 단위 · 조건이 계속 보이면 반복 클릭합니다. 화면 크기·배율이 바뀌면 이미지를 다시 등록하세요.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && !saving, onClick = { focusManager.clearFocus(); keyboard?.hide(); scope.launch {
                saving = true
                try {
                    val model = m.copy(name = name.trim(), type = RecognitionType.valueOf(type), query = query,
                        matchMode = if (contains) MatchMode.CONTAINS else MatchMode.EXACT, ignoreCase = ignoreCase,
                        intervalMs = secondsToMillis(interval, 1000), preDelayMs = secondsToMillis(pre), postDelayMs = secondsToMillis(post),
                        region = SearchRegion(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
                        templatePath = if (type == "IMAGE") template else null, threshold = (kotlin.math.round(threshold * 100) / 100).toDouble(),
                        referenceWidth = width, referenceHeight = height, referenceRotation = rotation)
                    if (model.type == RecognitionType.IMAGE) {
                        val image = repository.bitmap(template ?: error("기준 이미지를 등록하세요."))
                        try { val box = model.region.pixels(width, height); require(image.width <= box.width && image.height <= box.height) { "기준 이미지가 검색 영역보다 큽니다." } }
                        finally { image.recycle() }
                    }
                    repository.save(model)
                    if (type == "TEXT" && template != m.templatePath) template?.let(repository::discard)
                    RuntimeStore.clearCapture(); onSave()
                } catch (e: Exception) { error = e.message ?: "저장 실패" }
                finally { saving = false }
            } }) { Text("저장") }
            OutlinedButton(onClick = { cancel() }, enabled = !busy && !saving) { Text("취소") }
        }
        Spacer(Modifier.height(32.dp))
    }
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text("입력 확인") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { error = null }) { Text("확인") } }) }
}
@Composable private fun Field(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier = modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable private fun Toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(text, Modifier.weight(1f)); Switch(checked, onChange, enabled = enabled) }
}
