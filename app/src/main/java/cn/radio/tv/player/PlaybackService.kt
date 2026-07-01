package cn.radio.tv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 承载播放的前台服务：独占持有 [RadioPlayer]，把其 ExoPlayer 交给 [MediaSession]。
 *
 * 播放时 Media3 自动进入前台并生成媒体通知（默认 DefaultMediaNotificationProvider，
 * 按 MediaItem 的 MediaMetadata 渲染电台名/节目名/封面 + 播放暂停）。播放器生命周期
 * 脱离 Activity/ViewModel，从而支持退后台/息屏/关界面持续播放。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: RadioPlayer

    override fun onCreate() {
        super.onCreate()
        player = RadioPlayer(this)
        mediaSession = MediaSession.Builder(this, player.exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            release()
            player.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
