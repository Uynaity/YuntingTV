package cn.radio.tv

import android.app.Application
import cn.radio.tv.perf.PerfCounters
import coil.EventListener
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options

/**
 * 自定义 Coil [ImageLoader]。此前全程使用 Coil 单例的默认配置。
 *
 * 两个目的：
 *
 * 1. **解码计数**（仅 debug）。"同一张图被解了几次"是本次重构的关键验收指标之一，
 *    而它只能从 ImageLoader 侧观测。
 *
 * 2. **内存缓存上限**。Coil 默认取可用堆的 25%，仅当系统上报 `isLowRamDevice` 时降到 15%。
 *    廉价电视盒子往往不正确上报该标志，于是按 25% 吃内存。这里统一按 15%（即 Android
 *    自己给低内存设备定的档位）。
 *
 *    需要说明：真机实测本项目的 Java 堆只有 20~30MB，图片内存缓存**当前不是瓶颈**
 *    （瓶颈在 GPU 侧的 GL 显存，见 ArtworkRepository 注释）。此项是给小堆设备的兜底，
 *    不是已验证的收益。
 */
class RadioApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .build()
            }
            .apply { if (BuildConfig.DEBUG) eventListener(DecodeCountingListener) }
            .build()

    private object DecodeCountingListener : EventListener {
        override fun decodeEnd(
            request: ImageRequest,
            decoder: Decoder,
            options: Options,
            result: DecodeResult?,
        ) {
            val size = result?.drawable?.let { "${it.intrinsicWidth}x${it.intrinsicHeight}" } ?: "?"
            PerfCounters.decode("$size ${request.data}")
        }
    }

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.15
    }
}
