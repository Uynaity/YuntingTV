package cn.radio.tv

import android.app.Application
import cn.radio.tv.data.device.DeviceIdProvider
import cn.radio.tv.data.remote.LegacyTls
import cn.radio.tv.data.remote.NetworkModule
import cn.radio.tv.data.remote.applyLegacyTls
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

/**
 * 自定义 Coil [ImageLoader]，替换默认单例配置，只为压低**内存缓存上限**。
 *
 * Coil 默认取可用堆的 25%，仅当系统上报 `isLowRamDevice` 时降到 15%。廉价电视盒子往往
 * 不正确上报该标志，于是按 25% 吃内存。这里统一按 15%（即 Android 自己给低内存设备定的档位）。
 *
 * 需要说明：真机实测本项目的 Java 堆只有 20~30MB，图片内存缓存**当前不是瓶颈**
 * （瓶颈在 GPU 侧的 GL 显存，见 ArtworkRepository 注释）。此项是给小堆设备的兜底，
 * 不是已验证的收益。
 */
class RadioApp : Application(), ImageLoaderFactory {

    /**
     * 设备哈希在这里算一次并灌进 [NetworkModule]。
     *
     * 必须在这里而不是首次用到时现算：[cn.radio.tv.player.RadioPlayer] 的
     * DataSource 工厂在构造时就把请求头定死了（同进程的 PlaybackService 起得比任何
     * 播放动作都早），晚一步就会有一批请求不带设备标识、被服务端当成未激活。
     * 取标识本身是毫秒级的本地调用，不涉网。
     */
    override fun onCreate() {
        super.onCreate()
        NetworkModule.deviceHash = DeviceIdProvider.hash(this)
        // 同一个时序要求：Media3 的 DataSource 底层是 HttpURLConnection，只能靠改全局默认
        // SSLSocketFactory 生效，必须赶在 PlaybackService 起播之前。API ≥ 25 上是空操作。
        LegacyTls.install()
    }

    /**
     * Coil 默认自建一个 OkHttpClient，走不到 [NetworkModule] 里配好的那两个，
     * 于是 `/img` 图标在 Android 6 上会单独踩一次证书信任的坑 —— 这里换成挂了
     * [applyLegacyTls] 的 client。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { OkHttpClient.Builder().applyLegacyTls().build() }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .build()
            }
            .build()

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.15
    }
}
