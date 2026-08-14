package cn.radio.tv.data.remote

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
     * 按 contentId 批量取电台快照（主要是 subtitle = 当前节目名）。
     *
     * [contentIds] 为逗号分隔，单次上限 200（服务端夹住）。返回顺序随请求，**查不到的略过** ——
     * 电台下架、换地区都是常态，调用方据此保留自己那份旧快照即可。
     *
     * 三来源的上游都没有「按 id 查单台」的接口；服务端 `scopeCache` 里本就有该范围的全量
     * 索引，查表即可（见 radio-proxy `gateway.go:fetchChannelsByIDs`）。
     */
    @GET("v1/channels/by-ids")
    suspend fun getChannelsByIds(
        @Query("source") source: String,
        @Query("provinceCode") provinceCode: Long,
        @Query("contentIds") contentIds: String,
        @Query("scope") scope: String? = null,
    ): ApiResponse<List<Channel>>

    /**
     * 按名称搜台。[q] 支持中文原文、拼音首字母、全拼与英文子串；匹配与多音字处理
     * 全在服务端（见 radio-proxy `search.go`），客户端不另写一份。分页与 [getChannels] 同口径。
     *
     * [scope]=[SCOPE_CATALOG] 时搜当前来源的**整份目录**（跨地区跨分类），
     * [provinceCode] 与 [categoryId] 被服务端忽略 —— APP 的搜索走这一档。
     * 传 null 则退回「来源 + 地区 + 分类」范围（旧行为，旧网关也只认这一档）。
     */
    @GET("v1/search")
    suspend fun searchChannels(
        @Query("source") source: String,
        @Query("provinceCode") provinceCode: Long,
        @Query("categoryId") categoryId: String,
        @Query("q") q: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("scope") scope: String? = null,
    ): ApiResponse<List<Channel>>

    /** 节目单。[date] 为 yyyy/MM/dd（北京时间，见 [cn.radio.tv.data.source.BEIJING_TIME_ZONE]）。TuneIn 无节目单,客户端不调此端点。 */
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

    /**
     * 兑换 / 换绑「TuneIn 代理」激活码。设备标识由 OkHttp 拦截器统一挂在
     * `X-Device-Hash` 头上（见 [NetworkModule]），故这里只传码。
     *
     * 失败时服务端回非 2xx + [ApiResponse.code] 细分原因（见 [ActivationCodes]），
     * Retrofit 会抛 `HttpException`，由调用方按业务码取文案。
     */
    @POST("v1/activation/redeem")
    suspend fun redeemActivation(@Body body: RedeemRequest): ApiResponse<ActivationStatusDto>

    /** 查当前设备的激活状态。未激活/已过期都返回 `activated=false`。 */
    @GET("v1/activation/status")
    suspend fun getActivationStatus(
        @Query("deviceHash") deviceHash: String,
    ): ApiResponse<ActivationStatusDto>

    /**
     * 自助解绑本设备：本设备立即失去代理权限，激活码回到未使用状态，
     * **剩余有效期不变**，可在任意设备重新兑换。
     *
     * 返回的是与状态查询同结构的快照（解绑后必然 `activated=false`）。
     */
    @POST("v1/activation/unbind")
    suspend fun unbindActivation(@Body body: UnbindRequest): ApiResponse<ActivationStatusDto>

    companion object {
        /**
         * `?scope=` 的唯一取值：**当前来源的整份目录**，服务端忽略 provinceCode 与 categoryId。
         * 只有 `/v1/search` 与 `/v1/channels/by-ids` 认（服务端 `gateway.go:catalogScope`）。
         *
         * 为什么要这个参数，而不是传 `provinceCode = 0` 去凑：那个 0 对三个来源的意思
         * **各不相同** —— 蜻蜓 / TuneIn 是「全部地区」哨兵，云听却是上游真实的「国家」桶
         * （19 台，而整份目录 943 台）。传 0 求整份目录对云听是错的，且**没有任何报错**：
         * 搜索照常出结果，只是九百多台里只搜得到十九台。
         *
         * 旧网关不认这个参数，会照旧按 provinceCode / categoryId 过滤 —— 故**必须先部署
         * 服务端再发 APP**，反过来云听的搜索会静默退化成只搜国家桶。
         */
        const val SCOPE_CATALOG = "catalog"
    }
}

/** /v1/activation/redeem 的请求体。[deviceHash] 见 [cn.radio.tv.data.device.DeviceIdProvider]。 */
@Serializable
data class RedeemRequest(val code: String, val deviceHash: String)

/** /v1/activation/unbind 的请求体。服务端只解绑这个设备自己绑的码。 */
@Serializable
data class UnbindRequest(val deviceHash: String)

/**
 * 激活状态。[expiresAt] 为 **epoch 秒**（服务端 unix 时间戳），
 * 注意与本项目其余时间字段的毫秒口径不同 —— 展示前要 ×1000。
 */
@Serializable
data class ActivationStatusDto(
    val activated: Boolean = false,
    val expiresAt: Long = 0,
    val code: String = "",
)

/**
 * 激活相关的业务错误码，须与 radio-proxy `gateway.go` 的常量一致。
 *
 * 1005 曾是 REBIND_COOLING（换绑冷却），冷却机制已移除，**空着不复用** ——
 * 服务端那边也留了空位，两边要一致。
 */
object ActivationCodes {
    const val BAD_DEVICE = 1001
    const val CODE_NOT_FOUND = 1002
    const val CODE_REVOKED = 1003
    const val CODE_EXPIRED = 1004
    const val RATE_LIMITED = 1006
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
 * [directUrl] 是 TuneIn 专有的可选字段：解析出的上游真实地址。默认（「TuneIn 代理」开关
 * 关闭）用它，绕开 [url] 的服务端透传以省带宽。其余来源服务端固定回空串；旧网关部署没有
 * 这个字段，默认值 `""` 让两种情况都自然回退到 [url]（见 GatewaySource.resolveStream）。
 *
 * [streamType] 取 "hls" / "progressive"。保持 String 而非 enum：遇到服务端将来新增的
 * 取值时不会反序列化失败，未知值由调用方按 progressive 兜底（见 GatewaySource.resolveStream）。
 *
 * [proxyActivated] / [proxyExpiresAt] 是本设备的「TuneIn 代理」激活状态，**仅供 UI 参考**
 * （开关是否可用、到期提示）。真正的拦截在服务端 `/proxy`、`/seg`，未激活时那两个端点直接
 * 403 —— 这里即使被篡改成 true 也换不来透传。默认值保证旧网关（无此字段）不炸。
 */
@Serializable
data class StreamDto(
    val url: String = "",
    val directUrl: String = "",
    val streamType: String = "progressive",
    val proxyActivated: Boolean = false,
    val proxyExpiresAt: Long = 0,
)
