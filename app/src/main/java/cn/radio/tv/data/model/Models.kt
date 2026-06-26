package cn.radio.tv.data.model

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
)

/**
 * 收藏的电台。云听未提供「按 id 查询电台」的接口，故收藏时除电台快照外
 * 一并记录其所属城市 [provinceCode]；打开收藏页时据此按城市重新拉取列表，
 * 用最新的 subtitle（节目单）刷新快照。
 */
@Serializable
data class FavoriteChannel(
    val channel: Channel,
    val provinceCode: Long,
)
