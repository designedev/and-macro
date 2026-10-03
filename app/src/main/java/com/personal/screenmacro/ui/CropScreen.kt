package com.personal.screenmacro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.personal.screenmacro.CapturedEditorFrame
import com.personal.screenmacro.core.*
import kotlin.math.roundToInt

@Composable fun FrameCanvas(bitmap: Bitmap, box: Box?, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier) {
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val origin = Offset((size.width - bitmap.width * scale) / 2, (size.height - bitmap.height * scale) / 2)
        drawImage(image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt()))
        box?.let {
            drawRect(Color(0xFF00E676), origin + Offset(it.left * scale, it.top * scale), Size(it.width * scale, it.height * scale), style = Stroke(3.dp.toPx()))
            drawCircle(Color.Red, 5.dp.toPx(), origin + Offset(it.centerX * scale, it.centerY * scale))
        }
    }
}
@Composable fun CropScreen(frame: CapturedEditorFrame, imageCondition: Boolean, onCancel: () -> Unit,
    onAccept: (SearchRegion, SearchRegion) -> Unit, busy: Boolean) {
    var selectingSearch by remember(frame.token) { mutableStateOf(!imageCondition) }
    var template by remember(frame.token) { mutableStateOf(SearchRegion(.3f, .3f, .6f, .6f)) }
    var search by remember(frame.token) { mutableStateOf(SearchRegion()) }
    var start by remember { mutableStateOf(Offset.Zero) }
    var error by remember { mutableStateOf<String?>(null) }
    val bitmap = frame.bitmap
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("영역 등록", style = MaterialTheme.typography.titleLarge)
        Text("화면을 드래그해 ${if (selectingSearch) "검색 영역" else "기준 이미지"}을 지정하세요.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (imageCondition) FilterChip(selected = !selectingSearch, onClick = { selectingSearch = false }, label = { Text("기준 이미지") }, enabled = !busy)
            FilterChip(selected = selectingSearch, onClick = { selectingSearch = true }, label = { Text("검색 영역") }, enabled = !busy)
            TextButton(onClick = { search = SearchRegion() }, enabled = !busy) { Text("전체 화면 검색") }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).pointerInput(frame.token, selectingSearch, busy) {
            if (busy) return@pointerInput
            val scale = minOf(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
            val origin = Offset((size.width - bitmap.width * scale) / 2, (size.height - bitmap.height * scale) / 2)
            fun normalized(p: Offset) = Offset(((p.x - origin.x) / (bitmap.width * scale)).coerceIn(0f, 1f), ((p.y - origin.y) / (bitmap.height * scale)).coerceIn(0f, 1f))
            detectDragGestures(onDragStart = { start = normalized(it) }) { change, _ ->
                change.consume()
                val end = normalized(change.position)
                val region = SearchRegion(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
                if (region.valid()) { if (selectingSearch) search = region else template = region }
            }
        }) {
            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
            val origin = Offset((size.width - bitmap.width * scale) / 2, (size.height - bitmap.height * scale) / 2)
            drawImage(image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt()))
            fun draw(region: SearchRegion, color: Color) {
                drawRect(color, origin + Offset(region.left * bitmap.width * scale, region.top * bitmap.height * scale),
                    Size((region.right - region.left) * bitmap.width * scale, (region.bottom - region.top) * bitmap.height * scale), style = Stroke(3.dp.toPx()))
            }
            draw(search, Color(0xFF00E676)); if (imageCondition) draw(template, Color(0xFFFFB300))
        }
        Text("초록: 검색 영역${if (imageCondition) " · 주황: 기준 이미지" else ""}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!search.valid() || (imageCondition && !template.valid())) { error = "유효한 영역을 지정하세요."; return@Button }
                if (imageCondition && (template.left < search.left || template.top < search.top || template.right > search.right || template.bottom > search.bottom)) { error = "기준 이미지를 포함하는 검색 영역을 지정하세요."; return@Button }
                onAccept(template, search)
            }, enabled = !busy) { Text(if (busy) "저장 중…" else "영역 적용") }
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("취소") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
    }
}
