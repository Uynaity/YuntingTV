package cn.radio.tv.ui.components

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.ui.theme.GoldStar

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

    Text(
        text = "★ 收藏",
        style = MaterialTheme.typography.labelLarge,
        color = if (active) Color.Black else Color.White,
        modifier = modifier
            .focusableChrome(
                shape = RoundedCornerShape(50),
                container = if (active) GoldStar else MaterialTheme.colorScheme.surfaceVariant,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
                focusRequester = focusRequester,
            )
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

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .focusableChrome(
                shape = RoundedCornerShape(50),
                container = if (selected) GoldStar else MaterialTheme.colorScheme.surfaceVariant,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
                focusRequester = focusRequester,
            )
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
    // 获焦回调：默认与点击同义（获焦即展开）。调用方可覆盖以拦截收起瞬间的焦点回弹，
    onFocused: () -> Unit = onActivate,
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .focusableChrome(
                shape = RoundedCornerShape(50),
                // 收藏激活时整颗按钮高亮为金色，与选中筛选 chip 一致（金底黑字）。
                container = if (favoritesActive) GoldStar else MaterialTheme.colorScheme.surfaceVariant,
                focused = focused,
                onFocusChanged = {
                    focused = it
                    if (it) onFocused()
                },
                onClick = onActivate,
                focusRequester = focusRequester,
            )
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favoritesActive) {
            Text(
                text = "★ 收藏",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black,
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
