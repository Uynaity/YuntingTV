package cn.radio.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import cn.radio.tv.ui.theme.GoldStar

/**
 * 搜索入口按钮：圆形容器内画放大镜。规格与 [SettingsButton] 一致（44dp 圆、聚焦放大 1.1 倍），
 * 两者同属顶栏图标按钮。[active] 为真（搜索态）时容器转金色，与收藏 chip 同一套高亮语言。
 */
@Composable
fun SearchButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused -> Color.White
        active -> GoldStar
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val iconColor = if (focused || active) Color.Black else Color.White

    Box(
        modifier = modifier
            .size(44.dp)
            .scale(if (focused) 1.1f else 1f)
            .focusableChrome(
                shape = CircleShape,
                container = container,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
                focusRequester = focusRequester,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round)
            // 镜片占左上，半径留出右下角画手柄。
            val r = size.minDimension * 0.32f
            val center = Offset(r + stroke.width / 2f, r + stroke.width / 2f)
            drawCircle(iconColor, radius = r, center = center, style = stroke)
            // 手柄沿 45° 从镜片边缘延伸到右下角。
            val from = Offset(center.x + r * 0.72f, center.y + r * 0.72f)
            drawLine(
                color = iconColor,
                start = from,
                end = Offset(size.width - stroke.width / 2f, size.height - stroke.width / 2f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}
