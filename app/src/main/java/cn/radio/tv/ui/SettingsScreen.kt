package cn.radio.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.prefs.UserPreferences
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** 金色高亮（与全局收藏/选中风格一致）。 */
private val Accent = Color(0xFFFFC107)

/**
 * 设置页面（整屏覆盖）。适配 TV 遥控：D-pad 上下/左右移动焦点，OK 键确认，返回键关闭。
 *
 * @param provinces 省份列表（含 code=0 的「国家」），城市选择项来源。
 * @param homeCityCode 当前所在城市；[UserPreferences.DEFAULT_PROVINCE_CODE] 表示国家。
 * @param autoPlayLast 当前「启动自动播放上次电台」开关状态。
 */
@Composable
fun SettingsScreen(
    provinces: List<Province>,
    homeCityCode: Long,
    autoPlayLast: Boolean,
    onSelectCity: (Long) -> Unit,
    onToggleAutoPlay: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var cityMenuExpanded by remember { mutableStateOf(false) }

    // 返回键：下拉菜单展开时先收起菜单，否则关闭设置页
    BackHandler(enabled = !cityMenuExpanded, onBack = onClose)

    val firstFocusRequester = remember { FocusRequester() }
    // 打开后将焦点落到第一项（自动播放开关），确保遥控可立即操作
    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // TV 安全区留白
            .padding(horizontal = 48.dp, vertical = 36.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 设置项一：启动自动播放上次电台
        ToggleSettingRow(
            title = "启动时自动播放上次电台",
            subtitle = "关闭后仍会记忆上次收听的电台，仅启动时不自动播放",
            checked = autoPlayLast,
            onToggle = { onToggleAutoPlay(!autoPlayLast) },
            focusRequester = firstFocusRequester,
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 设置项二：所在城市（整行样式与设置项一一致，右侧展示当前城市）
        CityDropdown(
            title = "所在城市",
            subtitle = "设定后城市筛选栏将其置顶，且每次启动默认展示该城市",
            provinces = provinces,
            homeCityCode = homeCityCode,
            expanded = cityMenuExpanded,
            onExpandedChange = { cityMenuExpanded = it },
            onSelect = { onSelectCity(it) },
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 设置项三：清除图片缓存（Coil 默认磁盘缓存，清后下次展示重新拉取）
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        ActionSettingRow(
            title = "清除图片缓存",
            subtitle = "删除本地缓存的电台图片，下次展示时重新从网络获取",
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }
                    context.imageLoader.memoryCache?.clear()
                    Toast.makeText(context, "图片缓存已清除", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

/**
 * 城市下拉选择：折叠时为一枚锚点按钮（显示当前城市），OK 键展开为可滚动列表。
 * 展开后焦点落在当前选中项；选择或返回键收起并把焦点送回锚点。
 */
@Composable
private fun CityDropdown(
    title: String,
    subtitle: String,
    provinces: List<Province>,
    homeCityCode: Long,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
) {
    val currentName = provinces.firstOrNull { it.provinceCode == homeCityCode }?.provinceName ?: "国家"
    val selectedIndex = remember(provinces, homeCityCode) {
        provinces.indexOfFirst { it.provinceCode == homeCityCode }.coerceAtLeast(0)
    }

    val anchorFocusRequester = remember { FocusRequester() }
    val selectedItemFocusRequester = remember { FocusRequester() }

    // 展开时把焦点送到当前选中项，便于遥控直接上下浏览
    LaunchedEffect(expanded) {
        if (expanded) runCatching { selectedItemFocusRequester.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 锚点行（样式与 ToggleSettingRow 一致，右侧为当前城市名）
        DropdownAnchor(
            title = title,
            subtitle = subtitle,
            cityName = currentName,
            expanded = expanded,
            focusRequester = anchorFocusRequester,
            onClick = { onExpandedChange(!expanded) },
        )

        // 菜单展开时：返回键收起并把焦点送回锚点
        if (expanded) {
            BackHandler(enabled = true) {
                onExpandedChange(false)
                runCatching { anchorFocusRequester.requestFocus() }
            }
        }

        // 展开/收起动画：垂直展开 + 淡入淡出，与筛选栏过渡风格一致
        AnimatedVisibility(visible = expanded, modifier = Modifier.align(Alignment.End)) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(0.3f)
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .focusGroup(),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(provinces, key = { it.provinceCode }) { province ->
                    val selected = province.provinceCode == homeCityCode
                    CityMenuItem(
                        label = province.provinceName,
                        selected = selected,
                        focusRequester = if (selected) selectedItemFocusRequester else null,
                        onClick = {
                            onSelect(province.provinceCode)
                            onExpandedChange(false)
                            runCatching { anchorFocusRequester.requestFocus() }
                        },
                    )
                }
            }
        }
    }
}

/** 下拉锚点行：左侧标题/副标题（同 [ToggleSettingRow]），右侧显示当前城市名 + 展开箭头。 */
@Composable
private fun DropdownAnchor(
    title: String,
    subtitle: String,
    cityName: String,
    expanded: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color.White else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = cityName,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = if (expanded) "▴" else "▾",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 下拉列表中的单个城市行：选中金色高亮，聚焦白色描边。 */
@Composable
private fun CityMenuItem(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val containerColor = when {
        focused -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val borderColor = if (focused) Color.White else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .background(containerColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Accent else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(text = "✓", style = MaterialTheme.typography.bodyLarge, color = Accent)
        }
    }
}

/** 带开关的设置行：整行可聚焦，OK 键切换。 */
@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color.White else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        ToggleSwitch(checked = checked)
    }
}

/** 可点击的动作设置项（无开关），样式与 [ToggleSettingRow] 一致，自管理焦点。 */
@Composable
private fun ActionSettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color.White else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 纯展示用开关视觉（状态由外部驱动）：开=金色靠右，关=灰色靠左。 */
@Composable
private fun ToggleSwitch(checked: Boolean) {
    val trackColor = if (checked) Accent else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
