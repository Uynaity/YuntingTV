package com.example.myapplication.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.ui.components.ChannelCard
import com.example.myapplication.ui.components.CompactFilter
import com.example.myapplication.ui.components.ExitConfirmDialog
import com.example.myapplication.ui.components.FavoriteFilterChip
import com.example.myapplication.ui.components.FilterItem
import com.example.myapplication.ui.components.FilterRow
import com.example.myapplication.ui.components.PlayerPanel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun RadioScreen(viewModel: RadioViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val gridFocusRequester = remember { FocusRequester() }
    val cityFocusRequester = remember { FocusRequester() }
    val favoriteFocusRequester = remember { FocusRequester() }

    // 筛选栏是否展开（两行 chips）；折叠时显示「城市 ｜ 类型」摘要
    var filtersExpanded by remember { mutableStateOf(false) }
    // 展开后筛选区是否已真正承接过焦点（门闩）。
    // 仅当门闩置位后，焦点再进入电台列表才折叠 —— 避免展开瞬间焦点
    // 短暂漂过列表被误判为「离开筛选区」而立刻折回。
    var filtersTouched by remember { mutableStateOf(false) }

    // 退出确认弹窗是否显示
    var showExitDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // 返回键两段式：
    // 1) 焦点在电台列表（筛选折叠）→ 先把焦点收回到城市筛选（展开筛选区）。
    //    展开后由下方 LaunchedEffect(filtersExpanded) 把焦点送到选中的城市 chip。
    // 2) 焦点已在筛选区（展开）→ 弹出退出确认弹窗。
    // 弹窗显示时禁用本拦截，交由 Dialog 自身的返回键（onDismissRequest）关闭。
    BackHandler(enabled = !showExitDialog) {
        if (filtersExpanded) {
            showExitDialog = true
        } else {
            filtersExpanded = true
        }
    }

    // 首屏：电台加载完成后把焦点送入列表（默认折叠态）
    LaunchedEffect(state.channels.isNotEmpty()) {
        if (state.channels.isNotEmpty()) {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }

    // 展开后把焦点落到合适的 chip：收藏视图下落到「收藏」开关（城市/类型行已隐藏），
    // 否则落到当前选中的城市 chip（FilterRow 已确保其可见）。
    LaunchedEffect(filtersExpanded) {
        if (filtersExpanded) {
            val target = if (state.showFavorites) favoriteFocusRequester else cityFocusRequester
            runCatching { target.requestFocus() }
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 左侧 ≈ 1/3 播放器
        PlayerPanel(
            channel = state.currentChannel,
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            isFavorite = state.currentChannel?.let { state.favoriteIds.contains(it.contentId) } ?: false,
            onTogglePlayPause = viewModel::togglePlayPause,
            modifier = Modifier.weight(0.32f),
        )

        // 右侧 ≈ 2/3 列表（右/下不留外边距，让 Grid 尽量贴边）
        Column(
            modifier = Modifier
                .weight(0.68f)
                .fillMaxSize()
                .padding(start = 8.dp, top = 20.dp),
        ) {
            // 顶部筛选区：展开=两行 chips / 折叠=单个摘要按钮。
            // 关键：摘要按钮(CompactFilter)放在「被监听的两行容器之外」，
            // 这样它获焦触发展开时不会污染门闩 filtersTouched。
            Box(modifier = Modifier.animateContentSize()) {
                if (filtersExpanded) {
                    Column(
                        modifier = Modifier.onFocusChanged {
                            // 焦点真正落入筛选区 → 门闩置位
                            if (it.hasFocus) filtersTouched = true
                        },
                    ) {
                        // 收藏开关（独立视图：忽略城市/类型筛选，展示全部收藏台）
                        FavoriteFilterChip(
                            active = state.showFavorites,
                            onClick = {
                                if (state.showFavorites) viewModel.hideFavoritesView()
                                else viewModel.showFavoritesView()
                            },
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                            focusRequester = favoriteFocusRequester,
                        )

                        // 收藏视图为独立视图，隐藏城市/类型两行，避免误以为可叠加筛选
                        if (!state.showFavorites) {
                            Text(
                                text = "城市",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                            )
                            FilterRow(
                                items = state.provinces.map {
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
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                            )
                            FilterRow(
                                items = state.categories.map { FilterItem(it.id, it.categoryName) },
                                selectedKey = state.selectedCategoryId,
                                onSelect = { viewModel.selectCategory(it) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    CompactFilter(
                        cityName = currentProvinceName(state),
                        typeName = currentCategoryName(state),
                        favoritesActive = state.showFavorites,
                        onActivate = { filtersExpanded = true },
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }

            // 电台 Grid / 状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 12.dp)
                    .onFocusChanged {
                        // 焦点进入电台列表，且筛选区此前已真正承接过焦点
                        // （门闩置位）→ 折叠筛选栏。门闩可滤除展开瞬间的焦点漂移。
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
                    !state.showFavorites && state.isLoadingChannels && state.channels.isEmpty() -> {
                        StatusText("加载中…")
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
                            // 仅保留聚焦放大（1.05x）所需的最小内边距，避免边缘卡片被裁切
                            contentPadding = PaddingValues(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(
                                state.displayedChannels,
                                key = { _, channel -> channel.contentId },
                            ) { index, channel ->
                                ChannelCard(
                                    channel = channel,
                                    isCurrent = state.currentChannel?.contentId == channel.contentId,
                                    isFavorite = state.favoriteIds.contains(channel.contentId),
                                    onClick = { viewModel.playChannel(channel) },
                                    onLongClick = { viewModel.toggleFavorite(channel) },
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

    // 退出确认弹窗：确定 → 结束 Activity 退出应用；取消/返回 → 关闭弹窗
    if (showExitDialog) {
        ExitConfirmDialog(
            onConfirm = {
                showExitDialog = false
                (context as? Activity)?.finish()
            },
            onDismiss = { showExitDialog = false },
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
