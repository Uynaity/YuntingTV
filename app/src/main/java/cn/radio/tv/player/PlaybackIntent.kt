package cn.radio.tv.player

import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.source.RadioSourceType

/**
 * 一次播放意图。
 *
 * 关键在于**来源在创建意图时就被捕获**，而不是在协程内部现读 UI 状态。
 * 旧代码 `sources.getValue(_uiState.value.playingSource).resolveStream(channel)` 是在
 * 协程里才读 `playingSource`：先点 A 台、再切来源点 B 台，A 的在途解析会拿 B 的来源
 * 去解析 A 的频道，跨源串味。
 *
 * 所有意图经同一个串行管道执行（`collectLatest`），新意图到达即取消旧执行：
 * - 天然的 latest-wins：慢响应不会覆盖后选中的电台；
 * - 回放与直播不再互相覆盖状态（旧代码 playReplay 在两次 await 之后才写节目名，
 *   而 playLive 是同步清除，慢回放落地晚于切回直播就会写出"直播中显示回放节目名"）；
 * - 取消沿协程边界向下传播到 Retrofit，不留孤儿请求。
 */
sealed interface PlaybackIntent {

    val source: RadioSourceType
    val channel: Channel

    /** 播放某台直播流。 */
    data class Live(
        override val source: RadioSourceType,
        override val channel: Channel,
    ) : PlaybackIntent

    /** 播放某档节目的回放。 */
    data class Replay(
        override val source: RadioSourceType,
        override val channel: Channel,
        val program: Program,
    ) : PlaybackIntent
}
