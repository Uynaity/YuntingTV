package cn.radio.tv.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import cn.radio.tv.BuildConfig
import cn.radio.tv.data.browse.BrowseQuery
import cn.radio.tv.data.browse.ChannelRepository
import cn.radio.tv.data.browse.QueryRequest
import cn.radio.tv.data.browse.toBrowseQueries
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.prefs.UserPreferences
import cn.radio.tv.data.program.ProgramRepository
import cn.radio.tv.data.remote.NetworkModule
import cn.radio.tv.data.remote.UpdateApp
import cn.radio.tv.data.source.BEIJING_TIME_ZONE
import cn.radio.tv.data.source.GatewaySource
import cn.radio.tv.data.source.RadioSource
import cn.radio.tv.data.source.RadioSourceType
import cn.radio.tv.data.update.UpdateInstaller
import cn.radio.tv.player.ConnectionState
import cn.radio.tv.player.PlaybackBridge
import cn.radio.tv.player.PlaybackConnection
import cn.radio.tv.player.PlaybackIntent
import cn.radio.tv.player.PlaybackService
import cn.radio.tv.player.mediaControllerConnection
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.milliseconds

/** 整个广播界面的 UI 状态。 */
data class RadioUiState(
    val selectedSource: RadioSourceType = RadioSourceType.DEFAULT,
    val provinces: List<Province> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE,
    val selectedCategoryId: String = UserPreferences.DEFAULT_CATEGORY_ID,
    val homeCityCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE,
    val autoPlayLast: Boolean = UserPreferences.DEFAULT_AUTO_PLAY,
    val autoFullscreen: Boolean = UserPreferences.DEFAULT_AUTO_FULLSCREEN,
    val currentChannel: Channel? = null,
    /** 正在播放电台所属来源（可能与浏览来源不同：跨源收藏台原地播放时）。 */
    val playingSource: RadioSourceType = RadioSourceType.DEFAULT,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 断网恢复中剩余倒计时秒数;0 表示非恢复态(普通缓冲不显示倒计时)。 */
    val retrySeconds: Int = 0,
    /**
     * 播放器不可用时的提示；null 表示正常。
     * 与 [error]（列表加载错误）分开：播放器连不上不该让电台列表显示错误，反之亦然 ——
     * 这两条链路的解耦正是本次重构的核心。
     */
    val playerError: String? = null,
    /** 睡眠定时剩余分钟数;0 表示未设定定时。到点自动暂停播放。 */
    val sleepTimerRemainingMinutes: Int = 0,
    /** 睡眠定时本次设定的总分钟数;0 表示未设定。供按钮环形进度算比例（剩余/总）。 */
    val sleepTimerTotalMinutes: Int = 0,
    val isLoadingFilters: Boolean = true,
    /**
     * 筛选项加载失败的提示。列表自身的加载中/失败/翻页状态不在这里 ——
     * 它们是 Paging 的 `LoadState`，由 UI 直接读 `LazyPagingItems.loadState`。
     */
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
    /** 搜索界面是否打开（横屏显示自绘键盘，竖屏显示系统输入法搜索栏）。 */
    val searchActive: Boolean = false,
    val searchQuery: String = "",
) {
    /**
     * 搜索态且已输入内容：此时右侧列表展示的是搜索结果而非筛选结果。
     * 二者是同一条分页流的不同查询（见 [BrowseQuery]），这里只用于选空态文案与结果计数口径。
     */
    val showingSearchResults: Boolean get() = searchActive && searchQuery.isNotBlank()

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

/**
 * LazyGrid 的稳定唯一 key：来源 + 频道 ID 复合。
 *
 * 三个来源的 contentId 空间彼此独立、不保证全局唯一。收藏视图会把跨来源的电台合并展示，
 * 只用裸 [Channel.contentId] 当 key 有两个后果：相同 ID 的两个台一旦同时被收藏，
 * Compose 因重复 key 直接崩溃；播放/取消收藏按裸 ID 反查来源还会路由到错的源。
 * 故来源与频道必须始终成对传递 —— 分页列表与收藏列表都走这一个函数。
 */
fun gridKeyOf(source: RadioSourceType, channel: Channel): String =
    "${source.key}:${channel.contentId}"

/** 节目单左列的一个可选日期：[dayStartMillis]=北京时间 00:00 epoch ms，[label]=今天/明天/M-d 周X。 */
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

/** 直播窗口未知时重试解析的最小间隔：节目切换后给后端一点时间更新下一档，同时避免高频空拉。 */
private const val LIVE_RESOLVE_MIN_INTERVAL_MS = 30_000L

/** 停手多久才发搜索请求。方向键键盘上逐字挪动本就慢，1 秒足以避免每键都打一次服务端。 */
private const val SEARCH_DEBOUNCE_MS = 1000L

/** 进度刷新间隔。 */
private const val PROGRESS_TICK_MS = 500L

/**
 * 无人订阅进度多久后停掉 ticker。留几秒余量，避免旋转屏幕等短暂重订阅期间反复起停。
 */
private const val PROGRESS_STOP_DELAY_MS = 5_000L

/**
 * 播放器连接失败/超时时的用户可见提示。
 * 低端 TV 上服务绑定或 ExoPlayer 初始化确实可能失败；此时明确告知并保持可重试，
 * 而不是让播放键按下去毫无反应（旧实现在这种情况下会永久挂起）。
 */
private const val PLAYER_UNAVAILABLE = "播放器暂时不可用，请重试"

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UserPreferences(app)

    /**
     * 播放器现由 [PlaybackService] 独占持有（支持后台播放），UI/VM 经 MediaController 连接控制。
     *
     * 连接是**按需**的：冷启动不再无条件绑定服务，只有确实要播（自动续播 / 用户点播 /
     * 播放控制）时才连。连接失败是一等状态而非异常 —— [controller] 返回 null 而不是永久
     * 挂起，调用方各自降级。详见 [PlaybackConnection]。
     */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val connection: PlaybackConnection<MediaController> =
        mediaControllerConnection(app, viewModelScope) { controllerFuture = it }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            recomputeBuffering()
        }
    }

    // 三来源统一经网关取数：每个 type 装一个轻量 GatewaySource（仅各持一枚举，共享同一 gatewayApi）。
    // 路由代码 sources.getValue(type) 照旧。
    private val sources: Map<RadioSourceType, RadioSource> =
        RadioSourceType.entries.associateWith { GatewaySource(it) }

    private val _uiState = MutableStateFlow(RadioUiState())
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    private val channelRepository = ChannelRepository { sources.getValue(it) }

    /**
     * 节目单取数的唯一入口。节目单面板与直播进度条的当前节目窗口要的是同一份数据，
     * 经它键控去重、短期复用并统一取消语义，不再各自直连数据源各发一次。
     */
    private val programs = ProgramRepository({ sources.getValue(it) })

    /** null = 尚未确定首个查询（要等 [loadSource] 读出该来源的所在城市），此时不发请求。 */
    private val queryRequests = MutableStateFlow<QueryRequest?>(null)

    /**
     * contentId → 最新副标题（当前节目），由半点静默刷新写入，叠加到分页数据上。
     *
     * 为什么不是 `PagingSource.invalidate()`：invalidate 会用 `getRefreshKey()=null`
     * 从 offset 0 整体重来，已翻出的第 2、3 页当场丢掉、滚动位置被甩到末尾。
     * 副标题刷新只想改一个字段，不该赔上分页进度 —— 故做成叠加层，页与滚动位置都不动。
     * 查询一变即清空（换来源后同一个 contentId 可能是别的台）。
     */
    private val subtitleOverrides = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 频道网格的分页流。收藏视图不走这里（它是本地全量快照，见 [RadioUiState.favorites]）。
     *
     * `cachedIn` 必须在 `combine` **之前**：缓存的是分页本身，叠加副标题只是展示期变换，
     * 顺序反了会让每次副标题更新都重新发一遍网络请求。
     */
    val channels: Flow<PagingData<Channel>> = channelRepository.pagingFlow(
        queryRequests.toBrowseQueries(SEARCH_DEBOUNCE_MS)
            .onEach { query ->
                subtitleOverrides.value = emptyMap()
                _servedQuery.value = query.query
            },
    ).cachedIn(viewModelScope)
        .combine(subtitleOverrides) { data, overrides ->
            if (overrides.isEmpty()) data
            else data.map { ch -> overrides[ch.contentId]?.let { ch.copy(subtitle = it) } ?: ch }
        }

    private val _servedQuery = MutableStateFlow("")

    /**
     * 已提交给分页层的查询词（防抖**之后**）。
     *
     * UI 用它判断「网格上摆着的还是不是用户当前输入对应的内容」：打字后的防抖窗口里，
     * 请求还没发出去，Paging 的 refresh 仍是 NotLoading，但网格上已经是过期内容了。
     * 少了这个判据，敲字后的第一秒既没有整屏 loading 也没有「搜索中…」，界面看着像没反应。
     */
    val servedQuery: StateFlow<String> = _servedQuery.asStateFlow()

    /**
     * 按当前来源/筛选/搜索词提交一次查询。替代原先的 `loadChannels()` + `triggerSearch()`：
     * 浏览与搜索在服务端是同一套 offset/limit 契约，客户端也就只有这一条分页流。
     */
    private fun updateQuery(typed: Boolean) {
        val s = _uiState.value
        queryRequests.value = QueryRequest(
            query = BrowseQuery(
                source = s.selectedSource,
                provinceCode = s.selectedProvinceCode,
                categoryId = s.selectedCategoryId,
                query = if (s.searchActive) s.searchQuery else "",
            ),
            typed = typed,
        )
    }

    /**
     * 播放进度。**冷 Flow**：只有真的有人在看的时候才跑。
     *
     * 旧实现是 ViewModel 整个生命周期内每 500ms 无条件更新的热 StateFlow，
     * 且 ticker 里还顺带触发节目刷新（进而拉整份频道列表）。退到后台、没有电台在播、
     * 界面根本没订阅时它照样在转。
     *
     * 改为 `stateIn(WhileSubscribed)` 后：UI 用 `collectAsStateWithLifecycle` 收集，
     * 退后台即停止订阅，超时后 ticker 自动停摆；回前台自动恢复。
     */
    val progress: StateFlow<ProgressState> = flow {
        while (true) {
            val c = connection.connected
            if (c == null) {
                emit(ProgressState())
            } else {
                emit(computeProgress(c))
                maybeRefreshLiveWindow()
            }
            delay(PROGRESS_TICK_MS.milliseconds)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(PROGRESS_STOP_DELAY_MS),
        initialValue = ProgressState(),
    )

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.None)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _updateEvents = MutableSharedFlow<UpdateEvent>()
    val updateEvents: SharedFlow<UpdateEvent> = _updateEvents.asSharedFlow()

    private var downloadJob: Job? = null

    private var liveWindowStart = 0L
    private var liveWindowEnd = 0L

    private var resolvingLive = false

    /** 上次触发直播窗口解析的墙钟时间，用于给窗口未知时的重试加最小间隔，避免无节目单电台高频空拉。 */
    private var lastLiveResolveAt = 0L

    private val currentSource: RadioSourceType get() = _uiState.value.selectedSource
    private fun activeSource(): RadioSource = sources.getValue(currentSource)

    /**
     * 取播放器控制器；未连接时按需发起连接。
     *
     * **连接失败或超时返回 null**，调用方必须处理。旧实现是
     * `controllerState.filterNotNull().first()`，连接失败即永久挂起，把 seekTo、睡眠定时器、
     * 节目刷新乃至整个首屏加载链路一起卡死。
     */
    private suspend fun controller(): MediaController? = connection.awaitController()

    /** isBuffering = 播放器缓冲态 或 断流恢复中（retrySeconds>0），二者任一即显示缓冲。 */
    private fun recomputeBuffering() {
        val buffering = connection.connected?.playbackState == Player.STATE_BUFFERING ||
                PlaybackBridge.retrySeconds.value > 0
        if (buffering != _uiState.value.isBuffering) _uiState.update { it.copy(isBuffering = buffering) }
    }

    /**
     * 通用播放入口：用给定地址与元数据构造 MediaItem 并起播（供直播与回放共用）。
     * title/artist/artworkUri 供媒体通知渲染。
     *
     * 返回是否成功起播。播放器连不上时返回 false 并写入可恢复的错误状态 ——
     * 调用方据此停止后续写回（如把 loadedUrl 当成已加载），不要假装播上了。
     */
    private suspend fun playUrl(
        url: String,
        title: String,
        artist: String,
        art: String,
        mimeType: String? = null,
    ): Boolean {
        val c = controller() ?: run {
            _uiState.update { it.copy(playerError = PLAYER_UNAVAILABLE, isPlaying = false) }
            return false
        }
        c.setMediaItem(mediaItemOf(url, title, artist, art, mimeType))
        c.prepare()
        c.play()
        if (_uiState.value.playerError != null) _uiState.update { it.copy(playerError = null) }
        return true
    }

    /**
     * 构造带媒体元数据的 MediaItem：title/artist/art 供系统媒体卡片/通知渲染。
     *
     * [mimeType] 决定 RadioPlayer 选哪个 MediaSource 工厂。HLS 必须显式传
     * `MimeTypes.APPLICATION_M3U8`：网关地址形如 /proxy/s20277，无 .m3u8 后缀，
     * 靠 URI 推断会一律当成渐进式，HLS 台就此卡在「缓冲中」。
     * 传 null 表示交给 ExoPlayer 按内容嗅探。
     */
    private fun mediaItemOf(
        url: String,
        title: String,
        artist: String,
        art: String,
        mimeType: String? = null,
    ): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(art.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build(),
            )
            .build()

    /**
     * 加载并从头播放一个电台（直播）：title=电台名、artist=当前节目、封面=电台封面。
     *
     * 所有直播起播路径都必须经过这里 —— 地址与流类型在此一并解析，别处直接取
     * [Channel.playUrlLow] 会丢掉类型。
     */
    private suspend fun playLiveStream(source: RadioSourceType, channel: Channel): Boolean {
        // 来源由意图携带，不在此现读 UI 状态：切来源后旧的在途解析会拿新来源解析旧频道。
        val stream = sources.getValue(source).resolveStream(channel)
        val started = playUrl(
            url = stream.url,
            title = channel.title,
            artist = channel.subtitle,
            art = channel.image,
            mimeType = MimeTypes.APPLICATION_M3U8.takeIf { stream.isHls },
        )
        if (started) resolveLiveWindow(channel, source)
        return started
    }

    /**
     * 刷新一次进度状态。回放：读 player 的 position/duration（可拖动）；
     * 直播：按当前节目窗口 + 墙钟计算（不可拖动），窗口未知时回退当天 24h。
     */
    /**
     * 计算一次进度快照。纯计算，无副作用 —— 直播窗口的重解析由 [maybeRefreshLiveWindow]
     * 单独负责，不再混在进度计算里。
     *
     * 回放：读 player 的 position/duration（可拖动）；
     * 直播：按当前节目窗口 + 墙钟计算（不可拖动），窗口未知时回退当天 24h。
     */
    private fun computeProgress(c: MediaController): ProgressState {
        val state = _uiState.value
        if (state.currentChannel == null) return ProgressState()
        if (state.playingProgramTitle != null) {
            val dur = c.duration.takeIf { it > 0 } ?: 0L
            return ProgressState(
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = dur,
                seekable = dur > 0,
            )
        }
        val now = System.currentTimeMillis()
        val start = if (liveWindowEnd > liveWindowStart) liveWindowStart else dayStartMillis(0)
        val end = if (liveWindowEnd > liveWindowStart) liveWindowEnd else start + DAY_MILLIS
        return ProgressState(
            positionMs = (now - start).coerceIn(0L, end - start),
            durationMs = end - start,
            seekable = false,
        )
    }

    /**
     * 解析当前直播节目窗口并写回；失败/未覆盖置 0（进度回退当天 24h）。
     *
     * 写回前校验身份：解析期间用户可能已切台或切来源，旧结果写回会让进度条按**别的台**的
     * 节目窗口走。旧实现只有一个 `resolvingLive` 防重入标志，防不了错配。
     */
    private suspend fun resolveLiveWindow(channel: Channel, source: RadioSourceType) {
        resolvingLive = true
        lastLiveResolveAt = System.currentTimeMillis()
        try {
            val win = programs.currentWindow(source, channel, dayStartMillis(0))
            val cur = _uiState.value
            if (cur.playingSource != source ||
                cur.currentChannel?.contentId != channel.contentId
            ) return
            liveWindowStart = win?.first ?: 0L
            liveWindowEnd = win?.last ?: 0L
        } finally {
            resolvingLive = false
        }
    }

    /**
     * 直播窗口已过期或未知时重新解析。
     *
     * 未知窗口也重试是关键：节目切换瞬间后端常还没更新到下一档，若首解析失败就永不再试，
     * 播放器会一直停在上一节目，直到用户手动打开电台列表。
     */
    private fun maybeRefreshLiveWindow() {
        val state = _uiState.value
        val channel = state.currentChannel ?: return
        if (state.playingProgramTitle != null) return  // 回放态不走直播窗口
        val now = System.currentTimeMillis()
        val expired = liveWindowEnd in 1..now
        val unknown = liveWindowEnd <= 0
        if (!expired && !unknown) return
        if (resolvingLive || now - lastLiveResolveAt < LIVE_RESOLVE_MIN_INTERVAL_MS) return
        viewModelScope.launch {
            resolveLiveWindow(channel, state.playingSource)
            refreshPrograms()
        }
    }

    /** 拖动定位（仅回放）：目标位置夹到 [0, duration] 后 seek，播放器随即进入缓冲加载。 */
    fun seekTo(positionMs: Long) {
        viewModelScope.launch {
            val c = controller() ?: return@launch
            val dur = c.duration.takeIf { it > 0 } ?: return@launch
            c.seekTo(positionMs.coerceIn(0L, dur))
        }
    }

    private var playingProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE

    /**
     * 已加载到播放器的地址;null 表示尚未加载。
     * 用于「记忆但不自动播放」场景:启动续播关闭时仅把上次电台设为当前(不加载),
     * 待用户首次按下播放键再真正加载,避免无谓缓冲。
     *
     * 只判 null / 非 null，值本身不可当播放地址用：直播真实加载的是
     * [RadioSource.resolveStream] 解析后的地址，与此处记的 playUrlLow 可能不同。
     */
    private var loadedUrl: String? = null

    private var sleepTimerJob: Job? = null

    /** 当前的节目单加载。切日期即取消上一次，见 [loadPlaybill]。 */
    private var playbillJob: Job? = null

    /**
     * 播放意图队列。容量 1 且丢弃最旧 —— 用户连按方向键快切时，中间那些台没有播放价值，
     * 只有最后一个作数；队列堆积反而会让每一个都被执行一遍。
     */
    private val playbackIntents = MutableSharedFlow<PlaybackIntent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 提交播放意图。非挂起：UI 事件回调里直接调。 */
    private fun submit(intent: PlaybackIntent) {
        playbackIntents.tryEmit(intent)
    }

    /**
     * 执行一次播放意图。由 `collectLatest` 驱动，新意图到达时本函数会在任意挂起点被取消。
     *
     * 正因如此，await 之后的状态写回才是安全的：过期的执行早已被取消，
     * 不可能再写回。旧代码没有这层保证，playReplay 在两次 await 后写节目名，
     * 会覆盖 playLive 的同步清除，造成"播着直播却显示回放节目名"。
     */
    private suspend fun execute(intent: PlaybackIntent) {
        when (intent) {
            is PlaybackIntent.Live -> {
                if (playLiveStream(intent.source, intent.channel)) {
                    loadedUrl = intent.channel.playUrlLow
                } else {
                    loadedUrl = null
                }
            }

            is PlaybackIntent.Replay -> {
                val url = try {
                    sources.getValue(intent.source)
                        .resolveReplayUrl(intent.channel, intent.program)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
                if (url.isBlank()) return
                if (!playUrl(
                        url,
                        intent.channel.title,
                        intent.program.title,
                        intent.channel.image
                    )
                ) {
                    return
                }
                loadedUrl = url
                _uiState.update {
                    it.copy(playingProgramTitle = intent.program.title, showPlaybill = false)
                }
            }
        }
    }

    init {
        // 服务已在放（用户退后台又回来）→ 必须主动连上接管，否则界面显示未播放而喇叭在响。
        // 服务不在 → 桥上若还留着非 0 的重试倒计时，那是被系统直接杀掉留下的过期值，清掉。
        if (PlaybackBridge.serviceRunning.value) connection.connect() else PlaybackBridge.reset()

        // 连接状态驱动 UI，不再在主线程 Runnable 里裸调 future.get()。
        viewModelScope.launch {
            connection.state.collect { st ->
                when (st) {
                    is ConnectionState.Connected -> {
                        st.controller.addListener(playerListener)
                        _uiState.update {
                            it.copy(isPlaying = st.controller.isPlaying, playerError = null)
                        }
                        recomputeBuffering()
                    }

                    is ConnectionState.Failed -> {
                        // 连不上就明确告诉用户，而不是让播放键按下去没反应。
                        _uiState.update {
                            it.copy(
                                playerError = PLAYER_UNAVAILABLE,
                                isPlaying = false
                            )
                        }
                        recomputeBuffering()
                    }

                    else -> Unit
                }
            }
        }
        // 播放意图串行管道：新意图到达即取消旧执行（latest-wins）。
        // 快速切台时慢响应不会覆盖后选中的电台；回放与直播不再互相写脏状态；
        // 取消沿协程边界传播到 Retrofit，不留孤儿请求。
        viewModelScope.launch {
            playbackIntents.collectLatest { execute(it) }
        }
        viewModelScope.launch {
            PlaybackBridge.retrySeconds.collect { seconds ->
                _uiState.update { it.copy(retrySeconds = seconds) }
                recomputeBuffering()
            }
        }
        viewModelScope.launch {
            // 收藏分源存储、合并展示。按 entries 遍历而非逐个列举：
            // 新增来源时漏改这里的话，该来源的收藏会静默不显示。
            combine(RadioSourceType.entries.map { prefs.favorites(it) }) { perSource ->
                perSource.toList().flatten()
            }.collect { favs -> _uiState.update { it.copy(favorites = favs) } }
        }
        viewModelScope.launch {
            prefs.autoPlayLast.distinctUntilChanged()
                .collect { enabled -> _uiState.update { it.copy(autoPlayLast = enabled) } }
        }
        viewModelScope.launch {
            prefs.autoFullscreen.distinctUntilChanged()
                .collect { enabled -> _uiState.update { it.copy(autoFullscreen = enabled) } }
        }
        viewModelScope.launch {
            prefs.selectedSource.distinctUntilChanged()
                .flatMapLatest { prefs.homeCity(it, sources.getValue(it).defaultProvinceCode) }
                .distinctUntilChanged()
                .collect { code -> _uiState.update { it.copy(homeCityCode = code) } }
        }
        viewModelScope.launch {
            var first = true
            // collectLatest 而非 collect：快速切来源时取消上一次尚未完成的 loadSource。
            // 用朴素 collect 时新来源必须排队等旧来源整个流程跑完（含筛选项两个请求和首页），
            // 期间旧来源的结果还会照常写回 UI。隔壁 homeCity 的收集早已用 flatMapLatest，
            // 这里是漏网的一处。
            prefs.selectedSource.distinctUntilChanged().collectLatest { source ->
                val autoStart = first && prefs.autoPlayLast.first()
                first = false
                loadSource(source, autoStart)
            }
        }
        checkForUpdate(manual = false)
    }

    /**
     * 打开搜索界面。与收藏视图、节目单互斥：三者抢的是右栏同一块位置，
     * 且搜索范围按「当前地区 + 分类」算，留在收藏视图里会让范围与所见不符。
     */
    fun openSearch() {
        if (_uiState.value.searchActive) return
        _uiState.update {
            it.copy(
                searchActive = true,
                searchQuery = "",
                showFavorites = false,
                showPlaybill = false,
            )
        }
        // 查询词为空 ⇒ 查询与筛选浏览时完全相同，distinctUntilChanged 会把它吞掉，
        // 打开搜索本身不会重新发请求，网格原样留着。
        updateQuery(typed = false)
    }

    /**
     * 关闭搜索界面，回到当前筛选列表。
     *
     * 这里会重新拉一次筛选列表的首页：浏览与搜索合成了同一条分页流（见 [BrowseQuery]），
     * 不再各留一份快照。代价是退出搜索多一次请求，换掉的是两套并行分页状态。
     */
    fun closeSearch() {
        if (!_uiState.value.searchActive) return
        _uiState.update { it.copy(searchActive = false, searchQuery = "") }
        updateQuery(typed = false)
    }

    /** 设置查询词（竖屏系统输入法整串写入 / 键盘逐字追加都走这里）。 */
    fun setSearchQuery(q: String) {
        if (!_uiState.value.searchActive || q == _uiState.value.searchQuery) return
        _uiState.update { it.copy(searchQuery = q) }
        updateQuery(typed = true)
    }

    /** 键盘输入一个字符。 */
    fun appendSearchChar(c: Char) = setSearchQuery(_uiState.value.searchQuery + c)

    /** 键盘删除键：退一个字符。已空则无动作。 */
    fun backspaceSearch() {
        val q = _uiState.value.searchQuery
        if (q.isEmpty()) return
        setSearchQuery(q.dropLast(1))
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
                selectedProvinceCode = home,
                selectedCategoryId = UserPreferences.DEFAULT_CATEGORY_ID,
                homeCityCode = home,
                showFavorites = false,
                isLoadingFilters = true,
                error = null,
            )
        }
        // 换来源等于换了整个搜索范围（地区/分类都重置了），旧结果无意义 —— 直接退出搜索，
        // 而不是留个空壳界面让用户猜为什么结果没了。
        closeSearch()
        // 首页与筛选项并发拉取：查询在这里就提交，不必等筛选项两个请求回来。
        // 旧代码把 loadChannels() 放在筛选项之后，弱网下首屏白等一个往返。
        updateQuery(typed = false)

        if (loadedUrl == null) {
            val last = prefs.lastPlayed().first()
            playingProvinceCode = last?.provinceCode ?: UserPreferences.DEFAULT_PROVINCE_CODE
            _uiState.update {
                it.copy(currentChannel = last?.channel, playingSource = last?.source ?: source)
            }
            if (autoStart && last != null) {
                // 自动续播**不得**挡在首屏加载前面。
                // 旧代码在这里 await playNow，而 playNow 会依次等流解析（网络往返）和
                // MediaController 连接；连接失败或悬挂时，下面的筛选项与频道列表请求
                // 根本发不出去，首屏永远是空列表 —— 这正是「低端电视打开就卡住」的成因。
                // 播放链路从此独立成协程，与浏览链路彻底解耦。
                loadedUrl = last.channel.playUrlLow
                submit(PlaybackIntent.Live(last.source, last.channel))
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
            // 存档的所在城市可能已不在地区列表里（如蜻蜓那个被去掉的「全部」哨兵，
            // 或服务端调整了地区集合）。放着不管的话筛选栏一个选中项都没有，
            // 用户看不出当前在浏览哪儿 —— 回落到该来源的默认地区并重新取一次。
            val selected = _uiState.value.selectedProvinceCode
            val fallback = src.defaultProvinceCode
            if (provinces.isNotEmpty() &&
                provinces.none { it.provinceCode == selected } &&
                provinces.any { it.provinceCode == fallback }
            ) {
                _uiState.update { it.copy(selectedProvinceCode = fallback) }
                updateQuery(typed = false)
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoadingFilters = false,
                    error = e.message ?: "加载筛选项失败"
                )
            }
        }
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
     *
     * [loadedChannelCount] = 网格当前已加载条数（`LazyPagingItems.itemCount`，由 UI 给出）。
     * 分页后 VM 不再持有列表，刷多少条只有 UI 知道；为 0 时退回一页。
     */
    fun refreshPrograms(loadedChannelCount: Int = 0) {
        val state = _uiState.value
        val query = queryRequests.value?.query
        viewModelScope.launch {
            val refreshed: List<Channel>? = if (state.showFavorites) {
                if (state.favorites.isNotEmpty()) runCatching { refreshFavoritesGrouped(state.favorites) }
                null
            } else {
                runCatching {
                    activeSource().fetchChannels(
                        categoryId = state.selectedCategoryId,
                        provinceCode = state.selectedProvinceCode,
                        // 只刷已加载的这些页；为空时退回一页，否则 limit=0 会误取全量。
                        limit = loadedChannelCount.coerceAtLeast(RadioSource.PAGE_SIZE),
                    )
                }.getOrNull()?.also { latest ->
                    // 筛选未变才回写，避免这次静默刷新把副标题贴到用户已切换的新列表上
                    if (queryRequests.value?.query == query) {
                        val updates = latest.associate { c -> c.contentId to c.subtitle }
                        // 合并而非替换：本次只刷了前 N 条，更靠后页的旧覆盖值要留着。
                        subtitleOverrides.update { prev -> prev + updates }
                    }
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
                        // 按 contentId 找单台，必须全量：被页大小截断则排名靠后的
                        // 正在播电台副标题永不刷新（静默失败）。
                        limit = RadioSource.NO_LIMIT,
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
                // 元数据刷新不是播放意图：只在已连上时更新，不为此触发绑定服务。
                val c = connection.connected ?: return@launch
                // 只换元数据里的副标题，地址与 mimeType 必须从正在播的 MediaItem 原样带过来：
                // 用 cur.playUrlLow 重建会丢掉 resolveStream 解析后的地址和 HLS 类型，
                // 把正在播的 HLS 台打回渐进式（表现为刷节目单后突然卡住）。
                val playing = c.currentMediaItem?.localConfiguration
                if (playing != null) {
                    runCatching {
                        c.replaceMediaItem(
                            c.currentMediaItemIndex,
                            mediaItemOf(
                                url = playing.uri.toString(),
                                title = cur.title,
                                artist = latestSubtitle,
                                art = cur.image,
                                mimeType = playing.mimeType,
                            ),
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
        // 搜索态下筛选即搜索范围：换了范围立即按新范围重查，不等打字防抖。
        updateQuery(typed = false)
    }

    fun selectCategory(categoryId: String) {
        val state = _uiState.value
        if (categoryId == state.selectedCategoryId) {
            if (state.showFavorites) _uiState.update { it.copy(showFavorites = false) }
            return
        }
        _uiState.update { it.copy(selectedCategoryId = categoryId, showFavorites = false) }
        updateQuery(typed = false)
    }

    /** 打开收藏视图，并按城市重新拉取以刷新各收藏电台的节目单。与搜索互斥。 */
    fun showFavoritesView() {
        closeSearch()
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
    /**
     * 切换收藏。[source] 同 [playChannel]：由调用方按位置给出，不从裸 ID 猜。
     * 收藏在 DataStore 中本就按来源分 key 存储，来源一旦猜错就会写进别的源的收藏列表。
     */
    fun toggleFavorite(channel: Channel, source: RadioSourceType) {
        val state = _uiState.value
        val fav = state.favorites.firstOrNull {
            it.source == source && it.channel.contentId == channel.contentId
        }
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

    /** 设定首页 30s 无操作是否自动进入全屏。 */
    fun setAutoFullscreen(enabled: Boolean) {
        viewModelScope.launch { prefs.saveAutoFullscreen(enabled) }
    }

    /**
     * 选中并播放一个电台。
     *
     * [source] 由调用方按网格项位置显式给出（见 [RadioUiState.sourceAt]），**不再**从裸
     * contentId 反查收藏来猜 —— 跨来源 ID 可能重名，猜错就会播成别的源的同号电台。
     */
    fun playChannel(channel: Channel, source: RadioSourceType) {
        val state = _uiState.value
        val fav = state.favorites.firstOrNull {
            it.source == source && it.channel.contentId == channel.contentId
        }
        playingProvinceCode = fav?.provinceCode ?: state.selectedProvinceCode
        _uiState.update {
            it.copy(
                currentChannel = channel,
                playingSource = source,
                playingProgramTitle = null
            )
        }
        // 乐观置位以保住 loadSource 的「是否已加载」判据；起播失败则回滚，
        // 否则下次按播放会走「已加载」分支，对着空播放器调 play() 毫无反应。
        // 乐观置位以保住 loadSource 的「是否已加载」判据；起播失败由管道回滚。
        loadedUrl = channel.playUrlLow
        submit(PlaybackIntent.Live(source, channel))
        viewModelScope.launch { prefs.saveLastPlayed(source, channel, playingProvinceCode) }
        refreshPrograms()
    }

    fun togglePlayPause() {
        val channel = _uiState.value.currentChannel ?: return
        viewModelScope.launch {
            if (loadedUrl == null) {
                // 首次按播放才真正加载：这里才是播放意图，连接在此按需建立。
                submit(PlaybackIntent.Live(_uiState.value.playingSource, channel))
            } else {
                val c = controller() ?: return@launch
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

    /**
     * 按需加载某天节目单。
     *
     * 快切日期靠**取消**上一次而非自增令牌丢弃迟到结果：取消沿协程边界传到
     * [ProgramRepository]，最后一个等待者走掉时在途请求随之取消（进而 `Call.cancel()`）。
     * 令牌只能让旧结果不写回，请求本身仍会跑完 —— 遥控连按日期时那是一串白跑的往返。
     */
    private fun loadPlaybill(dayStart: Long) {
        val state = _uiState.value
        val channel = state.currentChannel ?: return
        val source = state.playingSource
        _uiState.update { it.copy(isLoadingPlaybill = true, playbillError = null) }
        playbillJob?.cancel()
        playbillJob = viewModelScope.launch {
            try {
                val list = programs.playbill(source, channel, dayStart)
                _uiState.update {
                    it.copy(
                        playbillPrograms = list,
                        isLoadingPlaybill = false,
                        playbillError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        playbillPrograms = emptyList(),
                        isLoadingPlaybill = false,
                        playbillError = e.message ?: "加载节目单失败",
                    )
                }
            }
        }
    }

    /** 播放某节目的回放:解析地址(蜻蜓按需二次请求)后接管播放;地址为空则静默不播。 */
    fun playReplay(program: Program) {
        val state = _uiState.value
        val channel = state.currentChannel ?: return
        submit(PlaybackIntent.Replay(state.playingSource, channel, program))
    }

    /** 从回放切回直播：重载当前电台直播流、清空回放节目名并关闭节目单。已在直播则忽略。 */
    fun playLive() {
        val channel = _uiState.value.currentChannel ?: return
        if (_uiState.value.playingProgramTitle == null) return
        _uiState.update { it.copy(playingProgramTitle = null, showPlaybill = false) }
        loadedUrl = channel.playUrlLow
        submit(PlaybackIntent.Live(_uiState.value.playingSource, channel))
    }

    /** 归零到某天(今天+[offset]天)北京时间 00:00:00 的 epoch ms。 */
    private fun dayStartMillis(offset: Int): Long = Calendar.getInstance(BEIJING_TIME_ZONE).apply {
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
            else -> Calendar.getInstance(BEIJING_TIME_ZONE).apply { timeInMillis = ms }.let { c ->
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
            // 连不上也必须把定时器状态清掉。旧代码这里是 `controller().pause()`，
            // 连接失败时倒计时跑完却永久挂在这一行，下面的复位永远不执行 ——
            // 表现为睡眠定时器 UI 永久卡在已结束的倒计时上。
            // 暂停只对已连上的播放器有意义，不为此触发连接。
            connection.connected?.pause()
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
            val file = try {
                UpdateInstaller.download(getApplication(), url, current.app.size) { p ->
                    (_updateState.value as? UpdateState.Available)?.let {
                        _updateState.value = it.copy(progress = p)
                    }
                }
            } catch (e: CancellationException) {
                throw e  // 用户取消：不发失败事件，向上传播结束协程
            } catch (e: Exception) {
                null
            }
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
        programs.close()
        connection.connected?.removeListener(playerListener)
        connection.release()
        // 即使尚未连上也要释放 future（Media3 要求），故用保存下来的引用而非连接状态。
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    companion object {
        private const val HALF_HOUR_MILLIS = 30 * 60 * 1000L

        private val WEEK_LABELS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    }
}
