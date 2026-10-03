package com.personal.screenmacro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.personal.screenmacro.R

private val Navy = Color(0xFF111B2E)
private val Mint = Color(0xFF64DAB6)

@Composable internal fun PermissionStrip(accessibility: Boolean, notifications: Boolean, brightness: Boolean,
    locked: Boolean, helpOpen: Boolean, onAccessibility: () -> Unit, onNotification: () -> Unit,
    onBrightness: () -> Unit, onHelp: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        PermissionIcon("접근성", "permission-accessibility", R.drawable.ic_accessibility, accessibility, !locked, onAccessibility)
        PermissionIcon("알림", "permission-notification", R.drawable.ic_notification, notifications, !notifications, onNotification)
        PermissionIcon("밝기", "permission-brightness", R.drawable.ic_brightness, brightness, !locked, onBrightness)
        PermissionIcon("도움말", "permission-help", R.drawable.ic_help, null, true, onHelp, helpOpen)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PermissionIcon(title: String, tag: String, drawable: Int, granted: Boolean?,
    enabled: Boolean, onClick: () -> Unit, helpOpen: Boolean = false) {
    val state = when (granted) { true -> "허용됨"; false -> "허용되지 않음"; null -> if (helpOpen) "펼쳐짐" else "접힘" }
    val tint = when { granted == true || helpOpen -> Mint; granted == false -> Color(0xFF64748B); else -> Color(0xFFE2E8F0) }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(if (granted == null) title else "$title · $state") } },
        state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag).semantics { stateDescription = state }) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(drawable), contentDescription = title, tint = tint, modifier = Modifier.size(25.dp))
                if (granted == false) Canvas(Modifier.fillMaxSize()) {
                    val start = Offset(size.width * .16f, size.height * .84f)
                    val end = Offset(size.width * .84f, size.height * .16f)
                    drawLine(Navy, start, end, 5.dp.toPx(), StrokeCap.Round)
                    drawLine(tint, start, end, 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
