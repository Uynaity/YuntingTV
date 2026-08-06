package cn.radio.tv.data.program

import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.source.RadioSource
import cn.radio.tv.data.source.RadioSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 节目单的唯一取数入口：按 (来源, 电台, 日期) 键控，同键并发只发一次请求，结果短期复用。
 *
 * 节目单面板与直播进度条的当前节目窗口要的是**同一天、同一个台**的同一份数据；
 * 各自直连数据源的话，用户在正播的台上打开节目单要多一次网络往返，遥控快切日期
 * 再切回来还会把刚拿到的那份重新拉一遍。弱性能 TV 上这些都是白花的 JSON 解析与 GC，
 * 故收进这一处统一处理，三条语义写在一处才好一次性证明它们成立：
 * - **single-flight**：同键在途请求只有一个，后来者挂在同一个 [Deferred] 上。
 * - **TTL 缓存**：命中且未过期直接返回，不发请求；容量有界（[maxEntries]，LRU 淘汰）。
 * - **取消传播**：等待者按引用计数，最后一个走掉就取消在途请求（沿协程边界传到
 *   OkHttp 的 `Call.cancel()`）。单个等待者被取消不会连累其他等待者。
 *
 * @param scope 承载在途请求的作用域。**必须**是 supervisor 作用域：单次取数失败经
 *   `await()` 抛给等待者，不该把作用域连根带倒。默认自持一个，由 [close] 释放。
 * @param now 取墙钟。测试用虚拟时钟推进 TTL，不靠 `Thread.sleep`。
 */
class ProgramRepository(
    private val sourceOf: (RadioSourceType) -> RadioSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = TTL_MILLIS,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /** 缓存键。日期用北京时间当天 0 点的 epoch ms（见 [cn.radio.tv.data.source.BEIJING_TIME_ZONE]）。 */
    data class Key(
        val source: RadioSourceType,
        val contentId: String,
        val dayStartMillis: Long,
    )

    private class Entry(val at: Long, val programs: List<Program>)

    /** 一份在途请求及其等待者计数。计数归零即无人需要，取消之。 */
    private class Shared(val job: Deferred<List<Program>>) {
        var waiters = 0
    }

    /** 保护 [cache] 与 [inFlight] 的复合读改写；两张表必须同进同出，故共用一把锁。 */
    private val mutex = Mutex()

    // accessOrder=true：get 也算一次访问，淘汰的才是真正最久没用的那份。
    private val cache = object : LinkedHashMap<Key, Entry>(CACHE_INITIAL_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean =
            size > maxEntries
    }

    private val inFlight = HashMap<Key, Shared>()

    /**
     * 取某电台某天的节目单。失败原样抛给调用方（由它决定展示错误还是静默降级）。
     */
    suspend fun playbill(
        source: RadioSourceType,
        channel: Channel,
        dayStartMillis: Long,
    ): List<Program> = load(Key(source, channel.contentId, dayStartMillis), channel) { true }

    /**
     * 当前直播节目的时间窗口 [start, end]（epoch ms），供直播进度条使用；
     * 无节目单或没有一档覆盖此刻则返回 null（上层回退当天 24h）。
     *
     * 缓存在这里多一条准入判据：**缓存只有能回答问题时才算命中**。一档节目刚播完时，
     * 手上那份当天节目单可能还没有下一档（后端尚未更新），若单看 TTL 就直接复用，
     * 副标题和进度条会一直停在上一节目。故未覆盖此刻时无视 TTL 重新取，重试频率由
     * 调用方节流（`RadioViewModel.LIVE_RESOLVE_MIN_INTERVAL_MS`），此处不再叠一层。
     *
     * 与 [playbill] 不同，取数失败在此静默返回 null：进度条回退 24h 即可，不值得打扰用户。
     */
    suspend fun currentWindow(
        source: RadioSourceType,
        channel: Channel,
        todayStartMillis: Long,
    ): LongRange? {
        val key = Key(source, channel.contentId, todayStartMillis)
        val programs = try {
            load(key, channel) { cached -> cached.covering(now()) != null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return programs.covering(now())?.let { it.startTime..it.endTime }
    }

    /** 释放自持作用域（[scope] 为默认值时）。在 `ViewModel.onCleared` 调用。 */
    fun close() {
        scope.cancel()
    }

    /**
     * @param acceptCached 缓存**内容**是否可用；与 TTL 是与的关系，两者都过才算命中。
     */
    private suspend fun load(
        key: Key,
        channel: Channel,
        acceptCached: (List<Program>) -> Boolean,
    ): List<Program> {
        val shared = mutex.withLock {
            val hit = cache[key]
            if (hit != null && now() - hit.at < ttlMillis && acceptCached(hit.programs)) {
                return hit.programs
            }
            // LAZY：登记与启动都在锁内完成会把网络请求的第一段同步扛进临界区；
            // 交给下面第一个 await() 启动即可。
            inFlight.getOrPut(key) {
                Shared(scope.async(start = CoroutineStart.LAZY) { fetchAndCache(key, channel) })
            }.also { it.waiters++ }
        }
        try {
            return shared.job.await()
        } finally {
            // 走到这里可能正因本协程被取消：清账必须在 NonCancellable 下做，
            // 否则 withLock 一挂起就抛，计数永远减不回去，这一键从此再不发请求。
            withContext(NonCancellable) {
                mutex.withLock {
                    if (--shared.waiters == 0) {
                        // 只摘自己那份：失败后已有新请求登记同键时，别把它误摘了。
                        if (inFlight[key] === shared) inFlight.remove(key)
                        if (!shared.job.isCompleted) shared.job.cancel()
                    }
                }
            }
        }
    }

    private suspend fun fetchAndCache(key: Key, channel: Channel): List<Program> {
        val programs = sourceOf(key.source).fetchPlaybill(channel, key.dayStartMillis)
        // 在请求体内写缓存，而不是等 await() 返回：等待者全被取消时结果照样留下。
        // 摘 inFlight 的活儿统一由 [load] 的 finally 按引用计数做，此处不插手 ——
        // 完成之后、等待者清账之前赶来的新调用者会先撞上刚写好的缓存，不会挂到这份上。
        mutex.withLock { cache[key] = Entry(now(), programs) }
        return programs
    }

    private fun List<Program>.covering(at: Long): Program? =
        firstOrNull { at >= it.startTime && at < it.endTime }

    companion object {
        /**
         * 缓存有效期。一天的节目单本身是静态的，会变的只是「此刻在播哪一档」，
         * 而那由 [currentWindow] 的覆盖判据兜住，故这里不必取太短。
         */
        const val TTL_MILLIS = 10 * 60 * 1000L

        /** 缓存条数上限。节目单面板一次最多 9 天 × 少数几个台，32 条足够且内存有界。 */
        const val MAX_ENTRIES = 32

        private const val CACHE_INITIAL_CAPACITY = 16
    }
}
