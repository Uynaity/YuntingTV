package cn.radio.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/**
 * 首页右上角的设置入口按钮：圆形容器内绘制齿轮图标。
 * 与卡片/播放键一致：聚焦时放大并白色描边，遥控 D-pad 可达。
 */
@Composable
fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) Color.White else MaterialTheme.colorScheme.surfaceVariant
    val iconColor = if (focused) Color.Black else Color.White

    Box(
        modifier = modifier
            .size(44.dp)
            .scale(if (focused) 1.1f else 1f)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(container, CircleShape)
            .border(2.dp, if (focused) Color.White else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val toothW = r * 0.34f
            val toothH = r * 0.5f
            // 8 个轮齿：绕中心每 45° 旋转后在顶部画一颗
            for (i in 0 until 8) {
                rotate(degrees = i * 45f, pivot = center) {
                    drawRect(
                        color = iconColor,
                        topLeft = Offset(center.x - toothW / 2f, center.y - r),
                        size = Size(toothW, toothH),
                    )
                }
            }
            // 齿轮主体与中心镂空
            drawCircle(iconColor, radius = r * 0.7f, center = center)
            drawCircle(container, radius = r * 0.32f, center = center)
        }
    }
}
