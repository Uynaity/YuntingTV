package cn.radio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * chip / 圆形按钮通用的可聚焦交互修饰：可选 [focusRequester] + 焦点回写 +
 * 裁剪为 [shape] + 无涟漪点击 + 聚焦时白色描边 + [container] 背景填充。
 *
 * 调用方持有 [focused] 状态（同时用于内容着色 / 缩放），故由 [onFocusChanged] 回写，
 * 不在此内部 remember。尺寸 / 内边距 / 缩放仍由调用方按需在前后追加。
 */
fun Modifier.focusableChrome(
    shape: Shape,
    container: Color,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    this
        .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .clip(shape)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
        .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
        .background(container, shape)
}
