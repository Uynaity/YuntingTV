package cn.radio.tv.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 播放器连接状态。失败是**一等状态**，不是异常 —— 这是本类存在的全部理由。 */
sealed interface ConnectionState<out C> {
    /** 尚未发起连接（按需连接：没人要播就不连）。 */
    data object Idle : ConnectionState<Nothing>

    data object Connecting : ConnectionState<Nothing>

    data class Connected<out C>(val controller: C) : ConnectionState<C>

    /** 连接失败或超时。可重连。 */
    data class Failed(val cause: Throwable) : ConnectionState<Nothing>
}

/**
 * 播放器连接的状态机。
 *
 * 连接失败是一等状态，不是异常：裸调 `future.get()` 若抛出且无人接管会直接杀进程，
 * 无超时的等待一旦连接失败又会**永久挂起**，把所有调用方（含首屏加载链路）一起焊死 ——
 * 低端 TV 上这两种情形都更容易触发。这里的约定：
 * - 连接失败转成 [ConnectionState.Failed]，绝不抛到主线程；
 * - [awaitController] 带超时且返回可空，调用方**必须**处理 null，不存在永久等待；
 * - [connect] 幂等，失败态可重连；
 * - 泛型化是为了可测：单测传一个会抛异常的 connector 即可覆盖 P0 路径，无需 Android。
 *
 * 结构化并发：外部取消（scope 被取消）照常向上传播 [CancellationException]；
 * 只有超时这一种「预期内的失败」被转成 [ConnectionState.Failed]。
 */
class PlaybackConnection<C : Any>(
    private val scope: CoroutineScope,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val connector: suspend () -> C,
) {

    private val _state = MutableStateFlow<ConnectionState<C>>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState<C>> = _state.asStateFlow()

    private var job: Job? = null
    private var released = false

    /** 已连上的控制器；未连上返回 null。用于不该触发连接的只读场景（如缓冲态计算）。 */
    val connected: C? get() = (_state.value as? ConnectionState.Connected)?.controller

    /**
     * 发起连接。幂等：连接中或已连上时是 no-op；失败态允许重连。
     * 按需调用 —— 没有播放意图时不要调，冷启动不该为「可能不播」的场景付出绑定服务的代价。
     */
    fun connect() {
        if (released) return
        when (_state.value) {
            is ConnectionState.Connecting, is ConnectionState.Connected -> return
            else -> Unit
        }
        _state.value = ConnectionState.Connecting
        job = scope.launch {
            try {
                val c = withTimeout(timeout) { connector() }
                if (released) return@launch
                _state.value = ConnectionState.Connected(c)
            } catch (e: TimeoutCancellationException) {
                // 超时是预期内的失败，转状态；它虽是 CancellationException 的子类，但不代表外部取消。
                _state.value = ConnectionState.Failed(e)
            } catch (e: CancellationException) {
                throw e  // 外部取消：保持取消语义向上传播
            } catch (e: Throwable) {
                _state.value = ConnectionState.Failed(e)
            }
        }
    }

    /**
     * 取控制器：未连接则发起连接并等待结果。
     *
     * **失败或超时返回 null**，调用方必须处理：不存在永久等待，否则 seekTo、睡眠定时器、
     * 节目刷新乃至整个首屏加载会被一并卡死。
     */
    suspend fun awaitController(): C? {
        if (released) return null
        connect()
        val settled = state.first {
            it is ConnectionState.Connected || it is ConnectionState.Failed
        }
        return (settled as? ConnectionState.Connected)?.controller
    }

    /** 释放：停止连接尝试并回到不可用状态。释放后 [awaitController] 恒返回 null。 */
    fun release() {
        released = true
        job?.cancel()
        job = null
        _state.value = ConnectionState.Idle
    }

    companion object {
        /**
         * 连接超时。低端 TV 绑定服务 + 初始化 ExoPlayer 可能明显慢于手机，
         * 给足余量；但必须有上限 —— 无上限就是旧版的永久挂起。
         */
        val DEFAULT_TIMEOUT: Duration = 10.seconds
    }
}
