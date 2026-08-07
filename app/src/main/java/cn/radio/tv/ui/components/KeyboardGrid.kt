package cn.radio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.ui.theme.GoldStar

/**
 * 一个虚拟键。[label] 是键面文字，[onClick] 是按下要做的事 —— 键盘本身不理解语义，
 * 「这个键是退格还是字母」由调用方在闭包里决定。
 *
 * [wide] 给 ABC/123、⌫ 这类多字符标签用：只影响字号，不影响占位（网格是定宽的）。
 */
data class KeyboardKey(
    val label: String,
    val wide: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * D-pad 友好的定宽网格键盘。TV 遥控器上逐字输入的通用解法，
 * [SearchPanel]（搜电台）与激活码输入共用这一份。
 *
 * 用 Row/Column 手搭而非 LazyVerticalGrid：键数固定且全部可见，
 * 懒加载只会让方向键焦点在未组合的行上丢失。
 *
 * [firstKey] 传入时挂到第一个键上，供调用方进面板即落焦 ——
 * 否则用户得先按一下方向键才能开始打字。
 */
@Composable
fun KeyboardGrid(
    keys: List<KeyboardKey>,
    columns: Int,
    modifier: Modifier = Modifier,
    firstKey: FocusRequester? = null,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        keys.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEachIndexed { colIndex, key ->
                    KeyButton(
                        key = key,
                        modifier = Modifier.weight(1f),
                        focusRequester = firstKey.takeIf { rowIndex == 0 && colIndex == 0 },
                    )
                }
                // 末行不足一行时补空位，让剩余键保持与上方各行同宽
                // （否则 weight 会把它们拉大）。
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** 单个方键。焦点样式复用 [focusableChrome]，与频道卡 / chip 保持一致。 */
@Composable
private fun KeyButton(
    key: KeyboardKey,
    modifier: Modifier,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .focusableChrome(
                shape = shape,
                container = if (focused) GoldStar else MaterialTheme.colorScheme.surfaceVariant,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = key.onClick,
                focusRequester = focusRequester,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = if (focused) Color.Black else Color.White,
            textAlign = TextAlign.Center,
            // 单字符键用大字号提高远距离可读性；多字符标签略小一级避免窄屏被截断。
            style = if (key.wide) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
