package cn.radio.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import cn.radio.tv.data.RadioRepository
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.prefs.UserPreferences
import cn.radio.tv.player.RadioPlayer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** 整个广播界面的 UI 状态。 */
data class RadioUiState(
    val provinces: List<Province> = emptyList(),
    val categories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val selectedProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE,
    val selectedCategoryId: String = UserPreferences.DEFAULT_CATEGORY_ID,
    // 设置项：所在城市与启动自动播放（驱动设置页 UI 与城市筛选栏排序）
    val homeCityCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE,
    val autoPlayLast: Boolean = UserPreferences.DEFAULT_AUTO_PLAY,
    val currentChannel: Channel? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 断网恢复中剩余倒计时秒数;0 表示非恢复态(普通缓冲不显示倒计时)。 */
    val retrySeconds: Int = 0,
    val isLoadingChannels: Boolean = false,
    val isLoadingFilters: Boolean = true,
    val error: String? = null,
    // 收藏
    val favorites: List<FavoriteChannel> = emptyList(),
    val showFavorites: Boolean = false,
    val isRefreshingFavorites: Boolean = false,
) {
    /**
     * 已收藏电台的 contentId 集合，用于星标标记。
     * 惰性计算：isPlaying/isBuffering 等频繁 copy() 不触发重建，仅首次访问时构建；
     * 被 StateFlow 合并丢弃、从未渲染的中间态则完全不计算。
     * 仅在主线程（Compose 重组）读取，故用 NONE 模式免同步开销。
     */
    val favoriteIds: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        favorites.mapTo(HashSet()) { it.channel.contentId }
    }

    /**
     * 当前 Grid 应展示的电台：收藏视图为收藏快照，否则为筛选结果。
     * 惰性计算，理由同上。
     */
    val displayedChannels: List<Channel> by lazy(LazyThreadSafetyMode.NONE) {
        if (showFavorites) favorites.map { it.channel } else channels
    }

    /**
     * 城市筛选栏展示用的省份列表：设定了所在城市时将其置顶，其余保持原序；
     * 未设定（国家）或城市不在列表中则保持原序。惰性计算，理由同上。
     */
    val orderedProvinces: List<Province> by lazy(LazyThreadSafetyMode.NONE) {
        val idx = provinces.indexOfFirst { it.provinceCode == homeCityCode }
        if (homeCityCode == UserPreferences.DEFAULT_PROVINCE_CODE || idx <= 0) {
            provinces
        } else {
            buildList(provinces.size) {
                add(provinces[idx])
                provinces.forEachIndexed { i, p -> if (i != idx) add(p) }
            }
        }
    }
}

@UnstableApi
class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RadioRepository()
    private val prefs = UserPreferences(app)
    private val player = RadioPlayer(app)

    private val _uiState = MutableStateFlow(RadioUiState())
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    /** 正在播放电台所属城市,用于在任意视图下单独刷新其节目单。 */
    private var playingProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE

    /**
     * 已加载到播放器的地址;null 表示尚未加载。
     * 用于「记忆但不自动播放」场景:启动续播关闭时仅把上次电台设为当前(不加载),
     * 待用户首次按下播放键再真正加载,避免无谓缓冲。
     */
    private var loadedUrl: String? = null

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
        viewModelScope.launch {
            player.retrySeconds.collect { seconds ->
                _uiState.update { it.copy(retrySeconds = seconds) }
            }
        }
        // 持续同步收藏列表（星标实时更新）
        viewModelScope.launch {
            prefs.favorites.collect { favs ->
                _uiState.update { it.copy(favorites = favs) }
            }
        }
        // 持续同步设置项：所在城市变更后城市筛选栏即时重排（orderedProvinces 依赖之）
        viewModelScope.launch {
            prefs.settings.collect { s ->
                _uiState.update {
                    it.copy(homeCityCode = s.homeCityCode, autoPlayLast = s.autoPlayLast)
                }
            }
        }
        bootstrap()
    }

    /**
     * 计算距下一个整点或半点的毫秒数(范围 (0, 30min])。
     * 供 UI 层的生命周期感知刷新循环(仅前台运行)使用,见 RadioScreen。
     */
    fun millisToNextHalfHour(): Long {
        val cal = Calendar.getInstance()
        val elapsed = (cal.get(Calendar.MINUTE) % 30) * 60_000L +
            cal.get(Calendar.SECOND) * 1_000L +
            cal.get(Calendar.MILLISECOND)
        return HALF_HOUR_MILLIS - elapsed
    }

    /**
     * 静默刷新:重拉电台列表更新展示视图,并单独刷新正在播放电台的节目单。
     * 不显示加载态、不触发重新播放;失败则保留旧数据。
     * 由 UI 层在前台(STARTED)按整点/半点驱动调用,后台不触发。
     */
    fun refreshPrograms() {
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

    /** 加载筛选项 + 按所在城市决定默认筛选 + 自动续播 + 拉取电台。 */
    private fun bootstrap() {
        viewModelScope.launch {
            // 启动不再记忆上次退出时的筛选：默认城市取「所在城市」（未设定则为国家），
            // 分类恒为「全部」。
            val settings = prefs.settings.first()
            _uiState.update {
                it.copy(
                    selectedProvinceCode = settings.homeCityCode,
                    selectedCategoryId = UserPreferences.DEFAULT_CATEGORY_ID,
                    homeCityCode = settings.homeCityCode,
                    autoPlayLast = settings.autoPlayLast,
                    isLoadingFilters = true,
                    error = null,
                )
            }
            // 始终记忆上次电台:无论是否自动播放,都将其设为「当前电台」展示在播放器面板。
            // 仅当开启自动播放时立即加载并出声;否则只展示,待用户首次按播放键再加载(见 togglePlayPause)。
            prefs.lastPlayed.first()?.let { last ->
                playingProvinceCode = last.provinceCode
                _uiState.update { it.copy(currentChannel = last.channel) }
                if (settings.autoPlayLast) {
                    player.play(last.channel.playUrlLow)
                    loadedUrl = last.channel.playUrlLow
                }
            }
            try {
                // 省份与分类相互独立,并行拉取以缩短首屏等待(原为两次串行往返)。
                val (provinces, categories) = coroutineScope {
                    val provincesDeferred = async { repository.fetchProvinces() }
                    val categoriesDeferred = async { repository.fetchCategories() }
                    provincesDeferred.await() to categoriesDeferred.await()
                }
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
        loadChannels()
    }

    fun selectCategory(categoryId: String) {
        val state = _uiState.value
        if (categoryId == state.selectedCategoryId) {
            if (state.showFavorites) _uiState.update { it.copy(showFavorites = false) }
            return
        }
        _uiState.update { it.copy(selectedCategoryId = categoryId, showFavorites = false) }
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

    /** 设定所在城市：仅影响城市筛选栏排序与下次启动默认城市，不改变当前浏览。 */
    fun setHomeCity(provinceCode: Long) {
        viewModelScope.launch { prefs.saveHomeCity(provinceCode) }
    }

    /** 设定启动时是否自动播放上次电台。 */
    fun setAutoPlayLast(enabled: Boolean) {
        viewModelScope.launch { prefs.saveAutoPlayLast(enabled) }
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
        loadedUrl = channel.playUrlLow
        // 记忆为「上次播放」，供下次启动记忆/续播
        viewModelScope.launch { prefs.saveLastPlayed(channel, playingProvinceCode) }
    }

    fun togglePlayPause() {
        val channel = _uiState.value.currentChannel ?: return
        // 记忆但未自动播放的电台首次按播放键:此时才真正加载并播放。
        if (loadedUrl != channel.playUrlLow) {
            player.play(channel.playUrlLow)
            loadedUrl = channel.playUrlLow
        } else {
            player.togglePlayPause()
        }
    }

    override fun onCleared() {
        player.release()
    }

    companion object {
        private const val HALF_HOUR_MILLIS = 30 * 60 * 1000L
    }
}
