package cn.radio.tv.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.data.model.Channel
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
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
@Composable
fun RadioScreen(viewModel: RadioViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    // 频道网格的分页数据。收藏视图不走它（本地全量快照，见 state.favorites）。
    val channels = viewModel.channels.collectAsLazyPagingItems()
    val refreshState = channels.loadState.refresh
    val appendState = channels.loadState.append
    val servedQuery by viewModel.servedQuery.collectAsStateWithLifecycle()

    /** 词已改但请求还没发出去（打字防抖窗口内）：网格上摆着的是上一次查询的内容。 */
    val queryPending = state.searchQuery != servedQuery

    /** 网格上已有属于某次搜索的结果（而不是筛选浏览的列表）。 */
    val hasSearchResults = channels.itemCount > 0 && servedQuery.isNotBlank()

    /** 在刷新，或已提交的内容还没跟上当前输入 —— 两者都是「结果还没到」。 */
    val listLoading = refreshState is LoadState.Loading || queryPending

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
    val immersivePhoneFullscreen = !isTv && !isPortrait && showFullscreen

    // 手机横屏全屏播放：隐藏上下系统栏，并允许内容延伸到短边挖孔区域。
    // 退出全屏或离开组合树时恢复普通手机页面的系统栏与 cutout 策略。
    DisposableEffect(immersivePhoneFullscreen, context) {
        val window = (context as? Activity)?.window
        if (window == null || isTv) return@DisposableEffect onDispose {}

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val originalCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode
        } else {
            null
        }

        if (immersivePhoneFullscreen) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            if (immersivePhoneFullscreen) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && originalCutoutMode != null) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = originalCutoutMode
                    }
                }
            }
        }
    }

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
                // 刷多少条只有 UI 知道：VM 不持有列表，把已加载条数带过去。
                viewModel.refreshPrograms(channels.itemCount)
                delay(viewModel.millisToNextHalfHour().milliseconds)
            }
        }
    }

    // 搜索态排除在外：键盘刚拿到焦点，别被这里抢到右侧列表去。
    LaunchedEffect(channels.itemCount > 0, state.showPlaybill, state.searchActive) {
        if (channels.itemCount > 0 && !state.showPlaybill && !filtersExpanded &&
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
                    tuneInProxy = state.tuneInProxy,
                    activation = state.activation,
                    isRedeemingCode = state.isRedeemingCode,
                    onSelectSource = viewModel::setSource,
                    onSelectCity = viewModel::setHomeCity,
                    onToggleAutoPlay = viewModel::setAutoPlayLast,
                    onToggleAutoFullscreen = viewModel::setAutoFullscreen,
                    onToggleTuneInProxy = viewModel::setTuneInProxy,
                    onRedeemActivationCode = viewModel::redeemActivationCode,
                    onUnbindActivation = viewModel::unbindActivation,
                    onRefreshActivation = viewModel::refreshActivation,
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
                                    isSearching = listLoading,
                                    resultCount = channels.itemCount,
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
                                        // 搜索搜的是当前来源的整份目录，跨地区跨分类 —— 筛选改了
                                        // 也不影响结果。摆着一个点了没反应的控件比没有它更糟，
                                        // 故搜索态下整块让位（退出搜索时原样回来，选中态未被改动）。
                                        //
                                        // 只让收藏 chip 与「地区｜类型」在同一容器里做尺寸过渡。
                                        // 两者本体约 36dp，上下各 4dp 后固定为 44dp，与图标按钮等高。
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = 4.dp)
                                                .animateContentSize(),
                                        ) {
                                            when {
                                                // 搜索态：不留占位，Box 收成 0 宽，SearchButton 平移过来。
                                                state.searchActive -> Unit

                                                filtersExpanded -> FavoriteFilterChip(
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

                                                else -> CompactFilter(
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

                        // 搜索态一并收起：地区/类型对搜索结果不起作用（同上）。
                        // filtersExpanded 本身不重置 —— 退出搜索后展开状态原样回来。
                        AnimatedVisibility(
                            visible = filtersExpanded && !state.showFavorites && !state.searchActive,
                        ) {
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
                                state.showFavorites && state.favorites.isEmpty() -> {
                                    StatusText("暂无收藏\n长按电台卡片即可收藏")
                                }

                                state.showFavorites && state.isRefreshingFavorites -> {
                                    LoadingIndicator()
                                }

                                // 以下三支只看 Paging 的 LoadState（外加防抖窗口）。搜索与筛选
                                // 浏览是同一条分页流的不同查询，不各判一套加载/空态字段。
                                //
                                // 筛选浏览：只要结果还没到就整屏 loading —— 切来源/切筛选是换
                                // 一整套列表，不能让用户对着上一套的残留列表发呆。只判
                                // itemCount==0 不行：换查询时 Paging 会继续展示上一代数据，
                                // itemCount 并不归零。
                                !state.showFavorites && !state.showingSearchResults &&
                                        listLoading -> {
                                    LoadingIndicator()
                                }

                                // 搜索：网格上还没有属于搜索的结果时才整屏 loading。
                                // 已有结果再改词则保留旧结果、只让搜索框转圈，不逐字闪屏。
                                state.showingSearchResults && !hasSearchResults &&
                                        listLoading -> {
                                    LoadingIndicator()
                                }

                                !state.showFavorites && channels.itemCount == 0 -> {
                                    StatusText(
                                        (refreshState as? LoadState.Error)?.error?.message
                                            ?: state.error
                                            ?: if (state.showingSearchResults) {
                                                "没有匹配的电台\n试试拼音首字母，如 bj"
                                            } else {
                                                "暂无电台"
                                            }
                                    )
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
                                        if (state.showFavorites) {
                                            // 收藏是本地全量快照，不分页。逐项自带来源（可跨源），
                                            // 来源与频道必须成对传递，见 [gridKeyOf]。
                                            itemsIndexed(
                                                state.favorites,
                                                key = { _, fav ->
                                                    gridKeyOf(fav.source, fav.channel)
                                                },
                                            ) { index, fav ->
                                                ChannelCard(
                                                    channel = fav.channel,
                                                    // 来源必须一起比：跨来源同号电台不能被误判成"正在播放"。
                                                    isCurrent = state.playingSource == fav.source &&
                                                            state.currentChannel?.contentId ==
                                                            fav.channel.contentId,
                                                    isFavorite = true,
                                                    sourceLabel = fav.source.displayName,
                                                    onClick = {
                                                        viewModel.playChannel(
                                                            fav.channel,
                                                            fav.source
                                                        )
                                                    },
                                                    onLongClick = {
                                                        viewModel.toggleFavorite(
                                                            fav.channel,
                                                            fav.source
                                                        )
                                                    },
                                                    modifier = if (index == 0) {
                                                        Modifier.focusRequester(gridFocusRequester)
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                            }
                                        } else {
                                            // 分页项全体同属当前浏览来源。用 channels[index]（而非
                                            // peek）取值：取项即是 Paging 判断该预取下一页的信号。
                                            items(
                                                count = channels.itemCount,
                                                key = channels.itemKey {
                                                    gridKeyOf(state.selectedSource, it)
                                                },
                                                contentType = channels.itemContentType { "channel" },
                                            ) { index ->
                                                val channel = channels[index] ?: return@items
                                                ChannelCard(
                                                    channel = channel,
                                                    isCurrent = state.playingSource == state.selectedSource &&
                                                            state.currentChannel?.contentId ==
                                                            channel.contentId,
                                                    isFavorite = state.favoriteIds.contains(
                                                        channel.contentId
                                                    ),
                                                    sourceLabel = null,
                                                    onClick = {
                                                        viewModel.playChannel(
                                                            channel,
                                                            state.selectedSource,
                                                        )
                                                    },
                                                    onLongClick = {
                                                        viewModel.toggleFavorite(
                                                            channel,
                                                            state.selectedSource,
                                                        )
                                                    },
                                                    modifier = if (index == 0) {
                                                        Modifier.focusRequester(gridFocusRequester)
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                            }
                                        }

                                        // 翻页指示：跨整行独占一格，追加在末尾，不遮挡已有内容。
                                        if (!state.showFavorites) {
                                            when (appendState) {
                                                is LoadState.Loading -> item(
                                                    span = { GridItemSpan(maxLineSpan) },
                                                ) {
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

                                                // 翻页失败必须给一个显式重试入口：Paging 记住失败
                                                // 状态后不会因为再滚动而自己重试（手写分页时会），
                                                // 没有这一格列表就成了死胡同。
                                                is LoadState.Error -> item(
                                                    span = { GridItemSpan(maxLineSpan) },
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { channels.retry() }
                                                            .padding(vertical = 12.dp),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            text = "加载下一页失败，选中重试",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }

                                                else -> Unit
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
                            playerError = state.playerError,
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
                            playingProgramStart = state.playingProgramStart,
                            onOpenFullscreen = { showFullscreen = true },
                            modifier = paneModifier,
                        )
                    }

                AnimatedContent(
                    targetState = showFullscreen,
                    // 背景仍绘制到透明系统栏下方；手机的可交互内容避开状态栏、刘海和手势区。
                    // 设置页自己按 insets 避让（内容可滚到系统栏后方），故不放在根节点上。
                    modifier = if (isTv || immersivePhoneFullscreen) Modifier
                    else Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    // 只让新页淡入，旧页立即移除。
                    //
                    // 结构上仍是整页二选一替换（spec/frontend/compose-ui-guidelines.md 要求：
                    // 改成叠加会让 DPAD 焦点逃到背后的网格且回不来），这里只去掉「两棵子树同时
                    // 带 alpha 存在」的那一段重叠。
                    //
                    // 双向淡入淡出会给进出两棵全屏子树各开一个离屏合成缓冲，且用完不归还。
                    // 真机实测（1080×2374，SDK 36）反复进出全屏后的 GL 显存平台期：
                    //   300ms 双向淡入淡出 231.6MB / 仅淡入 75.9MB / 完全无动画 56.6MB
                    // 取中间方案：省下 156MB，同时保留视觉过渡。
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith ExitTransition.None
                    },
                    label = "fullscreen-transition",
                ) { fullscreen ->
                    if (fullscreen) {
                        FullScreenPlayer(
                            channel = state.currentChannel,
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            retrySeconds = state.retrySeconds,
                            playerError = state.playerError,
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
                                            isSearching = listLoading,
                                            resultCount = channels.itemCount,
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
                                        playingProgramStart = state.playingProgramStart,
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
