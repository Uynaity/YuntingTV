package cn.radio.tv.data.remote

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 统一网关（radio-proxy）接口。三来源经服务端归一为 APP 共享模型 +
 * 云听式 [ApiResponse]（code==0 成功），APP 只认这一套契约。
 * [source] 取值对齐 [cn.radio.tv.data.source.RadioSourceType.key]。
 */
interface GatewayApi {

    @GET("v1/provinces")
    suspend fun getProvinces(@Query("source") source: String): ApiResponse<List<Province>>

    @GET("v1/categories")
    suspend fun getCategories(@Query("source") source: String): ApiResponse<List<Category>>

    /** [limit]=0 表示不分页取全量（服务端约定）；否则单页上限 300，超出被服务端夹住。 */
    @GET("v1/channels")
    suspend fun getChannels(
        @Query("source") source: String,
        @Query("provinceCode") provinceCode: Long,
        @Query("categoryId") categoryId: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
    ): ApiResponse<List<Channel>>

    /** 节目单。[date] 为 yyyy/MM/dd（设备本地时区）。全球电台无节目单,客户端不调此端点。 */
    @GET("v1/programs")
    suspend fun getPrograms(
        @Query("source") source: String,
        @Query("contentId") contentId: String,
        @Query("date") date: String,
    ): ApiResponse<List<Program>>

    /** 蜻蜓回放两段式解析（仅蜻蜓）。返回 { replayUrl }。 */
    @GET("v1/replay")
    suspend fun getReplay(
        @Query("source") source: String,
        @Query("contentId") contentId: String,
        @Query("programId") programId: String,
    ): ApiResponse<ReplayDto>
}

/** /v1/replay 的返回体。独立小 DTO,仅此一处用。 */
@Serializable
data class ReplayDto(val replayUrl: String = "")
