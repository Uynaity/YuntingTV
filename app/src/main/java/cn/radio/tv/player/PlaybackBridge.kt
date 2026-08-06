package cn.radio.tv.player

import cn.radio.tv.player.PlaybackBridge.retrySeconds
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 进程内共享的播放自定义状态桥。
 *
 * 断流恢复倒计时 [retrySeconds] 不属于标准 Player 接口，MediaController 无法透出，
 * 而播放器现由 [PlaybackService] 独占持有。App 为单进程，故 service 侧的 [RadioPlayer]
 * 直接写、ViewModel 直接读同一 StateFlow 即可，无需 SessionExtras/自定义 SessionCommand 的 IPC 仪式。
 */
object PlaybackBridge {
    /** 断流恢复中剩余倒计时秒数；0 表示非恢复态。 */
    val retrySeconds = MutableStateFlow(0)

    /**
     * [PlaybackService] 是否存活（单进程，故一个普通标志即可靠）。
     *
     * 用途：连接改为按需之后，ViewModel 不再无条件绑定服务。但若用户关了自动续播、
     * 退到后台又回来，服务其实还在放 —— 此时新 ViewModel 必须主动连上去接管，
     * 否则界面显示「未播放」而喇叭在响。
     */
    val serviceRunning = MutableStateFlow(false)

    /**
     * 清除服务侧遗留状态。
     *
     * 仅用于「服务已不在，[retrySeconds] 却非 0」这种确定过期的情形（如服务被系统直接
     * 杀掉、没走到 onDestroy）。**不要**在 ViewModel 销毁时无条件调用：配置变更重建时
     * 服务往往还活着且确实在重试，那个倒计时是真实状态，清掉反而是错的。
     */
    fun reset() {
        retrySeconds.value = 0
    }
}
