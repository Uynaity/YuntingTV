package cn.radio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.ui.theme.GoldStar

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
            keys = if (digitMode) DIGIT_KEYS else LETTER_KEYS,
            digitMode = digitMode,
            firstKey = firstKey,
            onAppend = onAppend,
            onBackspace = onBackspace,
            onToggleMode = { digitMode = !digitMode },
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

/**
 * 定宽网格键盘。用 Row/Column 手搭而非 LazyVerticalGrid：键数固定且全部可见，
 * 懒加载只会让方向键焦点在未组合的行上丢失。
 */
@Composable
private fun KeyboardGrid(
    keys: List<Key>,
    digitMode: Boolean,
    firstKey: FocusRequester,
    onAppend: (Char) -> Unit,
    onBackspace: () -> Unit,
    onToggleMode: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(KEYBOARD_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    KeyButton(
                        key = key,
                        digitMode = digitMode,
                        modifier = Modifier.weight(1f),
                        focusRequester = if (key === keys.first()) firstKey else null,
                        onClick = {
                            when (key) {
                                is Key.Char -> onAppend(key.c)
                                Key.Backspace -> onBackspace()
                                Key.Mode -> onToggleMode()
                            }
                        },
                    )
                }
                // 末行不足一行时补空位，让剩余键保持与上方各行同宽(否则 weight 会把它们拉大)。
                repeat(KEYBOARD_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** 单个方键。焦点样式复用 [focusableChrome]，与频道卡 / chip 保持一致。 */
@Composable
private fun KeyButton(
    key: Key,
    digitMode: Boolean,
    modifier: Modifier,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .focusableChrome(
                shape = shape,
                container = if (focused) GoldStar
                else MaterialTheme.colorScheme.surfaceVariant,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
                focusRequester = focusRequester,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (key) {
                is Key.Char -> key.c.toString()
                Key.Backspace -> "⌫"
                Key.Mode -> if (digitMode) "ABC" else "123"
            },
            color = if (focused) Color.Black else Color.White,
            textAlign = TextAlign.Center,
            style = when (key) {
                // 5 列布局下单键空间充足，字母、数字和删除符号都用大字号提高远距离可读性。
                is Key.Char, Key.Backspace -> MaterialTheme.typography.titleLarge
                // ABC / 123 是三字符标签，略小一级避免窄屏下被截断，但仍明显大于原 labelMedium。
                Key.Mode -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
