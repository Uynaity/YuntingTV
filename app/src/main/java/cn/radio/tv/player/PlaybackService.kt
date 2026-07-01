package cn.radio.tv.player

import android.content.Intent
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

    /**
     * 用户在退出弹窗选「确定」→ Activity 调 finishAndRemoveTask() 移除任务，触发此回调。
     * 停掉所有播放器并结束前台服务，让媒体通知消失、进程得以回收 —— 真正彻底退出，
     * 而非默认「后台继续播放」。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            player.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
