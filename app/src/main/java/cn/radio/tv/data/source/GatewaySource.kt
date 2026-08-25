package cn.radio.tv.data.source

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.prefs.UserPreferences
import cn.radio.tv.data.remote.GatewayApi
import cn.radio.tv.data.remote.NetworkModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 电台数据的唯一入口。三来源（云听 / 蜻蜓FM / TuneIn）经服务端 radio-proxy 归一为同一套
 * 模型与响应格式，客户端不按来源分实现：来源是**每次调用的参数**（`source=type.key`），
 * 不是对象身份。签名、地址拼接、去「台」后缀、回放两段式等差异全部在服务端消化。
 *
 * 少数服务端未抹平的差异（蜻蜓的哨兵地区、TuneIn 无节目单）就地写在对应方法里，
 * 集中在此一处，便于与服务端行为对照。
 *
 * 约定：[fetchChannels] 的 categoryId / provinceCode 为 "0" / 0L 时表示「全部」。
 *
 * @param io 承载网络调用的调度器。可注入是为了让单测把它换成测试调度器 —— 否则请求跑在
 *   真实 IO 线程上，虚拟时间管不住在途请求，用例只能靠 sleep 撞运气。
 */
class GatewaySource(
    private val api: GatewayApi = NetworkModule.gatewayApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 网关是否支持 `/v1/channels/by-ids`。
     *
     * 端点有无是**这套部署**的属性，三来源共用同一个网关，故一次 404 对所有来源生效。
     */
    private var byIdsSupported = true

    /** 「所在城市」未设定时的默认地区码。 */
    fun defaultProvinceCode(source: RadioSourceType): Long = when (source) {
        // 蜻蜓无「全部地区」，默认「网络台」。
        RadioSourceType.QINGTING -> QINGTING_DEFAULT_PROVINCE
        // TuneIn 服务端支持「全部」，但那是全球两三万台，首屏没必要。默认美国节点。
        RadioSourceType.TUNEIN -> TUNEIN_DEFAULT_PROVINCE
        // 云听默认「全部」(0)。
        else -> UserPreferences.DEFAULT_PROVINCE_CODE
    }

    /**
     * 地区列表。
     *
     * 蜻蜓本身没有「全部地区」，网关为了三来源口径统一补了个 provinceCode=0 的哨兵
     * （`radio-proxy/source_qingting.go:71`，请求 channels 时省略 region_id）。
     * 该项不在界面上展示，故在此过滤掉。
     *
     * 只过滤展示列表，不动请求语义：仍以 provinceCode=0 发出的请求（如按存档地区分组的
     * 收藏刷新）服务端照旧支持，过滤这一项不会让它们失效。
     */
    suspend fun fetchProvinces(source: RadioSourceType): List<Province> = withContext(io) {
        val provinces = api.getProvinces(source.key).dataOrThrow("省份")
        if (source == RadioSourceType.QINGTING) {
            provinces.filterNot { it.provinceCode == UserPreferences.DEFAULT_PROVINCE_CODE }
        } else {
            provinces
        }
    }

    suspend fun fetchCategories(source: RadioSourceType): List<Category> = withContext(io) {
        api.getCategories(source.key).dataOrThrow("分类")
    }

    /** 取一页电台列表。[offset] 为已加载条数，[limit] 为本页条数。 */
    suspend fun fetchChannels(
        source: RadioSourceType,
        categoryId: String,
        provinceCode: Long,
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): List<Channel> = withContext(io) {
        api.getChannels(source.key, provinceCode, categoryId, offset, limit).dataOrThrow("电台列表")
    }

    /**
     * 在**当前来源的整份目录**里按名称搜台，跨地区跨分类。[q] 可以是中文原文、拼音首字母、
     * 全拼或英文子串 —— 匹配口径由服务端定义，客户端不做本地过滤。分页语义同 [fetchChannels]。
     *
     * 不收 categoryId / provinceCode：搜索范围就是整份目录，收了也只会让调用方以为搜索还
     * 受筛选影响。范围由 `scope=catalog` 表达 —— 地区与分类传服务端的「全部」哨兵（新网关
     * 忽略它们；旧网关不认 scope，会退回按这两个值过滤，见 [GatewayApi.SCOPE_CATALOG]
     * 里的部署顺序说明）。
     */
    suspend fun searchChannels(
        source: RadioSourceType,
        q: String,
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): List<Channel> = withContext(io) {
        api.searchChannels(
            source = source.key,
            provinceCode = UserPreferences.DEFAULT_PROVINCE_CODE,
            categoryId = UserPreferences.DEFAULT_CATEGORY_ID,
            q = q,
            offset = offset,
            limit = limit,
            scope = GatewayApi.SCOPE_CATALOG,
        ).dataOrThrow("搜索结果")
    }

    /**
     * 按 contentId 批量取最新快照（主要是 subtitle = 当前节目名）。空入参直接短路，不发请求。
     *
     * 返回顺序随请求，**查不到的略过**：电台下架、换地区都是常态，调用方保留旧快照即可。
     *
     * 端点未部署（旧版网关返回 404）时记下「无此能力」并返回空。记成进程级开关而非每次重试：
     * 这个 404 是部署事实，不是偶发失败，每次刷新都去撞一下只是稳定地多打一发请求。
     * 网关升级后重启 App 即恢复 —— 这条路径本就只影响副标题新鲜度。
     *
     * 额外带 `scope=catalog`：跨地区搜到的台被收藏时，[provinceCode] 记的是搜索时筛选栏
     * 选中的那个，**并非该台真正的地区** —— 按它查会静默查不到，表现为收藏的「正在播放」
     * 副标题永远刷不出来。走整份目录后，这个字段填得对不对不再影响刷新结果。
     * [provinceCode] 照常发：旧网关忽略 scope，仍按它检索，行为与今天一致。
     */
    suspend fun fetchChannelsByIds(
        source: RadioSourceType,
        provinceCode: Long,
        contentIds: List<String>,
    ): List<Channel> {
        if (contentIds.isEmpty() || !byIdsSupported) return emptyList()
        return withContext(io) {
            try {
                api.getChannelsByIds(
                    source = source.key,
                    provinceCode = provinceCode,
                    contentIds = contentIds.joinToString(","),
                    scope = GatewayApi.SCOPE_CATALOG,
                ).dataOrThrow("电台快照")
            } catch (e: HttpException) {
                if (e.code() != HTTP_NOT_FOUND) throw e
                byIdsSupported = false
                emptyList()
            }
        }
    }

    /**
     * 刷新收藏电台的节目单（subtitle 等）：按收藏收录时记下的所在地区
     * [FavoriteChannel.provinceCode] 分组，每组用 [fetchChannelsByIds] 只取这几个台的最新
     * 快照并覆盖。取不到（接口缺失、请求失败、电台下架）则保留原快照，原顺序不变。
     *
     * 按 id 批量查而非拉全量列表再匹配：实测 TuneIn 美国节点全量 2,148,833 字节，
     * 而收藏在该地区的 4 个台按 id 取只要 828 字节（1/2595）。这条路径在用户可感知的场景
     * 触发（打开收藏页、回前台），全量下载在低性能 TV 上是大 JSON 解析 + HashMap + 内存峰值的叠加。
     *
     * 入参须为**同一来源**的收藏：跨来源 ID 可能重名，混着刷会互相覆盖。
     */
    suspend fun refreshFavoritePrograms(
        source: RadioSourceType,
        favorites: List<FavoriteChannel>,
    ): List<FavoriteChannel> = withContext(io) {
        if (favorites.isEmpty()) return@withContext favorites

        // 各地区相互独立，并行拉取以缩短整体刷新等待；信号量限制并发，避免收藏跨多地区时
        // 一次性发起过多请求拖垮弱性能 TV / 触发限流。
        val gate = Semaphore(MAX_CONCURRENT_REFRESH)
        val latestByProvince: Map<Long, Map<String, Channel>> = coroutineScope {
            favorites.groupBy { it.provinceCode }.map { (code, inProvince) ->
                async {
                    code to gate.withPermit {
                        try {
                            fetchChannelsByIds(source, code, inProvince.map { it.channel.contentId })
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
     * 取某电台某一天的节目单。[dayStartMillis] = 北京时间 00:00 的 epoch ms
     * （见 [BEIJING_TIME_ZONE]），date 为北京时间的 yyyy/MM/dd。
     *
     * TuneIn 无节目单，直接短路返回空（省一次网关请求；网关对该源亦返回空，取其一即可）。
     */
    suspend fun fetchPlaybill(
        source: RadioSourceType,
        channel: Channel,
        dayStartMillis: Long,
    ): List<Program> {
        if (source == RadioSourceType.TUNEIN) return emptyList()
        return withContext(io) {
            // SimpleDateFormat 非线程安全，日期快切会并发触发，故每次新建。
            // Locale.US：这是给机器看的报文字段，不能被本地历法/数字形态改写。
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.US)
                .apply { timeZone = BEIJING_TIME_ZONE }
                .format(dayStartMillis)
            api.getPrograms(source.key, channel.contentId, date).dataOrThrow("节目单")
        }
    }

    /** 回放：非蜻蜓地址已随节目单下发（[Program.replayUrl]）；蜻蜓走网关两段式解析。 */
    suspend fun resolveReplayUrl(
        source: RadioSourceType,
        channel: Channel,
        program: Program,
    ): String {
        if (source != RadioSourceType.QINGTING) return program.replayUrl
        return withContext(io) {
            runCatching {
                api.getReplay(source.key, channel.contentId, program.id).data?.replayUrl.orEmpty()
            }.getOrDefault("")
        }
    }

    /**
     * 向网关问地址与流类型。三来源走同一条路（服务端按 source 分派），这里不写来源分支。
     *
     * [useProxy] 为「TuneIn 代理」开关（默认 false = 直连：用服务端下发的上游真实地址，
     * 省服务端带宽）。开启后一律走服务端透传地址；关闭但服务端没给直连地址（旧网关、
     * 非 TuneIn 来源）也照常回退透传地址 —— 故对其余来源天然是 no-op，调用方不必按来源分支。
     *
     * 失败不阻断播放：超时、网关不可达、旧版网关无此端点（404）时，退回
     * 「沿用 playUrlLow + 渐进式」。类型不确定不该比放不出声更严重。
     */
    suspend fun resolveStream(
        source: RadioSourceType,
        channel: Channel,
        useProxy: Boolean = false,
    ): ResolvedStream = withContext(io) {
        val fallback = ResolvedStream(channel.playUrlLow, isHls = false)
        runCatching {
            val dto = api.getStream(source.key, channel.contentId).data ?: return@runCatching fallback
            // 直连地址仅 TuneIn 有；其余来源与旧网关下发空串，这里恒为 null 走原分支，
            // 故不需要按 source 判断 —— 服务端的空值约定已把这层条件收掉。
            val direct = dto.directUrl.takeIf { !useProxy && it.isNotEmpty() }
            ResolvedStream(
                // url 为空是服务端约定：沿用客户端手上的地址（云听如此）。
                url = direct ?: dto.url.ifEmpty { channel.playUrlLow },
                // 未知取值按 progressive 兜底，保证新服务端 + 旧客户端不炸。
                isHls = dto.streamType == STREAM_TYPE_HLS,
                proxyActivated = dto.proxyActivated,
                proxyExpiresAtSeconds = dto.proxyExpiresAt,
            )
        }.getOrDefault(fallback)
    }

    // 仅用于列表端点：约束在 List 上，成功但 data=null（如未来日期节目单）退回空列表，避免误抛。
    // 受体限定为 List 后无需 unchecked 强转，非列表响应（如 ReplayDto）编译期即不可误用。
    private fun <E> ApiResponse<List<E>>.dataOrThrow(what: String): List<E> {
        if (code != 0) {
            throw IllegalStateException("获取${what}失败：${message ?: "code=$code"}")
        }
        return data ?: emptyList()
    }

    companion object {
        /** 列表分页的页大小。须与服务端 `gateway.go:defaultPageSize` 一致。 */
        const val PAGE_SIZE = 60

        /** 收藏跨地区刷新时的最大并发请求数，平衡刷新速度与弱 TV 负载。 */
        private const val MAX_CONCURRENT_REFRESH = 4

        private const val HTTP_NOT_FOUND = 404

        private const val QINGTING_DEFAULT_PROVINCE = 407L

        /** TuneIn 美国节点：radiotime guide_id r100436 去掉前缀 r，与服务端 tiGuideToCode 同规则。 */
        private const val TUNEIN_DEFAULT_PROVINCE = 100436L

        /** /v1/stream 的 streamType 取值，须与服务端 `handlers.go:streamTypeHLS` 一致。 */
        private const val STREAM_TYPE_HLS = "hls"
    }
}
