package cn.radio.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.random.Random
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import cn.radio.tv.player.RadioPlayer.Companion.RETRY_WINDOW_MS

/**
 * 基于 Media3 ExoPlayer 的 HLS 播放器封装，供 [PlaybackService] 独占持有。
 * 通过 [exoPlayer] 交给 MediaSession，播放控制统一走 Player 接口（由 UI 侧 MediaController 驱动）。
 *
 * 网络卡顿/出错时不会立即停止，而是进入"恢复中"模式：在 [RETRY_WINDOW_MS] 窗口内按
 * [PlaybackErrorPolicy] 的指数退避重新 prepare 当前媒体，并把剩余秒数写入
 * [PlaybackBridge.retrySeconds] 供 UI 呈现"缓冲中…Ns"；窗口内成功恢复(STATE_READY)则继续播放，
 * 倒计时归零或超出最大重试次数则暂停。
 *
 * 只有 [PlaybackErrorPolicy.isRetryable] 判定可恢复的错误才进入该模式：
 * 404、格式不支持、解析失败等永久性错误直接放弃，不再做无意义的重建。
 */
@UnstableApi
class RadioPlayer(context: Context) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    /**
     * HLS 解复用工厂，忽略 H.264 视频流。
     *
     * 蜻蜓FM 的直播 TS 在 PMT 里声明了一条 H.264 视频 PID，却从不发送有效视频帧
     * （PesReader 持续报 "Unexpected start code prefix"）。ExoPlayer 默认会解析该 PID
     * 并等待视频样本就绪，导致永远停在「缓冲中」放不出声。忽略 H.264 流后只解音频。
     * 对云听等纯音频 TS 无影响。
     */
    private val hlsExtractorFactory = DefaultHlsExtractorFactory(
        DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM,
        /* exposeCea608WhenMissingDeclarations = */ false,
    )

    /** ExoPlayer 回调运行在主线程，重试也在主线程上发起。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 本轮恢复中首次出错的时间(elapsedRealtime)，0 表示当前未处于恢复模式。 */
    private var firstErrorAtMs = 0L

    /**
     * 本轮恢复所针对的媒体 URI。用于区分「重试重置同一条流」与「用户切到另一条流」：
     * 换到不同 URI 即视为切台，立即退出上一条流的恢复态，倒计时不跨流延续。
     */
    private var recoveringUri: Uri? = null

    /** 本轮恢复已发起的重试次数，驱动指数退避。 */
    private var retryAttempt = 0

    private val retryRunnable = Runnable { retry() }

    /** 恢复期间每秒触发，刷新倒计时；归零则放弃并暂停。 */
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (firstErrorAtMs == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - firstErrorAtMs
            val remaining = ((RETRY_WINDOW_MS - elapsed + 999) / 1000).toInt()
            if (remaining <= 0) {
                giveUp()
                return
            }
            PlaybackBridge.retrySeconds.value = remaining
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    /** HLS 直播工厂（含上面的 H.264 忽略定制）。 */
    private val hlsFactory = HlsMediaSource.Factory(httpDataSourceFactory)
        .setExtractorFactory(hlsExtractorFactory)

    /**
     * 渐进式音频文件工厂：回放地址（蜻蜓 .aac / 云听 playUrlHigh）是普通文件，不是 HLS 播放列表。
     *
     * 开启恒定码率跳转：蜻蜓回放是裸 ADTS(.aac)，无容器时长索引，默认 AdtsExtractor 给不出总时长
     * （TIME_UNSET），媒体卡片便无总时长/进度条。按码率+文件长度估算时长后即可显示并支持拖动。
     */
    private val progressiveFactory = ProgressiveMediaSource.Factory(
        httpDataSourceFactory,
        DefaultExtractorsFactory().setConstantBitrateSeekingAlwaysEnabled(true),
    )

    /**
     * 按类型分派 MediaSource：HLS 走 [hlsFactory]，普通音频流与回放文件走 [progressiveFactory]。
     * 判错任一方向都会卡死：固定用 HLS 工厂会把回放文件当播放列表解析，反之把 HLS 播放列表
     * 当音频流解码，两者表现都是永远「缓冲中」。
     *
     * 判据取「显式 mimeType 优先，其次 URI 后缀」。正常路径永远走 mimeType：类型由网关
     * `/v1/stream` 下发，经 RadioViewModel.mediaItemOf 显式写入。后缀分支只是防御——
     * 网关漏填时不至于全挂；不能倒过来依赖它，因为网关透传的地址形如 `/proxy/{id}` 无后缀，
     * 且实测存在 media_type=hls 但地址无 .m3u8 的台（BBC mediaselector）。
     */
    private val mediaSourceFactory = object : MediaSource.Factory {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val uri = mediaItem.localConfiguration?.uri
            val mime = mediaItem.localConfiguration?.mimeType
            val isHls = when {
                mime != null -> MimeTypes.APPLICATION_M3U8.equals(mime, ignoreCase = true)
                uri != null -> Util.inferContentType(uri) == C.CONTENT_TYPE_HLS
                else -> false
            }
            return if (isHls) hlsFactory.createMediaSource(mediaItem)
            else progressiveFactory.createMediaSource(mediaItem)
        }

        override fun getSupportedTypes(): IntArray =
            intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply {
            hlsFactory.setDrmSessionManagerProvider(provider)
            progressiveFactory.setDrmSessionManagerProvider(provider)
        }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply {
            hlsFactory.setLoadErrorHandlingPolicy(policy)
            progressiveFactory.setLoadErrorHandlingPolicy(policy)
        }
    }

    /** 交给 MediaSession 的播放器；MediaItem(URI) 按类型分派到 HLS / 渐进式工厂。 */
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        // 使用 MUSIC 音频属性，使短暂的音频焦点丢失采用 ducking 而不是暂停。
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) cancelRetry()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // 仅在媒体 URI 变化时结束上一条流的恢复状态。
                    val uri = mediaItem?.localConfiguration?.uri
                    if (recoveringUri != null && uri != recoveringUri) cancelRetry()
                }

                override fun onPlayerError(error: PlaybackException) {
                    // 按错误类型分流，不再对所有错误一律重试。
                    // 旧实现对 404 / 格式不支持 / 解析失败这类必然立刻再失败的错误，
                    // 也会在 60 秒窗口内重建约 20 次 MediaSource —— 纯粹的浪费，
                    // 而且用户只看到一直"缓冲中"。
                    val httpStatus = (error.cause as? InvalidResponseCodeException)?.responseCode
                    if (!PlaybackErrorPolicy.isRetryable(error.errorCode, httpStatus)) {
                        giveUp()
                        return
                    }
                    scheduleRetry()
                }
            })
        }

    /** 发生可重试的播放错误时进入/维持恢复模式，并按指数退避安排下一次重试。 */
    private fun scheduleRetry() {
        if (exoPlayer.currentMediaItem == null) return
        if (retryAttempt >= PlaybackErrorPolicy.MAX_ATTEMPTS) {
            giveUp()
            return
        }
        if (firstErrorAtMs == 0L) {
            firstErrorAtMs = SystemClock.elapsedRealtime()
            recoveringUri = exoPlayer.currentMediaItem?.localConfiguration?.uri
            PlaybackBridge.retrySeconds.value = (RETRY_WINDOW_MS / 1000).toInt()
            mainHandler.postDelayed(countdownRunnable, 1_000L)
        }
        // 指数退避 + 抖动：断流恢复时不再固定 3 秒猛冲，也避免多设备同时重连形成尖峰。
        val delay = PlaybackErrorPolicy.backoffMs(retryAttempt, Random.nextDouble())
        retryAttempt++
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delay)
    }

    private fun retry() {
        val item = exoPlayer.currentMediaItem ?: return
        // 重建同一 MediaItem，确保错误态的 HLS 播放列表跟踪器被重新创建。
        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
    }

    /** 退出恢复模式，清除重试与倒计时。 */
    private fun cancelRetry() {
        firstErrorAtMs = 0L
        recoveringUri = null
        retryAttempt = 0
        PlaybackBridge.retrySeconds.value = 0
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(countdownRunnable)
    }

    /** 倒计时归零仍未恢复：放弃重试并暂停。 */
    private fun giveUp() {
        cancelRetry()
        exoPlayer.playWhenReady = false
    }

    fun release() {
        cancelRetry()
        exoPlayer.release()
    }

    companion object {
        /** 错误后自动重试的总时长窗口：1 分钟。重试间隔由 [PlaybackErrorPolicy] 的退避决定。 */
        private const val RETRY_WINDOW_MS = 60_000L
    }
}
