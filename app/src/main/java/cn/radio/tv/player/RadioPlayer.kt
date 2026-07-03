package cn.radio.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * 基于 Media3 ExoPlayer 的 HLS 播放器封装，供 [PlaybackService] 独占持有。
 * 通过 [exoPlayer] 交给 MediaSession，播放控制统一走 Player 接口（由 UI 侧 MediaController 驱动）。
 *
 * 网络卡顿/出错时不会立即停止，而是进入"恢复中"模式：在 [RETRY_WINDOW_MS] 窗口内每隔
 * [RETRY_DELAY_MS] 自动重新 prepare 一次当前媒体，并把剩余秒数写入 [PlaybackBridge.retrySeconds]
 * 供 UI 呈现"缓冲中…Ns"；窗口内成功恢复(STATE_READY)则继续播放，倒计时归零仍未恢复则暂停。
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

    /** 渐进式音频文件工厂：回放地址（蜻蜓 .aac / 云听 playUrlHigh）是普通文件，不是 HLS 播放列表。 */
    private val progressiveFactory = ProgressiveMediaSource.Factory(httpDataSourceFactory)

    /**
     * 按 URI 类型分派 MediaSource：直播是 HLS（.m3u8）走 [hlsFactory]，回放是渐进式音频走
     * [progressiveFactory]。此前固定用 HLS 工厂会把回放文件当播放列表解析，导致永远「缓冲中」。
     */
    private val mediaSourceFactory = object : MediaSource.Factory {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val uri = mediaItem.localConfiguration?.uri
            val isHls = uri != null && Util.inferContentType(uri) == C.CONTENT_TYPE_HLS
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
        // 启用音频焦点：别的应用抢焦点时自动暂停，本应用播放时也会请求焦点让其他应用暂停。
        // 用 MUSIC 而非 SPEECH：Media3 对 SPEECH 内容在“可压低”的短暂焦点丢失(如通知)时会直接暂停，
        // 而 MUSIC 则会压低音量(ducking)，通知结束后自动恢复原音量。
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
                    // 成功(恢复)播放，退出恢复模式。IDLE/ENDED 在恢复期间不处理，避免中间态。
                    if (playbackState == Player.STATE_READY) cancelRetry()
                }

                override fun onPlayerError(error: PlaybackException) {
                    scheduleRetry()
                }
            })
        }

    /** 发生播放错误时进入/维持恢复模式，并安排一次延迟重试。 */
    private fun scheduleRetry() {
        if (exoPlayer.currentMediaItem == null) return
        if (firstErrorAtMs == 0L) {
            // 首次出错：进入恢复模式，启动倒计时。
            firstErrorAtMs = SystemClock.elapsedRealtime()
            PlaybackBridge.retrySeconds.value = (RETRY_WINDOW_MS / 1000).toInt()
            mainHandler.postDelayed(countdownRunnable, 1_000L)
        }
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun retry() {
        if (exoPlayer.currentMediaItem == null) return
        exoPlayer.prepare()
    }

    /** 退出恢复模式，清除重试与倒计时。 */
    private fun cancelRetry() {
        firstErrorAtMs = 0L
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
        /** 错误后自动重试的总时长窗口：1 分钟。 */
        private const val RETRY_WINDOW_MS = 60_000L
        /** 每次重试的间隔。 */
        private const val RETRY_DELAY_MS = 3_000L
    }
}
