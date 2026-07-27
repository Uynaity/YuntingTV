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

    /**
     * 在「当前来源 + 地区 + 分类」范围内按名称搜台。[q] 支持中文原文、拼音首字母、
     * 全拼与英文子串；匹配与多音字处理全在服务端（见 radio-proxy `search.go`），
     * 客户端不另写一份。分页与 [getChannels] 同口径。
     */
    @GET("v1/search")
    suspend fun searchChannels(
        @Query("source") source: String,
        @Query("provinceCode") provinceCode: Long,
        @Query("categoryId") categoryId: String,
        @Query("q") q: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
    ): ApiResponse<List<Channel>>

    /** 节目单。[date] 为 yyyy/MM/dd（设备本地时区）。TuneIn 无节目单,客户端不调此端点。 */
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

    /**
     * 起播前取直播地址与流类型。播放器必须在**发起请求之前**选定解析器，
     * 而 TuneIn 的类型只有服务端调过上游才知道，故单独一跳把它提前取到。
     * 见 [StreamDto]。
     */
    @GET("v1/stream")
    suspend fun getStream(
        @Query("source") source: String,
        @Query("contentId") contentId: String,
    ): ApiResponse<StreamDto>
}

/** /v1/replay 的返回体。独立小 DTO,仅此一处用。 */
@Serializable
data class ReplayDto(val replayUrl: String = "")

/**
 * /v1/stream 的返回体。
 *
 * [url] 为空是服务端约定：表示「沿用你手上的 [Channel.playUrlLow]」。云听的播放地址
 * 由上游列表 JSON 直接携带，服务端此处没有它，故只下发类型。
 *
 * [streamType] 取 "hls" / "progressive"。保持 String 而非 enum：遇到服务端将来新增的
 * 取值时不会反序列化失败，未知值由调用方按 progressive 兜底（见 GatewaySource.resolveStream）。
 */
@Serializable
data class StreamDto(val url: String = "", val streamType: String = "progressive")
