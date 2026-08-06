package cn.radio.tv.ui.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import cn.radio.tv.perf.PerfCounters
import cn.radio.tv.ui.artwork.ArtworkRepository.BLUR_SIZE
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一张封面加工出的全部视觉产物。 */
data class Artwork(
    val background: Color,
    val accent: Color,
    /** 预缩放 + 预模糊的背景底图；解码失败为 null（上层回退纯色）。 */
    val blurred: ImageBitmap?,
)

/** 缓存中保存的原始产物：与主题无关，回退色在取用时才套上。 */
private data class ArtworkData(
    val backgroundRgb: Int?,
    val accentRgb: Int?,
    val blurred: ImageBitmap?,
)

/**
 * 封面视觉产物的唯一出处：**每个 URL 只解码一次**，同时产出调色板与预模糊底图，
 * 结果按 URL 放入有界 LruCache。
 *
 * 全屏背景若无尺寸约束地解全屏原图、再叠 `scale(1.5f).blur(60.dp)` 是重灾区：
 * 真机实测（1080×2374，SDK 36）进全屏使 GL 显存从 17MB 升到 101MB，退出后不释放、
 * 反复进出稳定在约 230MB，整体 PSS 从 145MB 涨到 378MB —— 在低内存电视盒子上足以
 * 被 LMK 直接杀掉。这里统一改为上传一张约 192px 的小纹理。
 */
object ArtworkRepository {

    /** 解码与模糊的工作尺寸。放大铺满由 GPU 双线性完成，本就是模糊底图，无需高分辨率。 */
    const val BLUR_SIZE = 192

    /** 相对 [BLUR_SIZE] 的模糊半径。 */
    private const val BLUR_RADIUS = 12

    /** 缓存条目数。单条约 192×192×4 ≈ 147KB，8 条约 1.2MB，上限明确。 */
    private const val CACHE_ENTRIES = 8

    private val cache = LruCache<String, ArtworkData>(CACHE_ENTRIES)

    /** 串行化加载，避免同一 URL 被并发解码两次（面板与全屏可能同时请求）。 */
    private val mutex = Mutex()

    suspend fun load(
        context: Context,
        url: String?,
        backgroundFallback: Color,
        accentFallback: Color,
    ): Artwork {
        if (url.isNullOrBlank()) return Artwork(backgroundFallback, accentFallback, null)

        val data = mutex.withLock {
            cache.get(url) ?: decode(context, url)?.also { cache.put(url, it) }
        } ?: return Artwork(backgroundFallback, accentFallback, null)

        return Artwork(
            background = data.backgroundRgb?.let { Color(it) } ?: backgroundFallback,
            accent = data.accentRgb?.let { Color(it) } ?: accentFallback,
            blurred = data.blurred,
        )
    }

    private suspend fun decode(context: Context, url: String): ArtworkData? {
        val request = ImageRequest.Builder(context)
            .data(url)
            // 必须是 software bitmap：硬件位图无法读像素，Palette 与模糊都做不了。
            .allowHardware(false)
            .size(BLUR_SIZE)
            .build()
        val source = (context.imageLoader.execute(request) as? SuccessResult)
            ?.let { (it.drawable as? BitmapDrawable)?.bitmap }
            ?: return null

        PerfCounters.decode("artwork:${BLUR_SIZE}px")

        return withContext(Dispatchers.Default) {
            val palette = runCatching { Palette.from(source).generate() }.getOrNull()
            ArtworkData(
                backgroundRgb = palette?.pickBackground(),
                accentRgb = palette?.pickAccent(),
                blurred = source.blurredCopy(),
            )
        }
    }

    /** 背景取深色系；过亮则向黑收敛，保证白字可读。 */
    private fun Palette.pickBackground(): Int? {
        val swatch = darkVibrantSwatch ?: darkMutedSwatch ?: vibrantSwatch ?: dominantSwatch
        val color = swatch?.rgb?.let { Color(it) } ?: return null
        return (if (color.luminance() > 0.5f) lerp(color, Color.Black, 0.5f) else color).toArgb()
    }

    /** 强调色取鲜艳系；过暗则向白提亮，保证在暗背景上可见。 */
    private fun Palette.pickAccent(): Int? {
        val swatch = vibrantSwatch ?: lightVibrantSwatch ?: lightMutedSwatch ?: dominantSwatch
        val color = swatch?.rgb?.let { Color(it) } ?: return null
        return (if (color.luminance() < 0.4f) lerp(color, Color.White, 0.6f) else color).toArgb()
    }

    private fun Bitmap.blurredCopy(): ImageBitmap? = runCatching {
        val w = width.coerceAtMost(BLUR_SIZE)
        val h = height.coerceAtMost(BLUR_SIZE)
        if (w < 2 || h < 2) return@runCatching null
        val scaled = scale(w, h, /* filter = */ true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        boxBlur(pixels, w, h, BLUR_RADIUS)
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}
