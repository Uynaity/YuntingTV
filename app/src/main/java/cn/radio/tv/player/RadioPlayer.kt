package cn.radio.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 基于 Media3 ExoPlayer 的 HLS 播放器封装。
 * 暴露 isPlaying / isBuffering / retrySeconds 状态供 UI 观察。
 *
 * 网络卡顿/出错时不会立即停止,而是进入"恢复中"模式:在 [RETRY_WINDOW_MS]
 * 窗口内每隔 [RETRY_DELAY_MS] 自动重新加载一次。整个恢复窗口内对 UI 始终
 * 呈现"缓冲中",并通过 [retrySeconds] 暴露剩余秒数倒计时;窗口内成功恢复
 * (STATE_READY)则继续播放,倒计时归零后仍未恢复则暂停。
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

    /** ExoPlayer 回调运行在主线程,重试也在主线程上发起。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前播放地址,错误后据此重新加载。 */
    private var currentUrl: String? = null

    /** 本轮恢复中首次出错的时间(elapsedRealtime),0 表示当前未处于恢复模式。 */
    private var firstErrorAtMs = 0L

    private val retryRunnable = Runnable { retry() }

    /** 恢复期间每秒触发,刷新倒计时;归零则放弃并暂停。 */
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (firstErrorAtMs == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - firstErrorAtMs
            val remaining = ((RETRY_WINDOW_MS - elapsed + 999) / 1000).toInt()
            if (remaining <= 0) {
                giveUp()
                return
            }
            _retrySeconds.value = remaining
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            // 成功(恢复)播放,退出恢复模式并清除缓冲态。
                            cancelRetry()
                            _isBuffering.value = false
                        }
                        Player.STATE_BUFFERING -> _isBuffering.value = true
                        // IDLE / ENDED:恢复期间维持"缓冲中",避免重试间隔的中间态
                        // 让 UI 在缓冲圈与暂停图标之间反复闪烁。
                        else -> if (firstErrorAtMs == 0L) _isBuffering.value = false
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    scheduleRetry()
                }
            })
        }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    /** 恢复中剩余倒计时秒数;0 表示当前未处于恢复模式(普通缓冲不显示倒计时)。 */
    private val _retrySeconds = MutableStateFlow(0)
    val retrySeconds: StateFlow<Int> = _retrySeconds

    /** 载入并播放一个 HLS 直播地址。 */
    fun play(playUrl: String) {
        if (playUrl.isBlank()) return
        currentUrl = playUrl
        cancelRetry()
        exoPlayer.playWhenReady = true
        startPlayback(playUrl)
    }

    private fun startPlayback(playUrl: String) {
        val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
            .setExtractorFactory(hlsExtractorFactory)
            .createMediaSource(MediaItem.fromUri(playUrl))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** 发生播放错误时进入/维持恢复模式,并安排一次延迟重试。 */
    private fun scheduleRetry() {
        if (currentUrl == null) return
        if (firstErrorAtMs == 0L) {
            // 首次出错:进入恢复模式,启动倒计时。
            firstErrorAtMs = SystemClock.elapsedRealtime()
            _retrySeconds.value = (RETRY_WINDOW_MS / 1000).toInt()
            mainHandler.postDelayed(countdownRunnable, 1_000L)
        }
        // 整个恢复窗口内维持"缓冲中",忽略重试过程的中间态。
        _isBuffering.value = true
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun retry() {
        val url = currentUrl ?: return
        startPlayback(url)
    }

    /** 退出恢复模式,清除重试与倒计时。 */
    private fun cancelRetry() {
        firstErrorAtMs = 0L
        _retrySeconds.value = 0
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(countdownRunnable)
    }

    /** 倒计时归零仍未恢复:放弃重试并暂停。 */
    private fun giveUp() {
        cancelRetry()
        exoPlayer.playWhenReady = false
        _isBuffering.value = false
    }

    /** 切换播放/暂停。 */
    fun togglePlayPause() {
        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
    }

    /** 停止并清空当前媒体（切换电台来源时停掉旧来源的音频）。 */
    fun stop() {
        cancelRetry()
        currentUrl = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _isBuffering.value = false
    }

    fun release() {
        cancelRetry()
        exoPlayer.release()
    }

    companion object {
        /** 错误后自动重试的总时长窗口:1 分钟。 */
        private const val RETRY_WINDOW_MS = 60_000L
        /** 每次重试的间隔。 */
        private const val RETRY_DELAY_MS = 3_000L
    }
}
