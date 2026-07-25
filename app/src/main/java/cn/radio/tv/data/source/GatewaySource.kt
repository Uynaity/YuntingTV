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
 * （仅各持一个枚举，共享同一 [gatewayApi]）。
 */
class GatewaySource(
    override val type: RadioSourceType,
    private val api: GatewayApi = NetworkModule.gatewayApi,
) : BaseRadioSource() {

    override val defaultProvinceCode: Long = when (type) {
        // 蜻蜓无「全部地区」，默认「网络台」。
        RadioSourceType.QINGTING -> QINGTING_DEFAULT_PROVINCE
        // 全球电台无「全部」（一拉上万台），默认美国。
        RadioSourceType.RADIOBROWSER -> ccToProvinceCode("US")
        // 云听默认「全部」(0)。
        else -> super.defaultProvinceCode
    }

    override suspend fun fetchProvinces(): List<Province> = withContext(Dispatchers.IO) {
        api.getProvinces(type.key).dataOrThrow("省份").map { p ->
            // 全球电台的国家名用系统 CLDR 本地化为中文（如 US→美国）；无国家码保持原样。
            val cc = p.countryCode
            if (cc.isBlank()) return@map p
            val cn = Locale("", cc).getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
            // getDisplayCountry 无对应译名时会回吐国家码本身，此时保留服务端原英文名。
            if (cn.isBlank() || cn.equals(cc, ignoreCase = true)) p else p.copy(provinceName = cn)
        }
    }

    override suspend fun fetchCategories(): List<Category> = withContext(Dispatchers.IO) {
        api.getCategories(type.key).dataOrThrow("分类")
    }

    override suspend fun fetchChannels(categoryId: String, provinceCode: Long): List<Channel> =
        withContext(Dispatchers.IO) {
            api.getChannels(type.key, provinceCode, categoryId).dataOrThrow("电台列表")
        }

    /**
     * 某天节目单：date 用设备本地时区 yyyy/MM/dd。全球电台无节目单,直接短路返回空
     * （省一次网关请求；网关对该源亦返回空,取其一即可）。
     */
    override suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program> {
        if (type == RadioSourceType.RADIOBROWSER) return emptyList()
        return withContext(Dispatchers.IO) {
            // SimpleDateFormat 非线程安全，日期快切会并发触发，故每次新建。
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(dayStartMillis)
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

        /** ISO 国家码 → provinceCode，须与服务端 ccToCode 同规则（cc[0]*1000+cc[1]）。 */
        fun ccToProvinceCode(cc: String): Long =
            if (cc.length == 2) cc[0].code * 1000L + cc[1].code else 0L
    }
}
