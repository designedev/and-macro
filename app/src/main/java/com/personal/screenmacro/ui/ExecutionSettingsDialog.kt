package com.personal.screenmacro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.personal.screenmacro.core.*
import kotlin.math.roundToInt

@Composable fun ExecutionSettingsDialog(current: Long, locked: Boolean, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(current) { mutableLongStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("실행 설정") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("인식 결과 유효 시간")
            Text("${resultAgeSeconds(draft)}초", modifier = Modifier.testTag("result-age-value"))
            Slider(value = draft.toFloat(), onValueChange = { draft = (it / 100f).roundToInt() * 100L },
                valueRange = 1000f..3000f, steps = 19, enabled = !locked, modifier = Modifier.testTag("result-age-slider"))
            Text("1.0~3.0초 · 0.1초 단위 · 모든 매크로에 적용")
            Text("캡처 후 이 시간이 지나면 클릭을 보류합니다. 시간을 늘리면 대상이 움직였을 때 이전 위치를 클릭할 수 있습니다. 실행 중에는 변경할 수 없습니다.")
        }
    }, confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = !locked) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
