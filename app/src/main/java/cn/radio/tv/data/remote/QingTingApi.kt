package cn.radio.tv.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 蜻蜓FM 开放接口（rapi.qtfm.cn）。响应统一为 { errcode, errmsg, data }，与云听不同，
 * 故单列一套 DTO，由 [cn.radio.tv.data.source.QingTingSource] 映射到共享业务模型。
 *
 * 「全部地区 / 全部分类」通过省略对应 query 参数实现（传 null Retrofit 即不拼接该参数）。
 */
interface QingTingApi {

    /** 地区（省级）列表。 */
    @GET("v4/regions")
    suspend fun getRegions(): QtResponse<QtList<QtRegion>>

    /** 频道分类列表（仅 channel 类型）。 */
    @GET("v4/categories")
    suspend fun getCategories(@Query("type") type: String = "channel"): QtResponse<QtList<QtCategory>>

    /**
     * 按地区 + 分类查询电台。两者均可空：null 表示「全部」（省略该参数）。
     * 单页拉取，[pagesize] 放大以一次取完常规组合。
     */
    @GET("v4/channels")
    suspend fun getChannels(
        @Query("region_id") regionId: Long?,
        @Query("category_id") categoryId: Long?,
        @Query("page") page: Int = 1,
        @Query("pagesize") pageSize: Int = 300,
    ): QtResponse<QtChannelPage>
}

/** 蜻蜓通用响应包装。errcode=0 为成功。 */
@Serializable
data class QtResponse<T>(
    val errcode: Int = -1,
    val errmsg: String? = null,
    val data: T? = null,
)

@Serializable
data class QtList<T>(val items: List<T> = emptyList())

@Serializable
data class QtChannelPage(
    val items: List<QtChannel> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pagesize: Int = 0,
)

@Serializable
data class QtRegion(val id: Long, val title: String = "")

@Serializable
data class QtCategory(val id: Long, val title: String = "")

@Serializable
data class QtChannel(
    val id: Long,
    val title: String = "",
    val cover: String = "",
    @SerialName("current_program") val currentProgram: QtProgram? = null,
)

@Serializable
data class QtProgram(val title: String = "")
