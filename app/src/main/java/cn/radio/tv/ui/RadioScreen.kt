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
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import cn.radio.tv.ui.components.FullScreenPlayer
import cn.radio.tv.ui.components.LoadingIndicator
import cn.radio.tv.ui.components.MobileSearchBar
import cn.radio.tv.ui.components.PlaybillContent
import cn.radio.tv.ui.components.PlayerPanel
import cn.radio.tv.ui.components.SearchButton
import cn.radio.tv.ui.components.SearchPanel
import cn.radio.tv.ui.components.SettingsButton
import cn.radio.tv.ui.components.SpinningArc
import cn.radio.tv.ui.components.UpdateDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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

    val gridState = rememberLazyGridState()

    var filtersExpanded by remember { mutableStateOf(true) }
    var filtersTouched by remember { mutableStateOf(false) }
    var lastKeyWasUp by remember { mutableStateOf(false) }

    var showExitDialog by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }

    var showFullscreen by remember { mutableStateOf(false) }

    // 首页无操作自动进全屏：任意按键 tick++ 重置计时。
    var homeInteractionTick by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val isTv = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    // 全屏播放仅横屏可用；竖屏（手机）不自动进全屏。
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    LaunchedEffect(Unit) {
        viewModel.updateEvents.collect { event ->
            val msg = when (event) {
                UpdateEvent.UpToDate -> "当前已是最新版本"
                UpdateEvent.Failed -> "检查更新失败"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = !showExitDialog && !showSettings) {
        if (showFullscreen) {
            showFullscreen = false
        } else if (state.searchActive) {
            viewModel.closeSearch()
        } else if (state.showPlaybill) {
            viewModel.togglePlaybill()
        } else if (filtersExpanded) {
            showExitDialog = true
        } else {
            filtersExpanded = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshPrograms()
                delay(viewModel.millisToNextHalfHour().milliseconds)
            }
        }
    }

    // 搜索态排除在外：键盘刚拿到焦点，别被这里抢到右侧列表去。
    LaunchedEffect(state.channels.isNotEmpty(), state.showPlaybill, state.searchActive) {
        if (state.channels.isNotEmpty() && !state.showPlaybill && !filtersExpanded &&
            !state.searchActive
        ) {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }

    // 切来源/地区/分类/收藏视图后列表回到顶部。分页下这一步是必须的：残留的深滚动位置
    // 会在只有一页的新列表里停在末尾，从而连锁触发翻页预取，把新筛选一路自动翻到底。
    LaunchedEffect(
        state.selectedSource,
        state.selectedProvinceCode,
        state.selectedCategoryId,
        state.showFavorites,
        // 查询词每变一次结果就是全新一批，同理要回顶。
        state.searchQuery,
        state.searchActive,
    ) {
        gridState.scrollToItem(0)
    }

    // 滚到接近底部自动翻页。用 snapshotFlow 而非在 composition 里判断：后者每帧重组都跑一次。
    // D-pad 与触摸共用这一条路径 —— 焦点移动同样会滚动列表，visibleItemsInfo 一样变化。
    // 幂等与「是否还有下一页」由 ViewModel 的守卫负责，这里只管报告「快到底了」。
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                val total = gridState.layoutInfo.totalItemsCount
                if (last >= 0 && last >= total - PREFETCH_DISTANCE) {
                    viewModel.loadMoreChannels()
                }
            }
    }

    val pullToExpandThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val density = LocalDensity.current
    val navigationBarHeightPx = WindowInsets.navigationBars.getBottom(density)
    val playerBarColor = MaterialTheme.colorScheme.surface
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

    LaunchedEffect(filtersExpanded) {
        if (filtersExpanded) {
            runCatching { favoriteFocusRequester.requestFocus() }
        }
    }

    // 首页播放中 30s 无操作自动进全屏；任意按键（tick 变化）或相关状态变化都会重置计时。
    LaunchedEffect(
        homeInteractionTick,
        state.autoFullscreen,
        isPortrait,
        state.isPlaying,
        state.currentChannel,
        showFullscreen,
        showSettings,
        showExitDialog,
        state.showPlaybill,
    ) {
        if (!state.autoFullscreen || isPortrait) return@LaunchedEffect
        if (showFullscreen || showSettings || showExitDialog || state.showPlaybill) return@LaunchedEffect
        if (!state.isPlaying || state.currentChannel == null) return@LaunchedEffect
        delay(30_000L.milliseconds)
        showFullscreen = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 根内容会避让底部手势区，因此额外把播放器面板色绘制到该 inset 后方；
            // 交互控件仍留在安全区内，但手势小白条不再落在页面黑底上。
            .drawBehind {
                if (!isTv && isPortrait && !showSettings && navigationBarHeightPx > 0) {
                    val navigationBarHeight = navigationBarHeightPx.toFloat()
                    drawRect(
                        color = playerBarColor,
                        topLeft = Offset(
                            x = 0f,
                            y = (size.height - navigationBarHeight).coerceAtLeast(0f),
                        ),
                        size = Size(
                            width = size.width,
                            height = navigationBarHeight,
                        ),
                    )
                }
            }
            // 背景仍绘制到透明系统栏下方；手机的可交互内容避开状态栏、刘海和手势区。
            .then(
                if (isTv) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            )
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) homeInteractionTick++
                false
            },
    ) {
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
                    autoFullscreen = state.autoFullscreen,
                    onSelectSource = viewModel::setSource,
                    onSelectCity = viewModel::setHomeCity,
                    onToggleAutoPlay = viewModel::setAutoPlayLast,
                    onToggleAutoFullscreen = viewModel::setAutoFullscreen,
                    onCheckUpdate = { viewModel.checkForUpdate(manual = true) },
                    onClose = { showSettings = false },
                )
            } else {
                val listPane: @Composable (Modifier) -> Unit = { paneModifier ->
                    Column(
                        modifier = paneModifier
                            .fillMaxSize()
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
                        AnimatedContent(
                            targetState = isPortrait && state.searchActive,
                            transitionSpec = {
                                if (targetState) {
                                    (slideInHorizontally(tween(240)) { it / 5 } +
                                            fadeIn(tween(180))) togetherWith fadeOut(tween(100))
                                } else {
                                    fadeIn(tween(180)) togetherWith
                                            (slideOutHorizontally(tween(200)) { it / 5 } +
                                                    fadeOut(tween(120)))
                                }
                            },
                            label = "mobile-search-bar-transition",
                        ) { showMobileSearchBar ->
                            if (showMobileSearchBar) {
                                MobileSearchBar(
                                    query = state.searchQuery,
                                    isSearching = state.isSearching,
                                    resultCount = state.searchResults.size,
                                    onQueryChange = viewModel::setSearchQuery,
                                    onClose = viewModel::closeSearch,
                                    onClear = { viewModel.setSearchQuery("") },
                                )
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = if (isPortrait) 8.dp else 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(start = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // 只让收藏 chip 与「地区｜类型」在同一容器里做尺寸过渡。
                                        // 两者本体约 36dp，上下各 4dp 后固定为 44dp，与图标按钮等高。
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = 4.dp)
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
                                                            filtersExpanded = true
                                                        } else {
                                                            runCatching { gridFocusRequester.requestFocus() }
                                                        }
                                                    },
                                                )
                                            }
                                        }

                                        // 搜索不参与上方尺寸动画，展开/收起时只随前一项水平移动，不上下抖动。
                                        SearchButton(
                                            active = state.searchActive,
                                            onClick = {
                                                if (state.searchActive) viewModel.closeSearch()
                                                else viewModel.openSearch()
                                            },
                                            modifier = Modifier.onFocusChanged {
                                                if (it.hasFocus) filtersTouched = true
                                            },
                                        )
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    ClockText(modifier = Modifier.padding(end = 16.dp))

                                    SettingsButton(
                                        onClick = { showSettings = true },
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = filtersExpanded && !state.showFavorites) {
                            Column(
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) filtersTouched = true
                                },
                            ) {
                                Text(
                                    text = "地区",
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
                                    items = state.categories.map {
                                        FilterItem(
                                            it.id,
                                            it.categoryName
                                        )
                                    },
                                    selectedKey = state.selectedCategoryId,
                                    onSelect = { viewModel.selectCategory(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 12.dp)
                                .then(
                                    if (isTv) Modifier
                                    else Modifier.nestedScroll(filterScrollConnection)
                                )
                                .onFocusChanged {
                                    if (it.hasFocus && filtersTouched) {
                                        filtersExpanded = false
                                        filtersTouched = false
                                    }
                                },
                        ) {
                            when {
                                // 搜索态优先判：此时右栏展示的是 searchResults，与 channels / favorites 无关。
                                state.showingSearchResults && state.isSearching &&
                                        state.searchResults.isEmpty() -> {
                                    LoadingIndicator()
                                }

                                state.showingSearchResults && state.searchResults.isEmpty() -> {
                                    StatusText("没有匹配的电台\n试试拼音首字母，如 bj")
                                }

                                state.showFavorites && state.favorites.isEmpty() -> {
                                    StatusText("暂无收藏\n长按电台卡片即可收藏")
                                }

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
                                        state = gridState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .focusGroup(),
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

                                        // 翻页指示：跨整行独占一格，追加在末尾，不遮挡已有内容。
                                        if (state.isLoadingMore) {
                                            item(span = { GridItemSpan(maxLineSpan) }) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 12.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    SpinningArc(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        size = 24.dp,
                                                        strokeWidth = 3.dp,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val playerPane: @Composable (Boolean, Modifier) -> Unit =
                    { horizontal, paneModifier ->
                        PlayerPanel(
                            channel = state.currentChannel,
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            retrySeconds = state.retrySeconds,
                            isFavorite = state.currentIsFavorite,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            horizontal = horizontal,
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
                            onOpenFullscreen = { showFullscreen = true },
                            modifier = paneModifier,
                        )
                    }

                AnimatedContent(
                    targetState = showFullscreen,
                    transitionSpec = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                    },
                    label = "fullscreen-transition",
                ) { fullscreen ->
                    if (fullscreen) {
                        FullScreenPlayer(
                            channel = state.currentChannel,
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            retrySeconds = state.retrySeconds,
                            isFavorite = state.currentIsFavorite,
                            positionMs = progress.positionMs,
                            durationMs = progress.durationMs,
                            seekable = progress.seekable,
                            playingProgramTitle = state.playingProgramTitle,
                            sleepTimerRemainingMinutes = state.sleepTimerRemainingMinutes,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onSeekTo = viewModel::seekTo,
                        )
                    } else {
                        if (isPortrait) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                                listPane(Modifier.weight(1f))
                                playerPane(true, Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                                // 搜索态下左栏让位给键盘：播放不中断，面板短距离滑入/滑出。
                                AnimatedContent(
                                    targetState = state.searchActive,
                                    transitionSpec = {
                                        if (targetState) {
                                            (slideInHorizontally(tween(240)) { -it / 6 } +
                                                    fadeIn(tween(180))) togetherWith fadeOut(
                                                tween(
                                                    100
                                                )
                                            )
                                        } else {
                                            fadeIn(tween(180)) togetherWith
                                                    (slideOutHorizontally(tween(200)) { -it / 6 } +
                                                            fadeOut(tween(120)))
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(0.32f)
                                        .fillMaxHeight(),
                                    label = "landscape-search-panel-transition",
                                ) { searching ->
                                    if (searching) {
                                        SearchPanel(
                                            query = state.searchQuery,
                                            isSearching = state.isSearching,
                                            resultCount = state.searchResults.size,
                                            onAppend = viewModel::appendSearchChar,
                                            onBackspace = viewModel::backspaceSearch,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        playerPane(false, Modifier.fillMaxSize())
                                    }
                                }
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
            }
        }

        if (showExitDialog) {
            ExitConfirmDialog(
                onConfirm = {
                    showExitDialog = false
                    (context as? Activity)?.finishAndRemoveTask()
                },
                onDismiss = { showExitDialog = false },
            )
        }

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
}

private fun currentProvinceName(state: RadioUiState): String =
    state.provinces.firstOrNull { it.provinceCode == state.selectedProvinceCode }?.provinceName
        ?: "全部地区"

private fun currentCategoryName(state: RadioUiState): String =
    state.categories.firstOrNull { it.id == state.selectedCategoryId }?.categoryName
        ?: "全部"

/**
 * 距列表末尾还剩几格时开始预取下一页。取约两行（Adaptive 180dp 下手机 2 列 / TV 6 列），
 * 让加载在用户滑到底之前就开始，滚动不断档。
 */
private const val PREFETCH_DISTANCE = 12

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
