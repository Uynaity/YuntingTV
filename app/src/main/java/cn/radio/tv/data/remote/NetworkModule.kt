package cn.radio.tv.data.remote

import cn.radio.tv.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** 构建 Retrofit / OkHttp：统一网关 [gatewayApi] 与更新分发 [yecaoApi] 两个单例。 */
object NetworkModule {

    /** 统一网关地址（集中一处，便于切换环境）。三来源均经此取数。 */
    private const val GATEWAY_BASE_URL = "https://radio.hku.wtf/"

    /** yecao 分发站；APK 下载地址 = 此 base + 接口返回的相对 download_url。 */
    const val YECAO_BASE_URL = "https://yecao.app/"

    /** 设备标识请求头，须与 radio-proxy `activation.go:deviceHashHeader` 一致。 */
    const val DEVICE_HASH_HEADER = "X-Device-Hash"

    /**
     * 设备哈希。由 [cn.radio.tv.RadioApp.onCreate] 在启动时灌入一次。
     *
     * 放在这里而不是把它一路穿过 GatewayApi → GatewaySource → RadioSource 的方法签名：
     * 它是**整个进程恒定的一个值**，不是随调用变化的参数，穿参只会让四层签名各多一个
     * 到处透传的 String。空串表示设备标识不可用（见 [cn.radio.tv.data.device.DeviceIdProvider]），
     * 此时不挂头，服务端视为未激活。
     */
    @Volatile
    var deviceHash: String = ""

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
        }
    }

    // 网关：普通请求，鉴权/签名全在服务端消化，客户端免签。
    // 唯一的例外是设备标识：「TuneIn 代理」的激活状态按设备判定，服务端要认出是谁。
    private val gatewayClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val hash = deviceHash
                val req = if (hash.isEmpty()) {
                    chain.request()
                } else {
                    chain.request().newBuilder().header(DEVICE_HASH_HEADER, hash).build()
                }
                chain.proceed(req)
            }
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
