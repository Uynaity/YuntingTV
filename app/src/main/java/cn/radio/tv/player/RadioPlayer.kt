package cn.radio.tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 基于 Media3 ExoPlayer 的 HLS 播放器封装。
 * 暴露 isPlaying / isBuffering 状态供 UI 观察。
 */
@UnstableApi
class RadioPlayer(context: Context) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0")
        .setAllowCrossProtocolRedirects(true)

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _isBuffering.value = playbackState == Player.STATE_BUFFERING
                }
            })
        }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    /** 载入并播放一个 HLS 直播地址。 */
    fun play(playUrl: String) {
        if (playUrl.isBlank()) return
        val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
            .createMediaSource(MediaItem.fromUri(playUrl))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** 切换播放/暂停。 */
    fun togglePlayPause() {
        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
    }

    fun release() {
        exoPlayer.release()
    }
}
