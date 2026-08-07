package cn.radio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** 横屏搜索键盘统一使用 5 列布局。 */
private const val KEYBOARD_COLUMNS = 5

/** 功能键的位置标记：与字符键同走一套网格布局，故用 sealed 区分而非在字符串里塞魔法值。 */
private sealed interface Key {
    data class Char(val c: kotlin.Char) : Key

    /** 切换字母 / 数字态。 */
    data object Mode : Key
    data object Backspace : Key
}

private val LETTER_KEYS: List<Key> =
    ('A'..'Z').map { Key.Char(it) } + listOf(Key.Mode, Key.Backspace)

private val DIGIT_KEYS: List<Key> =
    ('1'..'9').map { Key.Char(it) } + listOf(Key.Char('0'), Key.Mode, Key.Backspace)

/**
 * 横屏搜索面板：上方搜索栏，下方 [KEYBOARD_COLUMNS] 列方形键盘。占据原播放器左栏的位置。
 *
 * 输入立即回显、请求由 ViewModel 防抖，故这里不持有查询词，只把击键往上抛。
 * 退出由外层的返回键处理（键盘不设「取消」键，返回键是 TV 遥控上的自然退路）。
 */
@Composable
fun SearchPanel(
    query: String,
    isSearching: Boolean,
    resultCount: Int,
    onAppend: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var digitMode by remember { mutableStateOf(false) }
    // 进入面板时把焦点落到第一个键，用户不必先按一下方向键才能开始打字。
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    Column(
        modifier = modifier.padding(12.dp),
        // 搜索框与键盘拉开层次，避免首排按键视觉上贴住输入区域。
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SearchField(query = query, isSearching = isSearching, resultCount = resultCount)
        KeyboardGrid(
            keys = (if (digitMode) DIGIT_KEYS else LETTER_KEYS).map { key ->
                when (key) {
                    is Key.Char -> KeyboardKey(key.c.toString()) { onAppend(key.c) }
                    Key.Backspace -> KeyboardKey("⌫", onClick = onBackspace)
                    Key.Mode -> KeyboardKey(
                        label = if (digitMode) "ABC" else "123",
                        wide = true,
                    ) { digitMode = !digitMode }
                }
            },
            columns = KEYBOARD_COLUMNS,
            firstKey = firstKey,
        )
    }
}

/** 搜索栏：查询词 + 状态行。不可聚焦 —— 输入全靠下方键盘，聚焦它只会多挡一次方向键。 */
@Composable
private fun SearchField(query: String, isSearching: Boolean, resultCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = query.ifEmpty { "输入电台名 / 拼音首字母" },
            color = if (query.isEmpty()) Color.White.copy(alpha = 0.45f) else Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when {
                query.isBlank() -> "搜索当前地区与分类"
                isSearching -> "搜索中…"
                else -> "$resultCount 个结果"
            },
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}

