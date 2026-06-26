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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.milliseconds

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

    /** 正在播放电台所属城市,用于在任意视图下单独刷新其节目单。 */
    private var playingProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE

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
        startProgramAutoRefresh()
    }

    /** 每到整点 / 半点(:00、:30)静默刷新当前视图数据,使节目单保持最新。 */
    private fun startProgramAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(millisToNextHalfHour().milliseconds)
                refreshPrograms()
            }
        }
    }

    /** 计算距下一个整点或半点的毫秒数(范围 (0, 30min])。 */
    private fun millisToNextHalfHour(): Long {
        val cal = Calendar.getInstance()
        val elapsed = (cal.get(Calendar.MINUTE) % 30) * 60_000L +
            cal.get(Calendar.SECOND) * 1_000L +
            cal.get(Calendar.MILLISECOND)
        return HALF_HOUR_MILLIS - elapsed
    }

    /**
     * 静默刷新:重拉电台列表更新展示视图,并单独刷新正在播放电台的节目单。
     * 不显示加载态、不触发重新播放;失败则保留旧数据。
     */
    private fun refreshPrograms() {
        val state = _uiState.value
        viewModelScope.launch {
            // 1. 刷新当前展示视图;返回刷新后的电台数据(供下方复用,避免重复请求)。
            val refreshed: List<Channel>? = if (state.showFavorites) {
                if (state.favorites.isEmpty()) null
                else runCatching { repository.refreshFavoritePrograms(state.favorites) }
                    .getOrNull()
                    ?.also { prefs.saveFavorites(it) } // 触发 favorites Flow 更新 UI
                    ?.map { it.channel }
            } else {
                runCatching {
                    repository.fetchChannels(
                        categoryId = state.selectedCategoryId,
                        provinceCode = state.selectedProvinceCode,
                    )
                }.getOrNull()?.also { latest ->
                    _uiState.update { it.copy(channels = latest) }
                }
            }

            // 2. 单独更新正在播放电台的节目单:优先用刚刷新的数据,
            //    若其不在当前视图(如已切换城市浏览),则按其所属城市单独拉取。
            val cur = _uiState.value.currentChannel ?: return@launch
            val latestSubtitle = refreshed?.firstOrNull { it.contentId == cur.contentId }?.subtitle
                ?: runCatching {
                    repository.fetchChannels(
                        categoryId = UserPreferences.DEFAULT_CATEGORY_ID,
                        provinceCode = playingProvinceCode,
                    )
                }.getOrNull()?.firstOrNull { it.contentId == cur.contentId }?.subtitle
                ?: return@launch

            _uiState.update { s ->
                val c = s.currentChannel ?: return@update s
                if (c.contentId == cur.contentId) {
                    s.copy(currentChannel = c.copy(subtitle = latestSubtitle))
                } else s
            }
        }
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
        val state = _uiState.value
        // 记录所属城市:收藏台用其收藏时的城市,否则用当前筛选城市。
        playingProvinceCode = state.favorites
            .firstOrNull { it.channel.contentId == channel.contentId }
            ?.provinceCode
            ?: state.selectedProvinceCode
        _uiState.update { it.copy(currentChannel = channel) }
        player.play(channel.playUrlLow)
    }

    fun togglePlayPause() {
        if (_uiState.value.currentChannel == null) return
        player.togglePlayPause()
    }

    override fun onCleared() {
        player.release()
    }

    companion object {
        private const val HALF_HOUR_MILLIS = 30 * 60 * 1000L
    }
}
