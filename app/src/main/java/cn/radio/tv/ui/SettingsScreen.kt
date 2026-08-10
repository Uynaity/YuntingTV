package cn.radio.tv.ui

import android.content.res.Configuration
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.core.net.toUri
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.data.activation.ActivationState
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.source.RadioSourceType
import cn.radio.tv.ui.components.AboutDialog
import cn.radio.tv.ui.components.ActivationCodeDialog
import cn.radio.tv.ui.components.ActivationManageDialog
import cn.radio.tv.ui.components.PURCHASE_URL
import cn.radio.tv.ui.components.PurchaseDialog
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
    autoFullscreen: Boolean,
    tuneInProxy: Boolean,
    activation: ActivationState,
    isRedeemingCode: Boolean,
    onSelectSource: (RadioSourceType) -> Unit,
    onSelectCity: (Long) -> Unit,
    onToggleAutoPlay: (Boolean) -> Unit,
    onToggleAutoFullscreen: (Boolean) -> Unit,
    onToggleTuneInProxy: (Boolean) -> Unit,
    onRedeemActivationCode: (String, (String) -> Unit) -> Unit,
    onUnbindActivation: ((String) -> Unit) -> Unit,
    onRefreshActivation: () -> Unit,
    onCheckUpdate: () -> Unit,
    onClose: () -> Unit,
) {
    var cityMenuExpanded by remember { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showActivationDialog by remember { mutableStateOf(false) }
    var showManageDialog by remember { mutableStateOf(false) }
    var showPurchaseDialog by remember { mutableStateOf(false) }

    // 全屏播放仅横屏可用，竖屏下禁用「无操作自动进入全屏」开关。
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    // 打开设置页时查一次激活状态：它只在兑换、到期、被吊销时变化，不值得轮询。
    LaunchedEffect(Unit) { onRefreshActivation() }

    BackHandler(
        enabled = !cityMenuExpanded && !sourceMenuExpanded && !showAbout &&
                !showActivationDialog && !showManageDialog && !showPurchaseDialog,
        onBack = onClose,
    )

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
                // 手机竖屏外层已避让系统栏，不再叠加 TV 规格的 36dp 上下留白。
                .padding(
                    start = 48.dp,
                    top = if (isPortrait) 16.dp else 36.dp,
                    end = 48.dp,
                    bottom = if (isPortrait) 16.dp else 36.dp,
                ),
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(16.dp))

            SourceDropdown(
                title = "电台来源",
                subtitle = "切换后展示该来源的电台，来源共享收藏夹",
                selected = selectedSource,
                expanded = sourceMenuExpanded,
                onExpandedChange = { sourceMenuExpanded = it },
                onSelect = onSelectSource,
                anchorFocusRequester = firstFocusRequester,
            )

            // 仅 TuneIn 走服务端透传，别的来源本就是上游直链，这项对它们没有意义，
            // 故紧随来源项之下、只在选中 TuneIn 时出现。
            if (selectedSource == RadioSourceType.TUNEIN) {
                val active = activation as? ActivationState.Active

                Spacer(modifier = Modifier.height(16.dp))

                ToggleSettingRow(
                    title = "TuneIn 代理",
                    subtitle = if (active != null) {
                        "开启后经服务器中转，适合网络受限环境"
                    } else {
                        // 未激活时说清楚为什么开不了，而不是给一个没反应的灰开关。
                        "需先激活后才能使用；未激活不影响直连播放"
                    },
                    checked = tuneInProxy,
                    onToggle = { onToggleTuneInProxy(!tuneInProxy) },
                    enabled = active != null,
                )

                Spacer(modifier = Modifier.height(16.dp))

                ActionSettingRow(
                    title = if (active != null) "管理激活码" else "输入激活码",
                    // 到期时间为 0 表示「已激活但期限未知」（服务端关闭了门禁），
                    // 此时只说已激活，不编一个日期出来。
                    subtitle = when {
                        active == null -> "激活后可开启 TuneIn 代理中转"
                        active.expiresAtSeconds > 0 ->
                            "已激活，有效期至 ${formatExpiry(active.expiresAtSeconds)}"

                        else -> "已激活"
                    },
                    // 已激活才进「管理」这一层:没有绑定关系可管时,多一层弹窗只是多一次点击。
                    onClick = {
                        if (active != null) showManageDialog = true else showActivationDialog = true
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 已激活的人也可能要续期或再买一个给家人，所以不按激活状态隐藏。
                val purchaseContext = LocalContext.current
                val hasTouch = remember(purchaseContext) { purchaseContext.hasTouchScreen() }
                ActionSettingRow(
                    title = "购买激活码",
                    subtitle = if (hasTouch) "在浏览器中打开购买页" else "用手机扫码打开购买页",
                    onClick = {
                        // 打不开浏览器就回落到二维码弹窗 —— 让用户拿另一台设备扫，或照着
                        // 弹窗里的链接手输。静默失败最糟：点了没反应，用户不知道是不是坏了。
                        val opened = hasTouch && purchaseContext.openUrl(PURCHASE_URL)
                        if (!opened) showPurchaseDialog = true
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ToggleSettingRow(
                title = "自动播放",
                subtitle = "开启后启动时自动播放上次播放的电台",
                checked = autoPlayLast,
                onToggle = { onToggleAutoPlay(!autoPlayLast) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            ToggleSettingRow(
                title = "无操作自动进入全屏",
                subtitle = if (isPortrait) {
                    "全屏播放仅横屏可用"
                } else {
                    "首页播放中 30 秒无操作自动进入全屏播放界面"
                },
                checked = autoFullscreen,
                onToggle = { onToggleAutoFullscreen(!autoFullscreen) },
                enabled = !isPortrait,
            )

            Spacer(modifier = Modifier.height(16.dp))

            CityDropdown(
                title = "所在地区",
                subtitle = "设定后地区筛选栏将其置顶，且每次启动默认展示该地区",
                provinces = provinces,
                homeCityCode = homeCityCode,
                expanded = cityMenuExpanded,
                onExpandedChange = { cityMenuExpanded = it },
                onSelect = { onSelectCity(it) },
            )

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

            ActionSettingRow(
                title = "检查更新",
                subtitle = "获取并安装最新版本",
                onClick = onCheckUpdate,
            )

            Spacer(modifier = Modifier.height(16.dp))

            ActionSettingRow(
                title = "关于",
                subtitle = "版权声明、开源与下载渠道",
                onClick = { showAbout = true },
            )
        }

        if (cityMenuExpanded || sourceMenuExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        cityMenuExpanded = false
                        sourceMenuExpanded = false
                    },
            )
        }

        if (showAbout) {
            AboutDialog(onDismiss = { showAbout = false })
        }

        if (showManageDialog) {
            val toastContext = LocalContext.current
            val active = activation as? ActivationState.Active
            ActivationManageDialog(
                expiryText = active?.expiresAtSeconds
                    ?.takeIf { it > 0 }
                    ?.let { "本设备已激活，有效期至 ${formatExpiry(it)}" },
                onUpdate = {
                    showManageDialog = false
                    showActivationDialog = true
                },
                onUnbind = {
                    showManageDialog = false
                    onUnbindActivation { message ->
                        Toast.makeText(toastContext, message, Toast.LENGTH_LONG).show()
                    }
                },
                onDismiss = { showManageDialog = false },
            )
        }

        if (showActivationDialog) {
            val toastContext = LocalContext.current
            ActivationCodeDialog(
                // 触摸设备用系统输入法更快；只有靠遥控器的 TV 才需要那套网格键盘。
                useGridKeyboard = !toastContext.hasTouchScreen(),
                submitting = isRedeemingCode,
                onSubmit = { code ->
                    onRedeemActivationCode(code) { message ->
                        Toast.makeText(toastContext, message, Toast.LENGTH_LONG).show()
                    }
                    showActivationDialog = false
                },
                onDismiss = { showActivationDialog = false },
            )
        }

        if (showPurchaseDialog) {
            PurchaseDialog(onDismiss = { showPurchaseDialog = false })
        }
    }
}

/**
 * 是否是触摸设备。TV 盒子在 manifest 里声明了 `touchscreen` 非必需
 * （见 AndroidManifest 的 `uses-feature`），据此把「遥控器」与「手指」两条输入路径分开。
 */
private fun android.content.Context.hasTouchScreen(): Boolean =
    packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)

/**
 * 用系统浏览器打开链接，返回是否成功 —— TV 盒子与精简 ROM 上常常压根没有浏览器，
 * 调用方要据此走兜底路径。
 *
 * `FLAG_ACTIVITY_NEW_TASK` 不能省：这里的 context 来自 `LocalContext`，在某些宿主下
 * 未必是 Activity，从非 Activity context 启动会直接抛异常。
 */
private fun android.content.Context.openUrl(url: String): Boolean = runCatching {
    startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrDefault(false)

/** 城市下拉：薄封装泛型 [Dropdown]，把 [Province] 映射到通用参数。 */
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
    Dropdown(
        title = title,
        subtitle = subtitle,
        items = provinces,
        selectedIndex = provinces.indexOfFirst { it.provinceCode == homeCityCode }
            .coerceAtLeast(0),
        currentLabel = provinces.firstOrNull { it.provinceCode == homeCityCode }
            ?.provinceName ?: "国家",
        itemKey = { it.provinceCode },
        itemLabel = { it.provinceName },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        onSelect = { onSelect(it.provinceCode) },
    )
}

/** 来源下拉：薄封装泛型 [Dropdown]，把 [RadioSourceType] 映射到通用参数。 */
@Composable
private fun SourceDropdown(
    title: String,
    subtitle: String,
    selected: RadioSourceType,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (RadioSourceType) -> Unit,
    anchorFocusRequester: FocusRequester,
) {
    val entries = RadioSourceType.entries
    Dropdown(
        title = title,
        subtitle = subtitle,
        items = entries,
        selectedIndex = entries.indexOf(selected).coerceAtLeast(0),
        currentLabel = selected.displayName,
        itemKey = { it.key },
        itemLabel = { it.displayName },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        onSelect = onSelect,
        anchorFocusRequester = anchorFocusRequester,
    )
}

/**
 * 通用下拉选择：折叠时为一枚锚点按钮（显示当前值），OK 键展开为可滚动列表。
 * 展开后焦点落在当前选中项；选择或返回键收起并把焦点送回锚点。
 */
@Composable
private fun <T> Dropdown(
    title: String,
    subtitle: String,
    items: List<T>,
    selectedIndex: Int,
    currentLabel: String,
    itemKey: (T) -> Any,
    itemLabel: (T) -> String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (T) -> Unit,
    anchorFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    val currentName = currentLabel
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
                    itemsIndexed(items, key = { _, it -> itemKey(it) }) { index, item ->
                        val selected = index == selectedIndex
                        CityMenuItem(
                            label = itemLabel(item),
                            selected = selected,
                            focusRequester = if (selected) selectedItemFocusRequester else null,
                            onClick = {
                                onSelect(item)
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
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color.White else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (enabled) Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                else Modifier
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

/** 带开关的设置行：复用 [SettingRow]，右侧为开关。 */
@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onToggle,
        focusRequester = focusRequester,
        enabled = enabled,
    ) {
        // 禁用时（如竖屏下的自动全屏）恒显示为关。
        ToggleSwitch(checked = checked && enabled)
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
