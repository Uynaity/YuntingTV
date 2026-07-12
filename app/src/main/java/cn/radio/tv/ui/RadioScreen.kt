package cn.radio.tv.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.ui.components.ChannelCard
import cn.radio.tv.ui.components.ClockText
import cn.radio.tv.ui.components.CompactFilter
import cn.radio.tv.ui.components.ExitConfirmDialog
import cn.radio.tv.ui.components.FavoriteFilterChip
import cn.radio.tv.ui.components.FilterItem
import cn.radio.tv.ui.components.FilterRow
import cn.radio.tv.ui.components.LoadingIndicator
import cn.radio.tv.ui.components.PlaybillContent
import cn.radio.tv.ui.components.PlayerPanel
import cn.radio.tv.ui.components.SettingsButton
import cn.radio.tv.ui.components.UpdateDialog
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
@Composable
fun RadioScreen(viewModel: RadioViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    val gridFocusRequester = remember { FocusRequester() }
    val cityFocusRequester = remember { FocusRequester() }
    val favoriteFocusRequester = remember { FocusRequester() }

    // 筛选栏是否展开（两行 chips）；折叠时显示「城市 ｜ 类型」摘要。
    // 启动即展开：焦点直接落在恒存在的「收藏」按钮上（见下方 LaunchedEffect(filtersExpanded)），
    // 不抢焦点到尚未加载完的列表/城市 chip，避开首启焦点竞态。
    var filtersExpanded by remember { mutableStateOf(true) }
    // 展开后筛选区是否已真正承接过焦点（门闩）。
    // 仅当门闩置位后，焦点再进入电台列表才折叠 —— 避免展开瞬间焦点
    // 短暂漂过列表被误判为「离开筛选区」而立刻折回。
    var filtersTouched by remember { mutableStateOf(false) }
    // 最近一次上/下方向键（由下方 listPane 的 onPreviewKeyEvent 记录）。
    // 用于区分摘要按钮「为何获焦」：真按上键从列表回到摘要 → 展开；
    // 折叠瞬间布局上移把焦点甩回摘要（无按键）→ 不展开、顶回列表。
    // 布局回弹不产生按键事件，故这是个不依赖时序的因果判据，取代旧的 400ms 猜窗口。
    var lastKeyWasUp by remember { mutableStateOf(false) }

    // 退出确认弹窗是否显示
    var showExitDialog by remember { mutableStateOf(false) }

    // 设置页是否显示（整屏覆盖首页）
    var showSettings by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // 电视端（leanback）没有触摸，展开/折叠全靠焦点驱动。手机端才需要「上滑折叠/下拉展开」
    // 的 nestedScroll。电视端必须禁用它：DPAD 焦点跨入置顶列表会被当作向下 overscroll，
    // 累计过阈值误触发下拉展开，把焦点甩回收藏（本次 bug 根因）。
    val isTv = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    // 手动检查更新的一次性提示（有更新则走 updateState 弹窗，此处只提示无更新/失败）。
    LaunchedEffect(Unit) {
        viewModel.updateEvents.collect { event ->
            val msg = when (event) {
                UpdateEvent.UpToDate -> "当前已是最新版本"
                UpdateEvent.Failed -> "检查更新失败"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 返回键两段式：
    // 1) 焦点在电台列表（筛选折叠）→ 先把焦点收回到城市筛选（展开筛选区）。
    //    展开后由下方 LaunchedEffect(filtersExpanded) 把焦点送到选中的城市 chip。
    // 2) 焦点已在筛选区（展开）→ 弹出退出确认弹窗。
    // 弹窗显示时禁用本拦截，交由 Dialog 自身的返回键（onDismissRequest）关闭。
    BackHandler(enabled = !showExitDialog && !showSettings) {
        if (state.showPlaybill) {
            viewModel.togglePlaybill()
        } else if (filtersExpanded) {
            showExitDialog = true
        } else {
            filtersExpanded = true
        }
    }

    // 节目单自动刷新：仅在前台(STARTED)驱动，App 退到后台时协程随生命周期挂起。
    // 回到前台先立即刷一次（后台音频持续播放，期间已换过几档节目，副标题/卡片可能陈旧），
    // 再按整点/半点循环，避免恢复前台后最长 30 分钟仍显示旧节目名。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshPrograms()
                delay(viewModel.millisToNextHalfHour().milliseconds)
            }
        }
    }

    // 关闭节目单时列表重新组合，把焦点送回首格（横屏节目单占据右侧列表区，
    // 关闭后若不主动收回焦点会出现无焦点态）。
    // 加 !filtersExpanded 守卫：启动即展开态时不抢焦点到列表（交由下方 LaunchedEffect
    // 把焦点落到收藏按钮）；仅在折叠态（正常浏览/关节目单）才收焦回列表。
    LaunchedEffect(state.channels.isNotEmpty(), state.showPlaybill) {
        if (state.channels.isNotEmpty() && !state.showPlaybill && !filtersExpanded) {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }

    // 手机端触摸滚动不产生焦点事件（电视端靠焦点进列表折叠），故这里按滚动方向处理：
    // - 手指上滑（内容向下滚，available.y<0）：立即收起展开的筛选栏，不论列表滚到哪。
    // - 列表已到顶后继续下拉（onPostScroll 里仍有正向 overscroll，且为手势拖动）：
    //   累计下拉距离超过阈值则展开筛选栏；反向或非拖动来源则清零，避免惯性误触。
    val pullToExpandThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val filterScrollConnection = remember {
        object : NestedScrollConnection {
            private var overscroll = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && filtersExpanded) filtersExpanded = false
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f && !filtersExpanded) {
                    overscroll += available.y
                    if (overscroll >= pullToExpandThreshold) {
                        filtersExpanded = true
                        overscroll = 0f
                    }
                } else {
                    overscroll = 0f
                }
                return Offset.Zero
            }
        }
    }

    // 展开后把焦点统一落到「收藏」按钮：展开态它恒存在、不依赖城市/频道加载，
    // 无 requestFocus 竞态。启动首屏与用户上键展开都走这里，落点一致。
    LaunchedEffect(filtersExpanded) {
        if (filtersExpanded) {
            runCatching { favoriteFocusRequester.requestFocus() }
        }
    }

    // 设置页与首页用 AnimatedContent 过渡：进入设置横向滑入+淡入，返回则反向。
    // 整屏替换（而非叠放）：避免遥控焦点穿透到背后的电台列表。
    AnimatedContent(
        targetState = showSettings,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
            } else {
                fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            }
        },
        label = "settings-transition",
    ) { inSettings ->
        if (inSettings) {
            SettingsScreen(
                selectedSource = state.selectedSource,
                provinces = state.provinces,
                homeCityCode = state.homeCityCode,
                autoPlayLast = state.autoPlayLast,
                onSelectSource = viewModel::setSource,
                onSelectCity = viewModel::setHomeCity,
                onToggleAutoPlay = viewModel::setAutoPlayLast,
                onCheckUpdate = { viewModel.checkForUpdate(manual = true) },
                onClose = { showSettings = false },
            )
        } else {
            // 列表面板抽为可复用内容：横屏置于右侧 2/3，竖屏置于顶部占满剩余高度。
            val listPane: @Composable (Modifier) -> Unit = { paneModifier ->
                Column(
                    modifier = paneModifier
                        .fillMaxSize()
                        // 记录最近一次上/下方向键。preview 阶段在焦点子节点处理前触发，
                        // 保证摘要按钮 onFocused 读到的是本次导航的真实按键方向。不消费按键。
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown) {
                                when (e.key) {
                                    Key.DirectionUp -> lastKeyWasUp = true
                                    Key.DirectionDown -> lastKeyWasUp = false
                                }
                            }
                            false
                        },
                ) {
                    // 顶部栏：左侧「收藏 / 筛选摘要」按钮 + 右上角设置按钮，二者同处一行；
                    // 展开后的城市/类型两行位于其下方、独占整行宽度（不被设置按钮挤占右侧）。
                    Row(
                        // 顶部留白放在此行（而非整个面板）：节目单占右侧区时可从顶部全屏铺开，
                        // 同时给顶栏按钮（含设置按钮）聚焦放大留出空间，高亮不溢出上缘。
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 顶部按钮槽：展开态=「★ 收藏」开关，折叠态=「城市 ｜ 类型」摘要。
                        // 二者同位置、同样式，视觉上像同一个按钮在两态间切换文本/大小。
                        // 注意：CompactFilter 获焦即展开，故仅在显示「收藏」开关时挂门闩，
                        // 避免折叠摘要获焦时污染 filtersTouched。
                        Box(
                            modifier = Modifier
                                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                                .animateContentSize(),
                        ) {
                            if (filtersExpanded) {
                                FavoriteFilterChip(
                                    active = state.showFavorites,
                                    onClick = {
                                        if (state.showFavorites) viewModel.hideFavoritesView()
                                        else viewModel.showFavoritesView()
                                    },
                                    modifier = Modifier.onFocusChanged {
                                        // 焦点落入收藏开关 → 门闩置位
                                        if (it.hasFocus) filtersTouched = true
                                    },
                                    focusRequester = favoriteFocusRequester,
                                )
                            } else {
                                CompactFilter(
                                    cityName = currentProvinceName(state),
                                    typeName = currentCategoryName(state),
                                    favoritesActive = state.showFavorites,
                                    onActivate = { filtersExpanded = true },
                                    onFocused = {
                                        if (lastKeyWasUp) {
                                            // 用户按上键从列表回到摘要栏：展开
                                            filtersExpanded = true
                                        } else {
                                            // 无上键却获焦 = 折叠瞬间布局回弹把焦点甩了过来：
                                            // 不展开，把焦点顶回列表，断开死循环（不论回弹几次都拦得住）。
                                            runCatching { gridFocusRequester.requestFocus() }
                                        }
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // 设置按钮左侧的时钟（HH:MM 24 小时制，白色粗体）
                        ClockText(modifier = Modifier.padding(end = 16.dp))

                        // 右上角设置入口
                        SettingsButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }

                    // 展开态：城市/类型两行，独占整行宽度（收藏视图下隐藏，避免误以为可叠加筛选）。
                    // AnimatedVisibility 提供垂直展开/收起 + 淡入淡出动画，恢复筛选栏的展开收起过渡。
                    AnimatedVisibility(visible = filtersExpanded && !state.showFavorites) {
                        Column(
                            modifier = Modifier.onFocusChanged {
                                // 焦点真正落入筛选区 → 门闩置位
                                if (it.hasFocus) filtersTouched = true
                            },
                        ) {
                            Text(
                                text = "城市",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    top = 8.dp,
                                    bottom = 4.dp
                                ),
                            )
                            FilterRow(
                                items = state.orderedProvinces.map {
                                    FilterItem(it.provinceCode.toString(), it.provinceName)
                                },
                                selectedKey = state.selectedProvinceCode.toString(),
                                onSelect = { viewModel.selectProvince(it.toLong()) },
                                modifier = Modifier.fillMaxWidth(),
                                selectedItemFocusRequester = cityFocusRequester,
                            )

                            Text(
                                text = "类型",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    top = 8.dp,
                                    bottom = 4.dp
                                ),
                            )
                            FilterRow(
                                items = state.categories.map { FilterItem(it.id, it.categoryName) },
                                selectedKey = state.selectedCategoryId,
                                onSelect = { viewModel.selectCategory(it) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // 电台 Grid / 状态
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 12.dp)
                            // 仅手机端挂手势展开/折叠；电视端禁用，避免焦点导航误触发下拉展开。
                            .then(
                                if (isTv) Modifier
                                else Modifier.nestedScroll(filterScrollConnection)
                            )
                            .onFocusChanged {
                                // 焦点进入电台列表，且筛选区此前已真正承接过焦点（门闩置位）→ 折叠筛选栏。
                                // 门闩可滤除展开瞬间的焦点漂移。折叠引发的焦点回弹由摘要按钮
                                // onFocused 按 lastKeyWasUp 拦截，此处无需再记窗口标志。
                                if (it.hasFocus && filtersTouched) {
                                    filtersExpanded = false
                                    filtersTouched = false
                                }
                            },
                    ) {
                        when {
                            state.showFavorites && state.favorites.isEmpty() -> {
                                StatusText("暂无收藏\n长按电台卡片即可收藏")
                            }

                            // 进入收藏即刷新全部收藏节目单，刷新期间显示加载动画（同切换城市体验）。
                            state.showFavorites && state.isRefreshingFavorites -> {
                                LoadingIndicator()
                            }

                            !state.showFavorites && state.isLoadingChannels -> {
                                LoadingIndicator()
                            }

                            !state.showFavorites && state.error != null && state.channels.isEmpty() -> {
                                StatusText(state.error ?: "出错了")
                            }

                            !state.showFavorites && state.channels.isEmpty() -> {
                                StatusText("暂无电台")
                            }

                            else -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 180.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .focusGroup(),
                                    // 四周留聚焦放大（1.05x）所需最小内边距；底部额外加高，给悬浮在
                                    // 播放栏顶边、上半探入列表的进度条胶囊让位（TV/手机共用，无妨）。
                                    contentPadding = PaddingValues(
                                        start = 6.dp,
                                        top = 6.dp,
                                        end = 6.dp,
                                        bottom = 12.dp
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    itemsIndexed(
                                        state.displayedChannels,
                                        key = { _, channel -> channel.contentId },
                                    ) { index, channel ->
                                        // 收藏视图里每张卡都是收藏(含跨源)，星标恒显示，并标注来源；
                                        // 普通浏览仅按当前源星标、不标来源。
                                        val favSource = if (state.showFavorites) {
                                            state.favorites.firstOrNull { it.channel.contentId == channel.contentId }?.source
                                        } else null
                                        ChannelCard(
                                            channel = channel,
                                            isCurrent = state.currentChannel?.contentId == channel.contentId,
                                            isFavorite = state.showFavorites || state.favoriteIds.contains(
                                                channel.contentId
                                            ),
                                            onClick = { viewModel.playChannel(channel) },
                                            onLongClick = { viewModel.toggleFavorite(channel) },
                                            sourceLabel = favSource?.displayName,
                                            modifier = if (index == 0) {
                                                Modifier.focusRequester(gridFocusRequester)
                                            } else {
                                                Modifier
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 竖屏：列表在上占满剩余高度，播放器横排置于屏幕底部；横屏：播放器左侧 1/3。
            val isPortrait = LocalConfiguration.current.orientation ==
                    Configuration.ORIENTATION_PORTRAIT
            if (isPortrait) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    listPane(Modifier.weight(1f))
                    PlayerPanel(
                        channel = state.currentChannel,
                        isPlaying = state.isPlaying,
                        isBuffering = state.isBuffering,
                        retrySeconds = state.retrySeconds,
                        isFavorite = state.currentIsFavorite,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        horizontal = true,
                        positionMs = progress.positionMs,
                        durationMs = progress.durationMs,
                        seekable = progress.seekable,
                        onSeekTo = viewModel::seekTo,
                        sleepTimerRemainingMinutes = state.sleepTimerRemainingMinutes,
                        sleepTimerTotalMinutes = state.sleepTimerTotalMinutes,
                        onSetSleepTimer = viewModel::setSleepTimer,
                        showPlaybill = state.showPlaybill,
                        onTogglePlaybill = viewModel::togglePlaybill,
                        playbillDates = state.playbillDates,
                        playbillPrograms = state.playbillPrograms,
                        selectedPlaybillDate = state.selectedPlaybillDate,
                        isLoadingPlaybill = state.isLoadingPlaybill,
                        playbillError = state.playbillError,
                        onSelectPlaybillDate = viewModel::selectPlaybillDate,
                        onPlayReplay = viewModel::playReplay,
                        onPlayLive = viewModel::playLive,
                        playingProgramTitle = state.playingProgramTitle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    PlayerPanel(
                        channel = state.currentChannel,
                        isPlaying = state.isPlaying,
                        isBuffering = state.isBuffering,
                        retrySeconds = state.retrySeconds,
                        isFavorite = state.currentIsFavorite,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        positionMs = progress.positionMs,
                        durationMs = progress.durationMs,
                        seekable = progress.seekable,
                        onSeekTo = viewModel::seekTo,
                        sleepTimerRemainingMinutes = state.sleepTimerRemainingMinutes,
                        sleepTimerTotalMinutes = state.sleepTimerTotalMinutes,
                        onSetSleepTimer = viewModel::setSleepTimer,
                        showPlaybill = state.showPlaybill,
                        onTogglePlaybill = viewModel::togglePlaybill,
                        playbillDates = state.playbillDates,
                        playbillPrograms = state.playbillPrograms,
                        selectedPlaybillDate = state.selectedPlaybillDate,
                        isLoadingPlaybill = state.isLoadingPlaybill,
                        playbillError = state.playbillError,
                        onSelectPlaybillDate = viewModel::selectPlaybillDate,
                        onPlayReplay = viewModel::playReplay,
                        playingProgramTitle = state.playingProgramTitle,
                        modifier = Modifier.weight(0.32f),
                    )
                    // 右侧区域：开启节目单时显示节目单（占据整块列表区，宽度充足），
                    // 否则显示电台列表。列表隐藏即杜绝「开着节目单还能换台」的冲突。
                    if (state.showPlaybill && state.currentChannel != null) {
                        PlaybillContent(
                            dates = state.playbillDates,
                            programs = state.playbillPrograms,
                            selectedDate = state.selectedPlaybillDate,
                            isLoading = state.isLoadingPlaybill,
                            error = state.playbillError,
                            onSelectDate = viewModel::selectPlaybillDate,
                            onPlayReplay = viewModel::playReplay,
                            isPlaying = state.isPlaying,
                            playingProgramTitle = state.playingProgramTitle,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onPlayLive = viewModel::playLive,
                            modifier = Modifier
                                .weight(0.68f)
                                .fillMaxHeight()
                                .padding(start = 8.dp),
                        )
                    } else {
                        listPane(
                            Modifier
                                .weight(0.68f)
                                .padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }

    // 退出确认弹窗：确定 → 结束 Activity 退出应用；取消/返回 → 关闭弹窗
    if (showExitDialog) {
        ExitConfirmDialog(
            onConfirm = {
                showExitDialog = false
                // finishAndRemoveTask 移除任务并触发 PlaybackService.onTaskRemoved，
                // 停掉前台播放服务，避免只退到后台仍在放音。
                (context as? Activity)?.finishAndRemoveTask()
            },
            onDismiss = { showExitDialog = false },
        )
    }

    // 更新弹窗：启动自动检查或设置页手动检查发现新版时显示（盖过设置页/列表）。
    (updateState as? UpdateState.Available)?.let { available ->
        UpdateDialog(
            versionName = available.app.versionName,
            sizeBytes = available.app.size,
            downloading = available.downloading,
            progress = available.progress,
            onConfirm = { viewModel.downloadAndInstall() },
            onDismiss = { viewModel.dismissUpdate() },
        )
    }
}

private fun currentProvinceName(state: RadioUiState): String =
    state.provinces.firstOrNull { it.provinceCode == state.selectedProvinceCode }?.provinceName
        ?: "全部地区"

private fun currentCategoryName(state: RadioUiState): String =
    state.categories.firstOrNull { it.id == state.selectedCategoryId }?.categoryName
        ?: "全部"

@Composable
private fun StatusText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
