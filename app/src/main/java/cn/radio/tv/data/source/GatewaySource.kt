package cn.radio.tv.data.source

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.remote.GatewayApi
import cn.radio.tv.data.remote.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 统一网关数据源。三来源经服务端归一为同一套模型与响应格式，故客户端不再每源一个适配器：
 * 单个 [GatewaySource] 持一个 [type]，每请求带 `source=type.key`。来源差异（签名、地址拼接、
 * 去「台」后缀、回放两段式）全部在服务端消化。
 *
 * [RadioViewModel] 仍按 `Map<RadioSourceType, RadioSource>` 路由，故每个 type 装一个轻量实例
 * （仅各持一个枚举，共享同一 [api]）。
 */
class GatewaySource(
    override val type: RadioSourceType,
    private val api: GatewayApi = NetworkModule.gatewayApi,
) : BaseRadioSource() {

    override val defaultProvinceCode: Long = when (type) {
        // 蜻蜓无「全部地区」，默认「网络台」。
        RadioSourceType.QINGTING -> QINGTING_DEFAULT_PROVINCE
        // TuneIn 服务端支持「全部」，但那是全球两三万台，首屏没必要。默认美国节点。
        RadioSourceType.TUNEIN -> TUNEIN_DEFAULT_PROVINCE
        // 云听默认「全部」(0)。
        else -> super.defaultProvinceCode
    }

    override suspend fun fetchProvinces(): List<Province> = withContext(Dispatchers.IO) {
        api.getProvinces(type.key).dataOrThrow("省份")
    }

    override suspend fun fetchCategories(): List<Category> = withContext(Dispatchers.IO) {
        api.getCategories(type.key).dataOrThrow("分类")
    }

    override suspend fun fetchChannels(
        categoryId: String,
        provinceCode: Long,
        offset: Int,
        limit: Int,
    ): List<Channel> = withContext(Dispatchers.IO) {
        api.getChannels(type.key, provinceCode, categoryId, offset, limit).dataOrThrow("电台列表")
    }

    override suspend fun searchChannels(
        q: String,
        categoryId: String,
        provinceCode: Long,
        offset: Int,
        limit: Int,
    ): List<Channel> = withContext(Dispatchers.IO) {
        api.searchChannels(type.key, provinceCode, categoryId, q, offset, limit)
            .dataOrThrow("搜索结果")
    }

    /**
     * 某天节目单：date 为北京时间的 yyyy/MM/dd（见 [BEIJING_TIME_ZONE]）。TuneIn 无节目单,
     * 直接短路返回空（省一次网关请求；网关对该源亦返回空,取其一即可）。
     */
    override suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program> {
        if (type == RadioSourceType.TUNEIN) return emptyList()
        return withContext(Dispatchers.IO) {
            // SimpleDateFormat 非线程安全，日期快切会并发触发，故每次新建。
            // Locale.US：这是给机器看的报文字段，不能被本地历法/数字形态改写。
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.US)
                .apply { timeZone = BEIJING_TIME_ZONE }
                .format(dayStartMillis)
            api.getPrograms(type.key, channel.contentId, date).dataOrThrow("节目单")
        }
    }

    /** 回放：非蜻蜓地址已随节目单下发（[Program.replayUrl]）；蜻蜓走网关两段式解析。 */
    override suspend fun resolveReplayUrl(channel: Channel, program: Program): String {
        if (type != RadioSourceType.QINGTING) return program.replayUrl
        return withContext(Dispatchers.IO) {
            runCatching {
                api.getReplay(type.key, channel.contentId, program.id).data?.replayUrl.orEmpty()
            }.getOrDefault("")
        }
    }

    /**
     * 向网关问地址与流类型。三来源走同一条路（服务端按 source 分派），
     * 这里不写来源分支。
     *
     * 失败不阻断播放：超时、网关不可达、旧版网关无此端点（404）时，退回
     * 「沿用 playUrlLow + 渐进式」，即修复前的既有行为。类型不确定不该比放不出声更严重。
     */
    override suspend fun resolveStream(channel: Channel): ResolvedStream =
        withContext(Dispatchers.IO) {
            val fallback = ResolvedStream(channel.playUrlLow, isHls = false)
            runCatching {
                val dto =
                    api.getStream(type.key, channel.contentId).data ?: return@runCatching fallback
                ResolvedStream(
                    // url 为空是服务端约定：沿用客户端手上的地址（云听如此）。
                    url = dto.url.ifEmpty { channel.playUrlLow },
                    // 未知取值按 progressive 兜底，保证新服务端 + 旧客户端不炸。
                    isHls = dto.streamType == STREAM_TYPE_HLS,
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

    private companion object {
        const val QINGTING_DEFAULT_PROVINCE = 407L

        /** TuneIn 美国节点：radiotime guide_id r100436 去掉前缀 r，与服务端 tiGuideToCode 同规则。 */
        const val TUNEIN_DEFAULT_PROVINCE = 100436L

        /** /v1/stream 的 streamType 取值，须与服务端 `handlers.go:streamTypeHLS` 一致。 */
        const val STREAM_TYPE_HLS = "hls"
    }
}
