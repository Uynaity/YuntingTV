package cn.radio.tv.data.remote

import android.annotation.SuppressLint
import android.os.Build
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 给 Android 7.0 及以下补上 Let's Encrypt 的 ISRG 根证书。
 *
 * `radio.hku.wtf` 的证书由 Caddy 自动向 Let's Encrypt 申请，证书链最终锚在
 * ISRG Root X1/X2 上。这两个根是 Android 7.1（API 25）才进系统信任库的，
 * 而 Let's Encrypt 早年靠 DST Root CA X3 交叉签兼容老系统的那条链已于 2024 年停用。
 * 结果就是 API ≤ 24 的设备一律
 * `CertPathValidatorException: Trust anchor for certification path not found`。
 *
 * 没走 Network Security Config 的 `<trust-anchors>`：那个属性是 API 24 才引入的，
 * 对 Android 6 无效，正好覆盖不到出问题的那批设备。
 *
 * 根证书以 PEM 常量内嵌而不是放 `res/raw`：[NetworkModule] 是没有 Context 的 object，
 * 走资源就得像 `deviceHash` 那样再从 [cn.radio.tv.RadioApp] 灌一次。两个根合计约 2.5KB。
 */
internal object LegacyTls {

    /**
     * SSL 工厂与信任管理器；API ≥ 25 为 null，调用方一律 no-op，高版本行为不受影响。
     *
     * 用 lazy 而不是启动时初始化：高版本上连证书解析都不会发生。
     */
    val config: Pair<SSLSocketFactory, X509TrustManager>? by lazy {
        // ISRG Root X1 自 API 25 进入系统信任库，API 24 及以下都缺。
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) return@lazy null
        val tm = CompositeTrustManager(systemTrustManager(), bundledTrustManager())
        val sf = SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<X509TrustManager>(tm), null) }
            .socketFactory
        sf to tm
    }

    /**
     * 改写 [HttpsURLConnection] 的全局默认工厂，覆盖走 `HttpURLConnection` 的调用方。
     *
     * Media3 的 `DefaultHttpDataSource`（见 [cn.radio.tv.player.RadioPlayer]）没有
     * SSLSocketFactory 注入点，只能从全局默认值下手 —— `/proxy` `/seg` 的播放流全在这条路上。
     * 必须在 PlaybackService 起来之前调用。
     */
    fun install() {
        config?.let { (sf, _) -> HttpsURLConnection.setDefaultSSLSocketFactory(sf) }
    }

    private fun systemTrustManager(): X509TrustManager = trustManagerOf(null)

    /** 内置的两个 ISRG 自签根；[cn.radio.tv.data.remote.LegacyTlsTest] 对其指纹有断言。 */
    internal val bundledRoots: List<X509Certificate> by lazy {
        CertificateFactory.getInstance("X.509")
            .generateCertificates(ISRG_ROOTS.byteInputStream())
            .map { it as X509Certificate }
    }

    private fun bundledTrustManager(): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        bundledRoots.forEachIndexed { index, root -> store.setCertificateEntry("isrg-$index", root) }
        return trustManagerOf(store)
    }

    /** `null` 表示用平台默认信任库。 */
    private fun trustManagerOf(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /**
     * ISRG Root X1（RSA 4096，2035 到期）与 ISRG Root X2（ECDSA P-384，2040 到期），两个自签根。
     *
     * 当前线上是 ECDSA 链，只有 X2 用得上；X1 是给 Caddy 续期时切回 RSA 链留的余量。
     * SHA-256 指纹（与 letsencrypt.org 公布一致，LegacyTlsTest 里有断言）：
     * - X1 `96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6`
     * - X2 `69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470`
     */
    private val ISRG_ROOTS = """
        -----BEGIN CERTIFICATE-----
        MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
        TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
        cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
        WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
        ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
        MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
        h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
        0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
        A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
        T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
        B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
        B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
        KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
        OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
        jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
        qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
        rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
        HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
        hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
        ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
        3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
        NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
        ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
        TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
        jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
        oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
        4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
        mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
        emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
        -----END CERTIFICATE-----
        -----BEGIN CERTIFICATE-----
        MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw
        CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg
        R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00
        MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT
        ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw
        EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW
        +1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9
        ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
        AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI
        zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW
        tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1
        /q4AaOeMSQ+2b1tbFfLn
        -----END CERTIFICATE-----
    """.trimIndent()
}

/**
 * 系统信任优先，只有系统拒绝时才拿内置根再试一次。
 *
 * 另一种写法是把系统根和内置根一起塞进一个新 KeyStore，换回平台原生的 TrustManagerImpl。
 * 在 Android 上行不通：系统 TrustManagerImpl 背后是 TrustedCertificateStore 而不是 KeyStore，
 * `getAcceptedIssuers()` 恒返回空数组，根本枚举不到系统根 —— 那样合出来的 KeyStore
 * 只有内置的两个 ISRG 根，`yecao.app` 这类走别的 CA 的域名会被一起误伤。
 *
 * 委托没有这个问题：系统能验通的链永远先由系统验通，内置根纯粹是补充，不放宽任何其它信任。
 * 代价是它不是平台原生的 TrustManagerImpl，OkHttp 拿不到 `X509TrustManagerExtensions`
 * 那条 host-aware 校验路径，会退到通用校验；只发生在 API ≤ 24。
 */
// lint 的 CustomX509TrustManager 防的是「自定义 TM 把校验整个废掉」。这里没有放宽任何东西：
// 系统那条校验一次不落，内置根只是在系统拒绝之后多给一次机会，两边都拒就照样抛。
@SuppressLint("CustomX509TrustManager")
internal class CompositeTrustManager(
    private val system: X509TrustManager,
    private val bundled: X509TrustManager,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (systemRejected: CertificateException) {
            try {
                bundled.checkServerTrusted(chain, authType)
            } catch (bundledRejected: CertificateException) {
                // 两边都不认就抛内置那条，系统那条挂成 suppressed 一并带出去，排查时两个原因都在。
                // 用 addSuppressed 而不是 initCause：异常自带 cause 时 initCause 会直接抛
                // IllegalStateException，把「证书不被信任」变成一个更难看懂的错。
                bundledRejected.addSuppressed(systemRejected)
                throw bundledRejected
            }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    // ponytail: Android 的系统 TrustManagerImpl 用 TrustedCertificateStore 而不是 KeyStore，
    // getAcceptedIssuers() 恒返回空数组 —— 所以这里实际只有内置的两个 ISRG 根。
    // 握手不受影响（走的是上面的 checkServerTrusted 委托），但 OkHttp 拿不到
    // X509TrustManagerExtensions 时会退到 BasicTrustRootIndex，那条路是按 acceptedIssuers
    // 建索引的。当前无害：OkHttp 只在配了 CertificatePinner 时才调链清理，本项目没配。
    // 一旦要加 CertificatePinner，得先把系统根真正枚举出来（读 /system/etc/security/cacerts），
    // 否则 yecao.app 这类非 ISRG 链会在清理阶段失败。
    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + bundled.acceptedIssuers
}

/** 给 OkHttp 挂上 [LegacyTls]；API ≥ 25 上什么都不做。 */
internal fun OkHttpClient.Builder.applyLegacyTls() = apply {
    LegacyTls.config?.let { (sf, tm) -> sslSocketFactory(sf, tm) }
}
