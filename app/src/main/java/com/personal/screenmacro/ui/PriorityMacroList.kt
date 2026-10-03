package com.personal.screenmacro.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.personal.screenmacro.core.Macro
import com.personal.screenmacro.core.RecognitionType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun PriorityMacroList(
    macros: List<Macro>, busy: Boolean, connected: Boolean, modifier: Modifier = Modifier,
    onSelect: (Macro, Boolean) -> Unit, onReorder: suspend (List<String>) -> Unit,
    onInteraction: (Boolean) -> Unit, onError: (String) -> Unit,
    onStart: (Macro) -> Unit, onTest: (Macro) -> Unit, onEdit: (Macro) -> Unit, onDelete: (Macro) -> Unit
) {
    var ordered by remember { mutableStateOf(macros) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var center by remember { mutableFloatStateOf(0f) }
    var direction by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    val latestMacros by rememberUpdatedState(macros)
    val saveOrder by rememberUpdatedState(onReorder)
    val interaction by rememberUpdatedState(onInteraction)
    val error by rememberUpdatedState(onError)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val scrollStep = with(LocalDensity.current) { 18.dp.toPx() }
    LaunchedEffect(macros) { if (draggingId == null && !saving) ordered = macros }
    DisposableEffect(Unit) { onDispose { interaction(false) } }

    fun moveDragged() {
        val id = draggingId ?: return
        val from = ordered.indexOfFirst { it.id == id }
        val active = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        // Wait for the layout to reflect the previous swap before considering another.
        if (from < 0 || active.index != from) return
        val crossed = list.layoutInfo.visibleItemsInfo.filter { item ->
            val index = ordered.indexOfFirst { it.id == item.key }
            item.key != id && if (direction > 0) index > from && center > item.offset + item.size / 2f
            else index in 0 until from && center < item.offset + item.size / 2f
        }
        val destination = if (direction > 0) crossed.maxByOrNull { it.index } else crossed.minByOrNull { it.index }
        val to = destination?.let { item -> ordered.indexOfFirst { it.id == item.key } } ?: return
        ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun persist() {
        draggingId = null
        if (ordered.map { it.id } == latestMacros.map { it.id }) { interaction(false); return }
        saving = true
        val ids = ordered.map { it.id }
        scope.launch {
            try { saveOrder(ids) }
            catch (e: Exception) { ordered = latestMacros; error(e.message ?: "순서 저장 실패") }
            finally { saving = false; interaction(false) }
        }
    }
    LaunchedEffect(draggingId) {
        while (draggingId != null) {
            val layout = list.layoutInfo
            val delta = when {
                center < layout.viewportStartOffset + edge -> -scrollStep
                center > layout.viewportEndOffset - edge -> scrollStep
                else -> 0f
            }
            if (delta != 0f) { direction = delta; list.scrollBy(delta); moveDragged() }
            delay(16)
        }
    }
    LazyColumn(modifier.testTag("priority-list"), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(ordered, key = { _, m -> m.id }) { index, macro ->
            val dragging = draggingId == macro.id
            val controls = !busy && !saving && draggingId == null
            Card(Modifier.fillMaxWidth().testTag("macro-${macro.id}").zIndex(if (dragging) 1f else 0f)
                .graphicsLayer {
                    val item = if (dragging) list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == macro.id } else null
                    translationY = if (item != null) center - (item.offset + item.size / 2f) else 0f
                },
                elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 0.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(macro.selected, { onSelect(macro, it) }, enabled = controls,
                            modifier = Modifier.testTag("select-${macro.id}").semantics { contentDescription = "실행 선택: ${macro.name}" })
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}순위 · ${macro.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (macro.type == RecognitionType.TEXT) "문구: ${macro.query}" else "이미지 조건", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.size(48.dp).testTag("drag-${macro.id}")
                            .semantics {
                                contentDescription = "우선순위 드래그: ${macro.name}"
                                customActions = listOf(
                                    CustomAccessibilityAction("우선순위 올리기") {
                                        if (!controls || index == 0) false else {
                                            interaction(true); ordered = ordered.toMutableList().apply { add(index - 1, removeAt(index)) }; persist(); true
                                        }
                                    },
                                    CustomAccessibilityAction("우선순위 내리기") {
                                        if (!controls || index == ordered.lastIndex) false else {
                                            interaction(true); ordered = ordered.toMutableList().apply { add(index + 1, removeAt(index)) }; persist(); true
                                        }
                                    }
                                )
                            }
                            .pointerInput(macro.id, busy, saving) {
                                if (!busy && !saving) detectDragGestures(
                                    onDragStart = {
                                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == macro.id }
                                        if (item != null) { draggingId = macro.id; center = item.offset + item.size / 2f; direction = 0f; interaction(true) }
                                    },
                                    onDragCancel = { draggingId = null; ordered = latestMacros; interaction(false) },
                                    onDragEnd = { persist() },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (draggingId == macro.id) { center += amount.y; direction = amount.y; moveDragged() }
                                    }
                                )
                            }, contentAlignment = Alignment.Center) { Text("☰", style = MaterialTheme.typography.headlineSmall) }
                    }
                    Text("${macro.intervalMs / 1000.0}초 간격 · 전 ${macro.preDelayMs / 1000.0}초 / 후 ${macro.postDelayMs / 1000.0}초", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onStart(macro) }, enabled = controls && connected) { Text("시작") }
                        TextButton(onClick = { onTest(macro) }, enabled = controls && connected) { Text("인식 테스트") }
                        TextButton(onClick = { onEdit(macro) }, enabled = controls) { Text("편집") }
                        TextButton(onClick = { onDelete(macro) }, enabled = controls) { Text("삭제") }
                    }
                }
            }
        }
    }
}
