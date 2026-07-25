package cn.radio.tv.data.model

import cn.radio.tv.data.source.RadioSourceType
import kotlinx.serialization.Serializable

/** radio.cn 接口通用响应包装：{ code, message, data, extInfo } */
@Serializable
data class ApiResponse<T>(
    val code: Int = -1,
    val message: String? = null,
    val data: T? = null,
)

/** 省份（城市筛选）。provinceCode=0 表示「国家（全国台）」 */
@Serializable
data class Province(
    val provinceName: String,
    val provinceCode: Long,
    /** ISO 国家码。仅全球电台由网关下发；用于把国家名本地化为中文。云听/蜻蜓留空。 */
    val countryCode: String = "",
)

/** 分类（类型筛选）。id="0" 表示「全部」 */
@Serializable
data class Category(
    val id: String,
    val categoryName: String,
)

/**
 * 电台。仅保留业务关注的字段：
 * contentId/title/subtitle/image/playUrlLow
 */
@Serializable
data class Channel(
    val contentId: String,
    val title: String = "",
    val subtitle: String = "",
    val image: String = "",
    val playUrlLow: String = "",
    /** ISO 国家码（如 cn/jp）。仅全球电台由网关下发；云听/蜻蜓留空。用于无封面时拼国旗图。 */
    val countryCode: String = "",
)

/** 有国家码时拼国旗 CDN 图（尺寸/CDN 由客户端掌控）；无则空。 */
val Channel.flagUrl: String
    get() = if (countryCode.isNotBlank()) "https://flagcdn.com/w320/${countryCode.lowercase()}.png" else ""

/** 展示用图源：封面优先，空则退国旗，都无则空串（上层兜底 📻/塌缩）。 */
val Channel.displayImageUrl: String
    get() = image.ifBlank { flagUrl }

/**
 * 节目单中的一档节目（两来源映射到此共享模型，UI 只吃它）。
 * [id]：源内节目 id，蜻蜓按需解析回放地址时用；云听无需故可为空。
 * [canReplay]：是否展示回放图标（判据各源本地计算，不发起 per-node 请求）。
 * [replayUrl]：已知回放地址（云听直接给）；空且 [canReplay] 时点击需二次解析（蜻蜓）。
 */
@Serializable
data class Program(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val canReplay: Boolean,
    val replayUrl: String = "",
)

/**
 * 收藏的电台。云听未提供「按 id 查询电台」的接口，故收藏时除电台快照外
 * 一并记录其所属城市 [provinceCode]；打开收藏页时据此按城市重新拉取列表，
 * 用最新的 subtitle（节目单）刷新快照。
 *
 * [source]：该收藏所属来源，用于跨源合并展示后的播放/节目单刷新路由。
 * 默认值仅为兼容老 JSON；[UserPreferences.favorites] 读取时会按存储 key 重新戳源，
 * 故无需数据迁移。
 */
@Serializable
data class FavoriteChannel(
    val channel: Channel,
    val provinceCode: Long,
    val source: RadioSourceType = RadioSourceType.YUNTING,
)
