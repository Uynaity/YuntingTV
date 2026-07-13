package cn.radio.tv.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import cn.radio.tv.BuildConfig
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.prefs.UserPreferences
import cn.radio.tv.data.remote.NetworkModule
import cn.radio.tv.data.remote.UpdateApp
import cn.radio.tv.data.source.QingTingSource
import cn.radio.tv.data.source.RadioSource
import cn.radio.tv.data.source.RadioSourceType
import cn.radio.tv.data.source.YunTingSource
import cn.radio.tv.data.update.UpdateInstaller
import cn.radio.tv.player.PlaybackBridge
import cn.radio.tv.player.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.milliseconds

/** 整个广播界面的 UI 状态。 */
data class RadioUiState(
    val selectedSource: RadioSourceType = RadioSourceType.DEFAULT,
    val provinces: List<Province> = emptyList(),
    val categories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val selectedProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE,
    val selectedCategoryId: String = UserPreferences.DEFAULT_CATEGORY_ID,
    val homeCityCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE,
    val autoPlayLast: Boolean = UserPreferences.DEFAULT_AUTO_PLAY,
    val currentChannel: Channel? = null,
    /** 正在播放电台所属来源（可能与浏览来源不同：跨源收藏台原地播放时）。 */
    val playingSource: RadioSourceType = RadioSourceType.DEFAULT,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 断网恢复中剩余倒计时秒数;0 表示非恢复态(普通缓冲不显示倒计时)。 */
    val retrySeconds: Int = 0,
    /** 睡眠定时剩余分钟数;0 表示未设定定时。到点自动暂停播放。 */
    val sleepTimerRemainingMinutes: Int = 0,
    /** 睡眠定时本次设定的总分钟数;0 表示未设定。供按钮环形进度算比例（剩余/总）。 */
    val sleepTimerTotalMinutes: Int = 0,
    val isLoadingChannels: Boolean = false,
    val isLoadingFilters: Boolean = true,
    val error: String? = null,
    val favorites: List<FavoriteChannel> = emptyList(),
    val showFavorites: Boolean = false,
    val isRefreshingFavorites: Boolean = false,
    val showPlaybill: Boolean = false,
    val playbillDates: List<PlaybillDate> = emptyList(),
    val selectedPlaybillDate: Long = 0L,
    val playbillPrograms: List<Program> = emptyList(),
    val isLoadingPlaybill: Boolean = false,
    val playbillError: String? = null,
    /** 正在回放的节目名；非空时面板副标题显示它。直播播放时清空。 */
    val playingProgramTitle: String? = null,
) {
    /**
     * 当前来源已收藏电台的 contentId 集合，用于 Grid 卡片星标。
     * 仅取当前来源的收藏：避免两源 contentId 偶然相同时把别源收藏误标到当前列表。
     * 惰性计算：isPlaying/isBuffering 等频繁 copy() 不触发重建，仅首次访问时构建；
     * 被 StateFlow 合并丢弃、从未渲染的中间态则完全不计算。
     * 仅在主线程（Compose 重组）读取，故用 NONE 模式免同步开销。
     */
    val favoriteIds: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        favorites.asSequence()
            .filter { it.source == selectedSource }
            .mapTo(HashSet()) { it.channel.contentId }
    }

    /**
     * 正在播放电台是否已收藏（按其自身来源 [playingSource] 判断），供 PlayerPanel 星标。
     * 与 [favoriteIds] 区别：跨源原地播放时，正在播放台属别源，仍应正确显示星标。
     */
    val currentIsFavorite: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        val cur = currentChannel ?: return@lazy false
        favorites.any { it.source == playingSource && it.channel.contentId == cur.contentId }
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
     * 未设定（全部）或城市不在列表中则保持原序。惰性计算，理由同上。
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

/** 节目单左列的一个可选日期：[dayStartMillis]=当地 00:00 epoch ms，[label]=今天/明天/M-d 周X。 */
data class PlaybillDate(val dayStartMillis: Long, val label: String)

/**
 * 播放进度（独立于 [RadioUiState]，避免 500ms 高频刷新触发大状态 copy 与 Grid 重组）。
 * [durationMs]=0 表示未知/隐藏进度条；[seekable] 仅回放为真（直播不可拖动）。
 */
data class ProgressState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val seekable: Boolean = false,
)

/** 检查更新状态。[Available] 携带新版信息与下载进度，驱动更新弹窗。 */
sealed interface UpdateState {
    data object None : UpdateState
    data class Available(
        val app: UpdateApp,
        val downloading: Boolean = false,
        val progress: Float = 0f,
    ) : UpdateState
}

/** 手动检查的一次性提示事件（自动检查静默，不发事件）。 */
enum class UpdateEvent { UpToDate, Failed }

/** 直播进度条无节目窗口时的回退总时长（一天）。 */
private const val DAY_MILLIS = 86_400_000L

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UserPreferences(app)

    /**
     * 播放器现由 [PlaybackService] 独占持有（支持后台播放），UI/VM 经 MediaController 连接控制。
     * 连接异步：连上前 [controllerState] 为 null，播放入口通过 [controller] 挂起等待就绪。
     */
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(
            app,
            SessionToken(app, ComponentName(app, PlaybackService::class.java))
        )
            .buildAsync()
    private val controllerState = MutableStateFlow<MediaController?>(null)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            recomputeBuffering()
        }
    }

    private val sources: Map<RadioSourceType, RadioSource> = mapOf(
        RadioSourceType.YUNTING to YunTingSource(),
        RadioSourceType.QINGTING to QingTingSource(),
    )

    private val _uiState = MutableStateFlow(RadioUiState())
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(ProgressState())
    val progress: StateFlow<ProgressState> = _progress.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.None)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _updateEvents = MutableSharedFlow<UpdateEvent>()
    val updateEvents: SharedFlow<UpdateEvent> = _updateEvents.asSharedFlow()

    private var downloadJob: Job? = null

    private var liveWindowStart = 0L
    private var liveWindowEnd = 0L

    private var resolvingLive = false

    private val currentSource: RadioSourceType get() = _uiState.value.selectedSource
    private fun activeSource(): RadioSource = sources.getValue(currentSource)

    private suspend fun controller(): MediaController = controllerState.filterNotNull().first()

    /** isBuffering = 播放器缓冲态 或 断流恢复中（retrySeconds>0），二者任一即显示缓冲。 */
    private fun recomputeBuffering() {
        val buffering = controllerState.value?.playbackState == Player.STATE_BUFFERING ||
                PlaybackBridge.retrySeconds.value > 0
        if (buffering != _uiState.value.isBuffering) _uiState.update { it.copy(isBuffering = buffering) }
    }

    /**
     * 通用播放入口：用给定地址与元数据构造 MediaItem 并起播（供直播与回放共用）。
     * title/artist/artworkUri 供媒体通知渲染。
     */
    private suspend fun playUrl(url: String, title: String, artist: String, art: String) {
        val c = controller()
        c.setMediaItem(mediaItemOf(url, title, artist, art))
        c.prepare()
        c.play()
    }

    /** 构造带媒体元数据的 MediaItem：title/artist/art 供系统媒体卡片/通知渲染。 */
    private fun mediaItemOf(url: String, title: String, artist: String, art: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(art.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build(),
            )
            .build()

    /** 加载并从头播放一个电台（直播）：title=电台名、artist=当前节目、封面=电台封面。 */
    private suspend fun playNow(channel: Channel) {
        playUrl(channel.playUrlLow, channel.title, channel.subtitle, channel.image)
        resolveLiveWindow(channel)
    }

    /**
     * 刷新一次进度状态。回放：读 player 的 position/duration（可拖动）；
     * 直播：按当前节目窗口 + 墙钟计算（不可拖动），窗口未知时回退当天 24h。
     */
    private fun updateProgress(c: MediaController) {
        val state = _uiState.value
        if (state.currentChannel == null) {
            _progress.value = ProgressState()
            return
        }
        if (state.playingProgramTitle != null) {
            val dur = c.duration.takeIf { it > 0 } ?: 0L
            _progress.value = ProgressState(
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = dur,
                seekable = dur > 0,
            )
        } else {
            val now = System.currentTimeMillis()
            if (liveWindowEnd in 1..now && !resolvingLive) {
                resolveLiveWindow(state.currentChannel)
                refreshPrograms()
            }
            val start = if (liveWindowEnd > liveWindowStart) liveWindowStart else dayStartMillis(0)
            val end = if (liveWindowEnd > liveWindowStart) liveWindowEnd else start + DAY_MILLIS
            _progress.value = ProgressState(
                positionMs = (now - start).coerceIn(0L, end - start),
                durationMs = end - start,
                seekable = false,
            )
        }
    }

    /** 后台解析当前直播节目窗口；失败/未覆盖置 0（ticker 回退 24h）。 */
    private fun resolveLiveWindow(channel: Channel) {
        resolvingLive = true
        viewModelScope.launch {
            val win = runCatching {
                sources.getValue(_uiState.value.playingSource)
                    .currentProgramWindow(channel, dayStartMillis(0))
            }.getOrNull()
            liveWindowStart = win?.first ?: 0L
            liveWindowEnd = win?.last ?: 0L
            resolvingLive = false
        }
    }

    /** 拖动定位（仅回放）：目标位置夹到 [0, duration] 后 seek，播放器随即进入缓冲加载。 */
    fun seekTo(positionMs: Long) {
        viewModelScope.launch {
            val c = controller()
            val dur = c.duration.takeIf { it > 0 } ?: return@launch
            c.seekTo(positionMs.coerceIn(0L, dur))
        }
    }

    private var playingProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE

    /**
     * 已加载到播放器的地址;null 表示尚未加载。
     * 用于「记忆但不自动播放」场景:启动续播关闭时仅把上次电台设为当前(不加载),
     * 待用户首次按下播放键再真正加载,避免无谓缓冲。
     */
    private var loadedUrl: String? = null

    private var sleepTimerJob: Job? = null

    private var playbillToken = 0

    init {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            controller.addListener(playerListener)
            controllerState.value = controller
            _uiState.update { it.copy(isPlaying = controller.isPlaying) }
            recomputeBuffering()
        }, ContextCompat.getMainExecutor(getApplication()))
        viewModelScope.launch {
            val c = controller()
            while (isActive) {
                updateProgress(c)
                delay(500.milliseconds)
            }
        }
        viewModelScope.launch {
            PlaybackBridge.retrySeconds.collect { seconds ->
                _uiState.update { it.copy(retrySeconds = seconds) }
                recomputeBuffering()
            }
        }
        viewModelScope.launch {
            combine(
                prefs.favorites(RadioSourceType.YUNTING),
                prefs.favorites(RadioSourceType.QINGTING),
            ) { yunting, qingting -> yunting + qingting }
                .collect { favs -> _uiState.update { it.copy(favorites = favs) } }
        }
        viewModelScope.launch {
            prefs.autoPlayLast.distinctUntilChanged()
                .collect { enabled -> _uiState.update { it.copy(autoPlayLast = enabled) } }
        }
        viewModelScope.launch {
            prefs.selectedSource.distinctUntilChanged()
                .flatMapLatest { prefs.homeCity(it, sources.getValue(it).defaultProvinceCode) }
                .distinctUntilChanged()
                .collect { code -> _uiState.update { it.copy(homeCityCode = code) } }
        }
        viewModelScope.launch {
            var first = true
            prefs.selectedSource.distinctUntilChanged().collect { source ->
                val autoStart = first && prefs.autoPlayLast.first()
                first = false
                loadSource(source, autoStart)
            }
        }
        checkForUpdate(manual = false)
    }

    /**
     * 切换到某来源并完整重载：重置列表/筛选/收藏视图，按该源所在城市设默认筛选，
     * 续播或仅展示其上次播放电台，再拉取筛选项与电台列表。
     */
    private suspend fun loadSource(source: RadioSourceType, autoStart: Boolean) {
        val home = prefs.homeCity(source, sources.getValue(source).defaultProvinceCode).first()
        _uiState.update {
            it.copy(
                selectedSource = source,
                provinces = emptyList(),
                categories = emptyList(),
                channels = emptyList(),
                selectedProvinceCode = home,
                selectedCategoryId = UserPreferences.DEFAULT_CATEGORY_ID,
                homeCityCode = home,
                showFavorites = false,
                isLoadingFilters = true,
                isLoadingChannels = true,
                error = null,
            )
        }

        if (loadedUrl == null) {
            val last = prefs.lastPlayed().first()
            playingProvinceCode = last?.provinceCode ?: UserPreferences.DEFAULT_PROVINCE_CODE
            _uiState.update {
                it.copy(currentChannel = last?.channel, playingSource = last?.source ?: source)
            }
            if (autoStart && last != null) {
                playNow(last.channel)
                loadedUrl = last.channel.playUrlLow
            }
            if (last != null) refreshPrograms()
        }

        val src = sources.getValue(source)
        try {
            val (provinces, categories) = coroutineScope {
                val provincesDeferred = async { src.fetchProvinces() }
                val categoriesDeferred = async { src.fetchCategories() }
                provincesDeferred.await() to categoriesDeferred.await()
            }
            _uiState.update {
                it.copy(provinces = provinces, categories = categories, isLoadingFilters = false)
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoadingFilters = false,
                    error = e.message ?: "加载筛选项失败"
                )
            }
        }
        loadChannels()
    }

    /** 切换电台来源（设置页调用）；写入偏好后由上方 collector 驱动重载。 */
    fun setSource(source: RadioSourceType) {
        if (source == currentSource) return
        viewModelScope.launch { prefs.saveSelectedSource(source) }
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
            val refreshed: List<Channel>? = if (state.showFavorites) {
                if (state.favorites.isNotEmpty()) runCatching { refreshFavoritesGrouped(state.favorites) }
                null
            } else {
                runCatching {
                    activeSource().fetchChannels(
                        categoryId = state.selectedCategoryId,
                        provinceCode = state.selectedProvinceCode,
                    )
                }.getOrNull()?.also { latest ->
                    _uiState.update { it.copy(channels = latest) }
                }
            }

            val cur = _uiState.value.currentChannel ?: return@launch
            val latestSubtitle = refreshed
                ?.takeIf { state.playingSource == state.selectedSource }
                ?.firstOrNull { it.contentId == cur.contentId }?.subtitle
                ?: runCatching {
                    sources.getValue(state.playingSource).fetchChannels(
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
            prefs.saveLastPlayed(
                state.playingSource,
                cur.copy(subtitle = latestSubtitle),
                playingProvinceCode,
            )

            if (latestSubtitle != cur.subtitle &&
                loadedUrl != null &&
                _uiState.value.playingProgramTitle == null
            ) {
                val c = controller()
                if (c.currentMediaItem != null) {
                    runCatching {
                        c.replaceMediaItem(
                            c.currentMediaItemIndex,
                            mediaItemOf(cur.playUrlLow, cur.title, latestSubtitle, cur.image),
                        )
                    }
                }
            }
        }
    }

    fun selectProvince(provinceCode: Long) {
        val state = _uiState.value
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

    /**
     * 切换某电台的收藏状态。收藏视图里长按的可能是别源收藏台，需按该收藏项自身来源/城市
     * 路由取消，否则会误写到当前源；普通视图则写入当前来源、记录当前筛选城市。
     */
    fun toggleFavorite(channel: Channel) {
        val state = _uiState.value
        val fav = if (state.showFavorites) {
            state.favorites.firstOrNull { it.channel.contentId == channel.contentId }
        } else null
        val source = fav?.source ?: state.selectedSource
        val provinceCode = fav?.provinceCode ?: state.selectedProvinceCode
        viewModelScope.launch {
            prefs.toggleFavorite(source, channel, provinceCode)
        }
    }

    /** 按城市重新拉取收藏电台并刷新节目单，写回存储。 */
    private fun refreshFavorites() {
        val current = _uiState.value.favorites
        if (current.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingFavorites = true) }
            try {
                refreshFavoritesGrouped(current)
            } catch (_: Exception) {
            } finally {
                _uiState.update { it.copy(isRefreshingFavorites = false) }
            }
        }
    }

    /**
     * 按来源分组刷新收藏节目单并写回各源。收藏已跨两源合并，需各用自身来源接口刷新，
     * 不可混用。写回后由 combine 订阅自动重新合并到 [RadioUiState.favorites]。
     */
    private suspend fun refreshFavoritesGrouped(favorites: List<FavoriteChannel>) {
        favorites.groupBy { it.source }.forEach { (src, list) ->
            val refreshed = sources.getValue(src).refreshFavoritePrograms(list)
            prefs.saveFavorites(src, refreshed)
        }
    }

    /** 设定当前来源的所在城市：仅影响城市筛选栏排序与下次启动默认城市，不改变当前浏览。 */
    fun setHomeCity(provinceCode: Long) {
        val source = currentSource
        viewModelScope.launch { prefs.saveHomeCity(source, provinceCode) }
    }

    /** 设定启动时是否自动播放上次电台。 */
    fun setAutoPlayLast(enabled: Boolean) {
        viewModelScope.launch { prefs.saveAutoPlayLast(enabled) }
    }

    fun loadChannels() {
        val state = _uiState.value
        val src = activeSource()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingChannels = true, error = null) }
            try {
                val channels = src.fetchChannels(
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
        val fav = if (state.showFavorites) {
            state.favorites.firstOrNull { it.channel.contentId == channel.contentId }
        } else {
            state.favorites.firstOrNull {
                it.source == state.selectedSource && it.channel.contentId == channel.contentId
            }
        }
        val playSource = fav?.source ?: state.selectedSource
        playingProvinceCode = fav?.provinceCode ?: state.selectedProvinceCode
        _uiState.update {
            it.copy(
                currentChannel = channel,
                playingSource = playSource,
                playingProgramTitle = null
            )
        }
        loadedUrl = channel.playUrlLow
        viewModelScope.launch { playNow(channel) }
        viewModelScope.launch { prefs.saveLastPlayed(playSource, channel, playingProvinceCode) }
        refreshPrograms()
    }

    fun togglePlayPause() {
        val channel = _uiState.value.currentChannel ?: return
        viewModelScope.launch {
            val c = controller()
            if (loadedUrl == null) {
                playNow(channel)
                loadedUrl = channel.playUrlLow
            } else {
                if (c.playWhenReady) c.pause() else c.play()
            }
        }
    }

    /**
     * 打开/关闭节目单。无正在播放电台时忽略。打开时计算 9 天日期、默认选中今天并加载当天节目。
     */
    fun togglePlaybill() {
        val state = _uiState.value
        if (state.currentChannel == null) return
        if (state.showPlaybill) {
            _uiState.update { it.copy(showPlaybill = false) }
            return
        }
        val today = dayStartMillis(0)
        _uiState.update {
            it.copy(
                showPlaybill = true,
                playbillDates = buildPlaybillDates(),
                selectedPlaybillDate = today,
                playbillPrograms = emptyList(),
                playbillError = null,
            )
        }
        loadPlaybill(today)
    }

    /** 切换节目单选中日期并加载当天节目;同日则忽略。 */
    fun selectPlaybillDate(dayStart: Long) {
        if (dayStart == _uiState.value.selectedPlaybillDate) return
        _uiState.update { it.copy(selectedPlaybillDate = dayStart) }
        loadPlaybill(dayStart)
    }

    /** 按需加载某天节目单;竞态令牌保证只有最新一次请求的结果被采用。 */
    private fun loadPlaybill(dayStart: Long) {
        val state = _uiState.value
        val channel = state.currentChannel ?: return
        val source = state.playingSource
        val token = ++playbillToken
        _uiState.update { it.copy(isLoadingPlaybill = true, playbillError = null) }
        viewModelScope.launch {
            val result = runCatching { sources.getValue(source).fetchPlaybill(channel, dayStart) }
            if (token != playbillToken) return@launch  // 日期已快切,丢弃过期结果
            result.fold(
                onSuccess = { programs ->
                    _uiState.update {
                        it.copy(
                            playbillPrograms = programs,
                            isLoadingPlaybill = false,
                            playbillError = null
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            playbillPrograms = emptyList(),
                            isLoadingPlaybill = false,
                            playbillError = e.message ?: "加载节目单失败",
                        )
                    }
                },
            )
        }
    }

    /** 播放某节目的回放:解析地址(蜻蜓按需二次请求)后接管播放;地址为空则静默不播。 */
    fun playReplay(program: Program) {
        val state = _uiState.value
        val channel = state.currentChannel ?: return
        val source = state.playingSource
        viewModelScope.launch {
            val url = runCatching { sources.getValue(source).resolveReplayUrl(channel, program) }
                .getOrDefault("")
            if (url.isBlank()) return@launch
            playUrl(url, channel.title, program.title, channel.image)
            loadedUrl = url
            _uiState.update { it.copy(playingProgramTitle = program.title, showPlaybill = false) }
        }
    }

    /** 从回放切回直播：重载当前电台直播流、清空回放节目名并关闭节目单。已在直播则忽略。 */
    fun playLive() {
        val channel = _uiState.value.currentChannel ?: return
        if (_uiState.value.playingProgramTitle == null) return
        _uiState.update { it.copy(playingProgramTitle = null, showPlaybill = false) }
        loadedUrl = channel.playUrlLow
        viewModelScope.launch { playNow(channel) }
    }

    /** 归零到某天(今天+[offset]天)本地 00:00:00 的 epoch ms。 */
    private fun dayStartMillis(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offset)
    }.timeInMillis

    /** 9 天日期集合:今天偏移 -7..+1;label = 昨天/今天/明天 或 M-d 周X。 */
    private fun buildPlaybillDates(): List<PlaybillDate> = (-7..1).map { offset ->
        val ms = dayStartMillis(offset)
        val label = when (offset) {
            -1 -> "昨天"
            0 -> "今天"
            1 -> "明天"
            else -> Calendar.getInstance().apply { timeInMillis = ms }.let { c ->
                "${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)} " +
                        WEEK_LABELS[c.get(Calendar.DAY_OF_WEEK) - 1]
            }
        }
        PlaybillDate(ms, label)
    }

    /**
     * 设置睡眠定时:[minutes] 分钟后自动暂停播放;0 表示取消定时。
     * 分钟粒度倒计时,每分钟回写剩余分钟驱动 UI;改档即取消旧协程重开。
     * 计时独立于播放/来源状态:切台、暂停恢复都不影响它。
     */
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _uiState.update { it.copy(sleepTimerRemainingMinutes = 0, sleepTimerTotalMinutes = 0) }
            return
        }
        sleepTimerJob = viewModelScope.launch {
            var left = minutes
            while (left > 0) {
                _uiState.update {
                    it.copy(sleepTimerRemainingMinutes = left, sleepTimerTotalMinutes = minutes)
                }
                delay(60_000L.milliseconds)
                left--
            }
            controller().pause()
            _uiState.update { it.copy(sleepTimerRemainingMinutes = 0, sleepTimerTotalMinutes = 0) }
        }
    }

    /**
     * 检查更新。线上 versionCode > 本地即视为有更新，置 [UpdateState.Available] 驱动弹窗。
     * [manual] 为设置页手动触发：无更新/失败时发一次性事件供 UI toast；自动检查则静默。
     */
    fun checkForUpdate(manual: Boolean) {
        viewModelScope.launch {
            runCatching {
                NetworkModule.yecaoApi.resolve(clientTime = System.currentTimeMillis() / 1000)
            }.onSuccess { resp ->
                val app = resp.data?.apps?.firstOrNull()
                if (app != null && app.versionCode > BuildConfig.VERSION_CODE) {
                    _updateState.value = UpdateState.Available(app)
                } else if (manual) {
                    _updateEvents.emit(UpdateEvent.UpToDate)
                }
            }.onFailure {
                if (manual) _updateEvents.emit(UpdateEvent.Failed)
            }
        }
    }

    /** 下载新版 APK 并调起安装；进度回写 [updateState]。失败发 Failed 事件并关闭弹窗。 */
    fun downloadAndInstall() {
        val current = _updateState.value as? UpdateState.Available ?: return
        if (current.downloading) return
        downloadJob = viewModelScope.launch {
            _updateState.value = current.copy(downloading = true, progress = 0f)
            val url = NetworkModule.YECAO_BASE_URL + current.app.downloadUrl
            val file = runCatching {
                UpdateInstaller.download(getApplication(), url, current.app.size) { p ->
                    (_updateState.value as? UpdateState.Available)?.let {
                        _updateState.value = it.copy(progress = p)
                    }
                }
            }.getOrNull()
            if (file == null) {
                _updateEvents.emit(UpdateEvent.Failed)
                _updateState.value = UpdateState.None
                return@launch
            }
            if (UpdateInstaller.install(getApplication(), file)) {
                _updateState.value = UpdateState.None
            } else {
                _updateState.value = current.copy(downloading = false, progress = 0f)
            }
        }
    }

    /** 关闭更新弹窗（取消/返回）；进行中的下载一并取消。 */
    fun dismissUpdate() {
        downloadJob?.cancel()
        _updateState.value = UpdateState.None
    }

    override fun onCleared() {
        controllerState.value?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
    }

    companion object {
        private const val HALF_HOUR_MILLIS = 30 * 60 * 1000L

        private val WEEK_LABELS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    }
}
