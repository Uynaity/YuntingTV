package cn.radio.tv.player

import androidx.media3.common.PlaybackException
import kotlin.math.min

/**
 * 播放错误的可重试判定与退避策略。
 *
 * 旧实现对**所有** [PlaybackException] 一视同仁：进入 60 秒恢复窗口，每次错误后
 * 3 秒重建 MediaItem 并 prepare。对 404、格式不支持、解析失败这类必然立刻再失败的错误，
 * 效果就是 60 秒内约 20 次无意义的重建 —— 每次都要重新建连、重新解析，在弱设备上
 * 是持续的 CPU、网络与主线程开销，而且用户看到的只是一直"缓冲中"。
 *
 * 这里按 Media3 的错误码分组判定。分组以千位为界，是 Media3 的公开约定：
 * 1000 系（运行时）、2000 系（IO）、3000 系（内容解析）、4000 系（解码）、
 * 5000 系（音频渲染）、6000 系（DRM）。
 *
 * 纯函数，无 Android 运行时依赖，可直接在 JVM 上验证。
 */
object PlaybackErrorPolicy {

    /** 首次重试延迟。 */
    const val BASE_DELAY_MS = 2_000L

    /** 退避上限。 */
    const val MAX_DELAY_MS = 30_000L

    /** 抖动比例：避免断网恢复时多设备同时重连形成尖峰。 */
    const val JITTER_FRACTION = 0.3

    /** 放弃前的最大重试次数。 */
    const val MAX_ATTEMPTS = 6

    /**
     * 该错误是否值得重试。
     *
     * @param httpStatus 当错误是 [PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS] 时的响应码。
     */
    fun isRetryable(errorCode: Int, httpStatus: Int? = null): Boolean = when (errorCode) {
        // 直播窗口滑出：重新 prepare 即可回到窗口内，是最典型的可恢复错误。
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> true

        // 地址本身无效或无权访问：重试多少次都一样。
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> false

        // 4xx 是客户端错误，重试无意义；408 请求超时与 429 限流除外，退避后可再来。
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            httpStatus == null || httpStatus !in 400..499 || httpStatus == 408 || httpStatus == 429

        else -> when (errorCode / 1000) {
            3, 4, 6 -> false  // 解析 / 解码 / DRM：内容或设备能力问题，重试不会变好
            2 -> true         // 其余 IO 归为网络抖动
            else -> false     // 未知错误保守处理，不进重试循环
        }
    }

    /**
     * 第 [attempt] 次重试（从 0 起）的延迟：指数退避 + 抖动，有上限。
     *
     * @param jitter [0,1) 的随机数，由调用方注入以便测试可确定。
     */
    fun backoffMs(attempt: Int, jitter: Double): Long {
        require(jitter in 0.0..1.0) { "jitter 须在 [0,1]，实际 $jitter" }
        val safeAttempt = attempt.coerceIn(0, MAX_ATTEMPTS)
        // 用 Long 逐次翻倍而非位移，避免大 attempt 时溢出。
        var delay = BASE_DELAY_MS
        repeat(safeAttempt) { delay = min(delay * 2, MAX_DELAY_MS) }
        return delay + (delay * JITTER_FRACTION * jitter).toLong()
    }
}
