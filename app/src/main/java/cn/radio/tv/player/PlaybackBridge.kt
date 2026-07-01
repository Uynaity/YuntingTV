package cn.radio.tv.player

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 进程内共享的播放自定义状态桥。
 *
 * 断流恢复倒计时 [retrySeconds] 不属于标准 Player 接口，MediaController 无法透出，
 * 而播放器现由 [PlaybackService] 独占持有。App 为单进程，故 service 侧的 [RadioPlayer]
 * 直接写、ViewModel 直接读同一 StateFlow 即可，无需 SessionExtras/自定义 SessionCommand 的 IPC 仪式。
 * ponytail: 跨进程才需要 session extras；单进程共享单例即正确，YAGNI。
 */
object PlaybackBridge {
    /** 断流恢复中剩余倒计时秒数；0 表示非恢复态。 */
    val retrySeconds = MutableStateFlow(0)
}
