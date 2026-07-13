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

    var filtersExpanded by remember { mutableStateOf(true) }
    var filtersTouched by remember { mutableStateOf(false) }
    var lastKeyWasUp by remember { mutableStateOf(false) }

    var showExitDialog by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }

    var showFullscreen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val isTv = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
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

    LaunchedEffect(state.channels.isNotEmpty(), state.showPlaybill) {
        if (state.channels.isNotEmpty() && !state.showPlaybill && !filtersExpanded) {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }

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

    LaunchedEffect(filtersExpanded) {
        if (filtersExpanded) {
            runCatching { favoriteFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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

                        Spacer(modifier = Modifier.weight(1f))

                        ClockText(modifier = Modifier.padding(end = 16.dp))

                        SettingsButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }

                    AnimatedVisibility(visible = filtersExpanded && !state.showFavorites) {
                        Column(
                            modifier = Modifier.onFocusChanged {
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
                                }
                            }
                        }
                    }
                }
            }

            val playerPane: @Composable (Boolean, Modifier) -> Unit = { horizontal, paneModifier ->
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

            val isPortrait = LocalConfiguration.current.orientation ==
                    Configuration.ORIENTATION_PORTRAIT
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
                    playerPane(false, Modifier.weight(0.32f))
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

        androidx.compose.animation.AnimatedVisibility(
            visible = showFullscreen,
            enter = fadeIn(androidx.compose.animation.core.tween(300)),
            exit = fadeOut(androidx.compose.animation.core.tween(300)),
        ) {
            cn.radio.tv.ui.components.FullScreenPlayer(
                channel = state.currentChannel,
                isBuffering = state.isBuffering,
                retrySeconds = state.retrySeconds,
                isFavorite = state.currentIsFavorite,
                playingProgramTitle = state.playingProgramTitle,
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
