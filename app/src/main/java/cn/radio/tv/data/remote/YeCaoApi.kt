package cn.radio.tv.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * yecao.app 客户端分发接口（检查更新）。
 * secret 头由 [NetworkModule] 的拦截器统一注入，token（R2B1）硬编码进 path。
 */
interface YeCaoApi {

    /** 解析当前渠道的最新版本信息。[clientTime] 为**秒级**时间戳（服务器校验时间偏差）。 */
    @GET("api/v1/client/resolve/R2B1")
    suspend fun resolve(
        @Query("protocol_version") protocolVersion: Int = 1,
        @Query("client_time") clientTime: Long,
    ): UpdateResolveResponse
}

// 接口返回 snake_case，用 @SerialName 映射；沿用 NetworkModule 的 Json{ignoreUnknownKeys=true}，只取所需字段。
@Serializable
data class UpdateResolveResponse(
    val code: Int = -1,
    val data: UpdateData? = null,
)

@Serializable
data class UpdateData(
    val apps: List<UpdateApp> = emptyList(),
)

@Serializable
data class UpdateApp(
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("version_name") val versionName: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
    val description: String = "",
    val size: Long = 0,
)
