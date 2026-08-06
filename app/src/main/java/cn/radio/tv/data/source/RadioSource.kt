package cn.radio.tv.data.source

import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.prefs.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * 节目单的时区口径。三个来源的节目单都是按北京时间划天与排期的，所以日期分档、
 * 发给网关的 date、播出时间展示一律钉死在 +08:00，不跟设备时区走 —— 否则设备
 * 时区一改（或人在境外），「今天」和节目时间会整体平移。
 * 服务端同口径见 radio-proxy `gateway.go:cnZone`。
 */
val BEIJING_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

/**
 * 一条可播的直播流：地址与类型作为**不可分的一个值**传递。
 *
 * 分成两个参数传会出现「带了地址却漏了类型」的中间状态，而漏掉类型的表现是播放器
 * 选错解析器后永远停在「缓冲中」—— 曾经的 TuneIn HLS 台就是这么卡住的。
 *
 * [isHls] 为真时上层显式设 `MimeTypes.APPLICATION_M3U8`；为假不设 mimeType，
 * 交给 ExoPlayer 按内容嗅探（TuneIn 直链可能是 mp3/aac/ogg 任一，猜错容器比不猜更糟）。
 */
data class ResolvedStream(val url: String, val isHls: Boolean)

/**
 * 单个电台来源的数据契约。各来源（云听 / 蜻蜓FM）各自实现，把自家接口映射到
 * 共享的 [Province] / [Category] / [Channel] 业务模型；上层只面向本接口，
 * 切换来源即切换实现，列表与收藏天然互不混合。
 *
 * 约定：[fetchChannels] 的 categoryId / provinceCode 为 "0" / 0L 时表示「全部」。
 */
interface RadioSource {

    val type: RadioSourceType

    /** 「所在城市」未设定时的默认地区码；多数来源为「全部」(0)，个别来源可覆盖。 */
    val defaultProvinceCode: Long get() = UserPreferences.DEFAULT_PROVINCE_CODE

    suspend fun fetchProvinces(): List<Province>

    suspend fun fetchCategories(): List<Category>

    /** 取一页电台列表。[offset] 为已加载条数，[limit] 为本页条数。 */
    suspend fun fetchChannels(
        categoryId: String,
        provinceCode: Long,
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): List<Channel>

    /**
     * 按 contentId 批量取最新快照（主要是 subtitle = 当前节目名）。
     *
     * 返回顺序随请求，**查不到的略过**：电台下架、换地区都是常态，调用方保留旧快照即可。
     * [provinceCode] 是检索范围（收藏收录时记下的所在地区）。
     * 见 [BaseRadioSource] 的默认实现（不支持则返回空）与 [GatewaySource] 的网关实现。
     */
    suspend fun fetchChannelsByIds(
        provinceCode: Long,
        contentIds: List<String>,
    ): List<Channel>

    /**
     * 在「[provinceCode] 地区 + [categoryId] 分类」范围内按名称搜台。[q] 可以是中文原文、
     * 拼音首字母、全拼或英文子串 —— 匹配口径由服务端定义，客户端不做本地过滤。
     * 分页语义同 [fetchChannels]。见 [BaseRadioSource] 的默认实现（不支持搜索则返回空）。
     */
    suspend fun searchChannels(
        q: String,
        categoryId: String,
        provinceCode: Long,
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): List<Channel>

    /** 刷新收藏电台的节目单（subtitle 等）；见 [BaseRadioSource] 的通用实现。 */
    suspend fun refreshFavoritePrograms(favorites: List<FavoriteChannel>): List<FavoriteChannel>

    /** 取某电台某一天（[dayStartMillis]=北京时间 00:00 的 epoch ms，见 [BEIJING_TIME_ZONE]）的节目单。 */
    suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program>

    /**
     * 解析回放地址：已带地址（云听）直接返回；蜻蜓按需二次请求 replay_program。
     * 见 [BaseRadioSource] 的默认实现（直接返回 [Program.replayUrl]）。
     */
    suspend fun resolveReplayUrl(channel: Channel, program: Program): String

    /**
     * 解析直播流：地址 + 是否 HLS。见 [BaseRadioSource] 的默认实现与
     * [GatewaySource] 的网关实现。
     */
    suspend fun resolveStream(channel: Channel): ResolvedStream

    companion object {
        /** 列表分页的页大小。须与服务端 `gateway.go:defaultPageSize` 一致。 */
        const val PAGE_SIZE = 60
    }
}

/**
 * 提供收藏节目单刷新的通用实现：按收藏收录时记下的所在地区
 * [FavoriteChannel.provinceCode] 分组，每组用 [RadioSource.fetchChannelsByIds] 只取这几个台的
 * 最新快照并覆盖。取不到（接口缺失、请求失败、电台下架）则保留原快照，原顺序不变。
 *
 * 按 id 批量查而非拉全量列表再匹配：实测 TuneIn 美国节点全量 2,148,833 字节，
 * 而收藏在该地区的 4 个台按 id 取只要 828 字节（1/2595）。这条路径在用户可感知的场景
 * 触发（打开收藏页、回前台），全量下载在低性能 TV 上是大 JSON 解析 + HashMap + 内存峰值的叠加。
 */
abstract class BaseRadioSource : RadioSource {

    override suspend fun refreshFavoritePrograms(
        favorites: List<FavoriteChannel>,
    ): List<FavoriteChannel> = withContext(Dispatchers.IO) {
        if (favorites.isEmpty()) return@withContext favorites

        // 各地区相互独立，并行拉取以缩短整体刷新等待；信号量限制并发，避免收藏跨多地区时
        // 一次性发起过多请求拖垮弱性能 TV / 触发限流。
        val gate = Semaphore(MAX_CONCURRENT_REFRESH)
        val latestByProvince: Map<Long, Map<String, Channel>> = coroutineScope {
            favorites.groupBy { it.provinceCode }.map { (code, inProvince) ->
                async {
                    code to gate.withPermit {
                        try {
                            fetchChannelsByIds(code, inProvince.map { it.channel.contentId })
                                .associateBy { it.contentId }
                        } catch (e: CancellationException) {
                            throw e  // 取消不是「这个地区刷新失败」，不能降级成空结果
                        } catch (_: Exception) {
                            emptyMap()  // 某地区取不到就保留该组旧快照，不影响其他地区
                        }
                    }
                }
            }.awaitAll().toMap()
        }

        favorites.map { fav ->
            val latest = latestByProvince[fav.provinceCode]?.get(fav.channel.contentId)
            if (latest != null) fav.copy(channel = latest) else fav
        }
    }

    /**
     * 默认不支持按 id 批量查，返回空（调用方保留旧快照）。能力在服务端，
     * 故只有 [GatewaySource] 覆盖此法 —— 同 [searchChannels] 的处置。
     */
    override suspend fun fetchChannelsByIds(
        provinceCode: Long,
        contentIds: List<String>,
    ): List<Channel> = emptyList()

    /**
     * 默认不支持搜索，返回空。搜索能力在服务端，故只有 [GatewaySource] 覆盖此法；
     * 返回空而非抛错：搜不出结果比崩掉一个界面轻。
     */
    override suspend fun searchChannels(
        q: String,
        categoryId: String,
        provinceCode: Long,
        offset: Int,
        limit: Int,
    ): List<Channel> = emptyList()

    /** 默认回放地址已随节目单返回（云听）；蜻蜓覆盖此法按需二次解析。 */
    override suspend fun resolveReplayUrl(channel: Channel, program: Program): String =
        program.replayUrl

    /**
     * 默认不查类型，按渐进式起播（等同接入网关前的行为）。
     * [GatewaySource] 覆盖此法向网关问真实类型。
     */
    override suspend fun resolveStream(channel: Channel): ResolvedStream =
        ResolvedStream(channel.playUrlLow, isHls = false)

    protected companion object {
        /** 收藏跨地区刷新时的最大并发请求数，平衡刷新速度与弱 TV 负载。 */
        const val MAX_CONCURRENT_REFRESH = 4
    }
}
