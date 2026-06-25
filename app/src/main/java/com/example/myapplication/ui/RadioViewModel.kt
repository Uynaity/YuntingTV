package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.RadioRepository
import com.example.myapplication.data.model.Category
import com.example.myapplication.data.model.Channel
import com.example.myapplication.data.model.FavoriteChannel
import com.example.myapplication.data.model.Province
import com.example.myapplication.data.prefs.UserPreferences
import com.example.myapplication.player.RadioPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 整个广播界面的 UI 状态。 */
data class RadioUiState(
    val provinces: List<Province> = emptyList(),
    val categories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val selectedProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE,
    val selectedCategoryId: String = UserPreferences.DEFAULT_CATEGORY_ID,
    val currentChannel: Channel? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLoadingChannels: Boolean = false,
    val isLoadingFilters: Boolean = true,
    val error: String? = null,
    // 收藏
    val favorites: List<FavoriteChannel> = emptyList(),
    val showFavorites: Boolean = false,
    val isRefreshingFavorites: Boolean = false,
) {
    /** 已收藏电台的 contentId 集合，用于星标标记。 */
    val favoriteIds: Set<String> = favorites.mapTo(HashSet()) { it.channel.contentId }

    /** 当前 Grid 应展示的电台：收藏视图为收藏快照，否则为筛选结果。 */
    val displayedChannels: List<Channel> =
        if (showFavorites) favorites.map { it.channel } else channels
}

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RadioRepository()
    private val prefs = UserPreferences(app)
    private val player = RadioPlayer(app)

    private val _uiState = MutableStateFlow(RadioUiState())
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    init {
        // 同步播放器状态
        viewModelScope.launch {
            player.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlaying = playing) }
            }
        }
        viewModelScope.launch {
            player.isBuffering.collect { buffering ->
                _uiState.update { it.copy(isBuffering = buffering) }
            }
        }
        // 持续同步收藏列表（星标实时更新）
        viewModelScope.launch {
            prefs.favorites.collect { favs ->
                _uiState.update { it.copy(favorites = favs) }
            }
        }
        bootstrap()
    }

    /** 加载筛选项 + 恢复上次选择 + 拉取电台。 */
    private fun bootstrap() {
        viewModelScope.launch {
            val saved = prefs.selection.first()
            _uiState.update {
                it.copy(
                    selectedProvinceCode = saved.provinceCode,
                    selectedCategoryId = saved.categoryId,
                    isLoadingFilters = true,
                    error = null,
                )
            }
            try {
                val provinces = repository.fetchProvinces()
                val categories = repository.fetchCategories()
                _uiState.update {
                    it.copy(
                        provinces = provinces,
                        categories = categories,
                        isLoadingFilters = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoadingFilters = false, error = e.message ?: "加载筛选项失败")
                }
            }
            loadChannels()
        }
    }

    fun selectProvince(provinceCode: Long) {
        val state = _uiState.value
        // 选择城市离开收藏视图；若城市未变则只需切回普通视图，不必重新请求。
        if (provinceCode == state.selectedProvinceCode) {
            if (state.showFavorites) _uiState.update { it.copy(showFavorites = false) }
            return
        }
        _uiState.update { it.copy(selectedProvinceCode = provinceCode, showFavorites = false) }
        persistSelection()
        loadChannels()
    }

    fun selectCategory(categoryId: String) {
        val state = _uiState.value
        if (categoryId == state.selectedCategoryId) {
            if (state.showFavorites) _uiState.update { it.copy(showFavorites = false) }
            return
        }
        _uiState.update { it.copy(selectedCategoryId = categoryId, showFavorites = false) }
        persistSelection()
        loadChannels()
    }

    /** 打开收藏视图，并按城市重新拉取以刷新各收藏电台的节目单。 */
    fun showFavoritesView() {
        _uiState.update { it.copy(showFavorites = true) }
        refreshFavorites()
    }

    /** 退出收藏视图，回到当前筛选结果。 */
    fun hideFavoritesView() {
        if (_uiState.value.showFavorites) _uiState.update { it.copy(showFavorites = false) }
    }

    /** 切换某电台的收藏状态；新增收藏时记录其当前所在城市。 */
    fun toggleFavorite(channel: Channel) {
        val provinceCode = _uiState.value.selectedProvinceCode
        viewModelScope.launch {
            prefs.toggleFavorite(channel, provinceCode)
        }
    }

    /** 按城市重新拉取收藏电台并刷新节目单，写回存储。 */
    private fun refreshFavorites() {
        val current = _uiState.value.favorites
        if (current.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingFavorites = true) }
            try {
                val refreshed = repository.refreshFavoritePrograms(current)
                prefs.saveFavorites(refreshed) // 触发 favorites Flow 更新 UI
            } catch (_: Exception) {
                // 刷新失败保留旧快照，不打断收藏浏览
            } finally {
                _uiState.update { it.copy(isRefreshingFavorites = false) }
            }
        }
    }

    private fun persistSelection() {
        val s = _uiState.value
        viewModelScope.launch {
            prefs.saveSelection(s.selectedProvinceCode, s.selectedCategoryId)
        }
    }

    fun loadChannels() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingChannels = true, error = null) }
            try {
                val channels = repository.fetchChannels(
                    categoryId = state.selectedCategoryId,
                    provinceCode = state.selectedProvinceCode,
                )
                _uiState.update { it.copy(channels = channels, isLoadingChannels = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoadingChannels = false, error = e.message ?: "加载电台失败")
                }
            }
        }
    }

    /** 选中并播放一个电台。 */
    fun playChannel(channel: Channel) {
        _uiState.update { it.copy(currentChannel = channel) }
        player.play(channel.playUrlLow)
    }

    fun togglePlayPause() {
        if (_uiState.value.currentChannel == null) return
        player.togglePlayPause()
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
