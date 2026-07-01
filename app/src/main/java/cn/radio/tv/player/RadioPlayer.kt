package cn.radio.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
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

    /** 交给 MediaSession 的播放器；经 session 设置的 MediaItem(URI) 按下方 HLS 工厂定制解析。 */
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            HlsMediaSource.Factory(httpDataSourceFactory).setExtractorFactory(hlsExtractorFactory),
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
