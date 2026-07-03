package cn.radio.tv.data.remote

import cn.radio.tv.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** 构建 Retrofit / OkHttp，分别提供云听与蜻蜓FM 两套 API 单例。 */
object NetworkModule {

    private const val YUNTING_BASE_URL = "https://ytmsout.radio.cn/"
    private const val QINGTING_BASE_URL = "https://rapi.qtfm.cn/"

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

    // 云听需注入签名头；蜻蜓接口无需鉴权，故用各自独立的 client。
    private val yunTingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(SignInterceptor())
            .withDebugLogging()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val qingTingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .withDebugLogging()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val yunTingApi: RadioApi by lazy {
        Retrofit.Builder()
            .baseUrl(YUNTING_BASE_URL)
            .client(yunTingClient)
            .addConverterFactory(converter)
            .build()
            .create(RadioApi::class.java)
    }

    val qingTingApi: QingTingApi by lazy {
        Retrofit.Builder()
            .baseUrl(QINGTING_BASE_URL)
            .client(qingTingClient)
            .addConverterFactory(converter)
            .build()
            .create(QingTingApi::class.java)
    }
}
