package cn.radio.tv.data.remote

import cn.radio.tv.BuildConfig
import cn.radio.tv.perf.PerfCounters
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** 构建 Retrofit / OkHttp，分别提供云听与蜻蜓FM 两套 API 单例。 */
object NetworkModule {

    /** 统一网关地址（集中一处，便于切换环境）。三来源均经此取数。 */
    private const val GATEWAY_BASE_URL = "https://radio.hku.wtf/"

    /** yecao 分发站；APK 下载地址 = 此 base + 接口返回的相对 download_url。 */
    const val YECAO_BASE_URL = "https://yecao.app/"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val converter = json.asConverterFactory("application/json".toMediaType())

    /** debug 构建下的日志拦截器（release 不挂，省去每请求的字符串拼接与 I/O）。 */
    private fun OkHttpClient.Builder.withDebugLogging() = apply {
        if (BuildConfig.DEBUG) {
            addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            // 请求扇出计数：冷启动/切台等场景发了多少请求，单测覆盖不到，只能在整机上数。
            addInterceptor(PerfCounters.interceptor)
        }
    }

    // 网关：普通请求，鉴权/签名全在服务端消化，客户端免签。
    private val gatewayClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .withDebugLogging()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // yecao 接口要求固定的 client-secret 头，缺失返回 401。
    private val yecaoClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("x-wgdc-client-secret", "udp-demo-secret")
                        .build(),
                )
            }
            .withDebugLogging()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val gatewayApi: GatewayApi by lazy {
        Retrofit.Builder()
            .baseUrl(GATEWAY_BASE_URL)
            .client(gatewayClient)
            .addConverterFactory(converter)
            .build()
            .create(GatewayApi::class.java)
    }

    val yecaoApi: YeCaoApi by lazy {
        Retrofit.Builder()
            .baseUrl(YECAO_BASE_URL)
            .client(yecaoClient)
            .addConverterFactory(converter)
            .build()
            .create(YeCaoApi::class.java)
    }
}
