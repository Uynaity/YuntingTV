package cn.radio.tv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.source.RadioSourceType
import cn.radio.tv.ui.components.AboutDialog
import cn.radio.tv.ui.components.focusableChrome
import cn.radio.tv.ui.theme.GoldStar
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置页面（整屏覆盖）。适配 TV 遥控：D-pad 上下/左右移动焦点，OK 键确认，返回键关闭。
 *
 * @param selectedSource 当前电台来源；切换后列表与收藏均按新来源展示。
 * @param provinces 省份列表（含 code=0 的「全部」），城市选择项来源。
 * @param homeCityCode 当前所在城市；[UserPreferences.DEFAULT_PROVINCE_CODE] 表示全部。
 * @param autoPlayLast 当前「启动自动播放上次电台」开关状态。
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
fun SettingsScreen(
    selectedSource: RadioSourceType,
    provinces: List<Province>,
    homeCityCode: Long,
    autoPlayLast: Boolean,
    onSelectSource: (RadioSourceType) -> Unit,
    onSelectCity: (Long) -> Unit,
    onToggleAutoPlay: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onClose: () -> Unit,
) {
    var cityMenuExpanded by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    BackHandler(enabled = !cityMenuExpanded && !showAbout, onBack = onClose)

    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 36.dp),
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(28.dp))

            SourceSelectRow(
                title = "电台来源",
                subtitle = "切换后展示该来源的电台，来源共享收藏夹",
                selected = selectedSource,
                onSelect = onSelectSource,
                firstFocusRequester = firstFocusRequester,
            )

            Spacer(modifier = Modifier.height(28.dp))

            ToggleSettingRow(
                title = "启动时自动播放上次电台",
                subtitle = "关闭后启动时不自动播放上次播放的电台",
                checked = autoPlayLast,
                onToggle = { onToggleAutoPlay(!autoPlayLast) },
            )

            Spacer(modifier = Modifier.height(28.dp))

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

            Spacer(modifier = Modifier.height(28.dp))

            ActionSettingRow(
                title = "检查更新",
                subtitle = "获取并安装最新版本",
                onClick = onCheckUpdate,
            )

            Spacer(modifier = Modifier.height(28.dp))

            ActionSettingRow(
                title = "关于",
                subtitle = "版权声明、开源与下载渠道",
                onClick = { showAbout = true },
            )
        }

        if (cityMenuExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { cityMenuExpanded = false },
            )
        }

        if (showAbout) {
            AboutDialog(onDismiss = { showAbout = false })
        }
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
    val currentName =
        provinces.firstOrNull { it.provinceCode == homeCityCode }?.provinceName ?: "国家"
    val selectedIndex = remember(provinces, homeCityCode) {
        provinces.indexOfFirst { it.provinceCode == homeCityCode }.coerceAtLeast(0)
    }

    val anchorFocusRequester = remember { FocusRequester() }
    val selectedItemFocusRequester = remember { FocusRequester() }

    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }

    DropdownAnchor(
        title = title,
        subtitle = subtitle,
        cityName = currentName,
        expanded = expanded,
        focusRequester = anchorFocusRequester,
        onClick = { onExpandedChange(!expanded) },
        modifier = Modifier.onGloballyPositioned {
            val pos = it.positionInWindow()
            anchorBounds = IntRect(
                pos.x.roundToInt(),
                pos.y.roundToInt(),
                (pos.x + it.size.width).roundToInt(),
                (pos.y + it.size.height).roundToInt(),
            )
        },
    )

    val bounds = anchorBounds
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = expanded
    if ((transitionState.currentState || transitionState.targetState) && bounds != null) {
        val density = LocalDensity.current
        val gapPx = with(density) { 8.dp.roundToPx() }
        val menuWidth = with(density) { (bounds.width / 2).toDp() }
        val positionProvider = remember(bounds, gapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = IntOffset(
                    x = bounds.right - popupContentSize.width,
                    y = bounds.bottom + gapPx,
                )
            }
        }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = {
                onExpandedChange(false)
                runCatching { anchorFocusRequester.requestFocus() }
            },
            properties = PopupProperties(focusable = true),
        ) {
            LaunchedEffect(Unit) { runCatching { selectedItemFocusRequester.requestFocus() } }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
            AnimatedVisibility(
                visibleState = transitionState,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(menuWidth)
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
}

/** 下拉锚点行：复用 [SettingRow] 容器，右侧显示当前城市名 + 展开箭头。 */
@Composable
private fun DropdownAnchor(
    title: String,
    subtitle: String,
    cityName: String,
    expanded: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
    ) {
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

/**
 * 设置行通用容器：整行可聚焦（聚焦白描边 + surfaceVariant 底），OK 键触发 [onClick]，
 * 左侧标题/副标题；可选右侧 [trailing]（开关 / 当前值 / 箭头等，为空则纯动作行）。
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color.White else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
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
        if (trailing != null) {
            Spacer(modifier = Modifier.size(16.dp))
            trailing()
        }
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
            color = if (selected) GoldStar else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(text = "✓", style = MaterialTheme.typography.bodyLarge, color = GoldStar)
        }
    }
}

/**
 * 电台来源选择行：左侧标题/副标题，右侧两枚来源 pill（选中金色高亮，聚焦白色描边）。
 * 第一枚 pill 持有 [firstFocusRequester]，打开设置页即落焦于此。
 */
@Composable
private fun SourceSelectRow(
    title: String,
    subtitle: String,
    selected: RadioSourceType,
    onSelect: (RadioSourceType) -> Unit,
    firstFocusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioSourceType.entries.forEachIndexed { index, source ->
                SourcePill(
                    label = source.displayName,
                    selected = source == selected,
                    onClick = { onSelect(source) },
                    focusRequester = if (index == 0) firstFocusRequester else null,
                )
            }
        }
    }
}

/** 单枚来源 pill：选中金色填充黑字，未选中灰底白字；聚焦白色描边。 */
@Composable
private fun SourcePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }

    Text(
        text = label,
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

/** 带开关的设置行：复用 [SettingRow]，右侧为开关。 */
@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onToggle,
        focusRequester = focusRequester,
    ) {
        ToggleSwitch(checked = checked)
    }
}

/** 可点击的动作设置项（无开关）：即无 trailing 的 [SettingRow]。 */
@Composable
private fun ActionSettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    SettingRow(title = title, subtitle = subtitle, onClick = onClick)
}

/** 纯展示用开关视觉（状态由外部驱动）：开=金色靠右，关=灰色靠左。 */
@Composable
private fun ToggleSwitch(checked: Boolean) {
    val trackColor = if (checked) GoldStar else MaterialTheme.colorScheme.surfaceVariant
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
