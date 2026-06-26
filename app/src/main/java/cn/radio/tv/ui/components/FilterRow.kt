package cn.radio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** 单个筛选项数据：key 用于业务匹配，label 用于展示。 */
data class FilterItem(val key: String, val label: String)

/** 一行横向可滚动的筛选 chips（城市 / 类型共用）。 */
@Composable
fun FilterRow(
    items: List<FilterItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedItemFocusRequester: FocusRequester? = null,
) {
    // 让选中项初始就处于可见区，确保其 chip 已组合、focusRequester 可用
    val selectedIndex = remember(items, selectedKey) {
        items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
        items(items, key = { it.key }) { item ->
            val selected = item.key == selectedKey
            FilterChip(
                text = item.label,
                selected = selected,
                onClick = { onSelect(item.key) },
                focusRequester = if (selected) selectedItemFocusRequester else null,
            )
        }
    }
}

/** 金色（收藏星标）。 */
private val GoldStar = Color(0xFFFFC107)

/**
 * 「⭐ 收藏」开关 chip：作为独立视图入口。激活时金色高亮。
 */
@Composable
fun FavoriteFilterChip(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }

    val containerColor = if (active) GoldStar else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (active) Color.Black else Color.White
    val borderColor = if (focused) Color.White else Color.Transparent

    Text(
        text = "★ 收藏",
        style = MaterialTheme.typography.labelLarge,
        color = contentColor,
        modifier = modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .border(2.dp, borderColor, RoundedCornerShape(50))
            .background(containerColor, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }

    val containerColor = if (selected) Color.White else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) Color.Black else Color.White
    val borderColor = if (focused) Color.White else Color.Transparent

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = contentColor,
        modifier = Modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .border(2.dp, borderColor, RoundedCornerShape(50))
            .background(containerColor, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/**
 * 折叠态筛选摘要：以一个紧凑按钮展示「城市 ｜ 类型」。
 * 获得焦点或点击时触发 [onActivate]（用于展开完整筛选栏）。
 */
@Composable
fun CompactFilter(
    cityName: String,
    typeName: String,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    favoritesActive: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color.White else Color.Transparent

    Row(
        modifier = modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onActivate()
            }
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onActivate,
            )
            .border(2.dp, borderColor, RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favoritesActive) {
            Text(
                text = "★ 收藏",
                style = MaterialTheme.typography.labelLarge,
                color = GoldStar,
            )
        } else {
            Text(
                text = "$cityName  ｜  $typeName",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }
}
