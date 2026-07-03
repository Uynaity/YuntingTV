package cn.radio.tv.data.remote

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Province
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** radio.cn（云听）开放接口。 */
interface RadioApi {

    /** 全部省份（城市筛选）。 */
    @GET("web/appProvince/list/all")
    suspend fun getProvinces(): ApiResponse<List<Province>>

    /** 全部分类（类型筛选）。 */
    @GET("web/appCategory/list/all")
    suspend fun getCategories(): ApiResponse<List<Category>>

    /** 按地区 + 分类查询电台列表。 */
    @GET("web/appBroadcast/list")
    suspend fun getChannels(
        @Query("categoryId") categoryId: String,
        @Query("provinceCode") provinceCode: Long,
    ): ApiResponse<List<Channel>>

    /**
     * 某电台某天的节目单（回放）。[date] 为 yyyy/MM/dd。
     * 两 query 参数由 [cn.radio.tv.data.remote.SignInterceptor] 自动按 key 字母序签名。
     */
    @GET("web/appProgram/listByDate")
    suspend fun getPrograms(
        @Query("broadcastId") broadcastId: String,
        @Query("date") date: String,
    ): ApiResponse<List<YtProgram>>
}

@Serializable
data class YtProgram(
    val programName: String = "",
    val startTime: Long = 0,
    val endTime: Long = 0,
    val playUrlHigh: String = "",
    val playFlag: Int = 0,
)
