package cn.radio.tv.data.program

import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.source.RadioSource
import cn.radio.tv.data.source.RadioSourceType
import cn.radio.tv.data.source.ResolvedStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val DAY = 1_700_000_000_000L
private val CHANNEL = Channel(contentId = "c1", title = "测试台")

private fun program(start: Long, end: Long, title: String = "节目") =
    Program(id = "$start", title = title, startTime = start, endTime = end, canReplay = false)

/**
 * 只实现 [RadioSource.fetchPlaybill] 的假来源：记录调用次数，可挂起到测试放行为止。
 */
private class FakePlaybillSource(
    override val type: RadioSourceType = RadioSourceType.YUNTING,
    private val gate: CompletableDeferred<Unit>? = null,
    private val failWith: Throwable? = null,
    private val result: (Long) -> List<Program> = { emptyList() },
) : RadioSource {

    var calls = 0
        private set

    /** 取消是否传到了数据源。取消时 `fetchPlaybill` 会在挂起点抛 CancellationException。 */
    var cancelled = false
        private set

    override suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program> {
        calls++
        if (gate != null) {
            try {
                gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
        failWith?.let { throw it }
        return result(dayStartMillis)
    }

    override suspend fun fetchProvinces(): List<Province> = emptyList()
    override suspend fun fetchCategories(): List<Category> = emptyList()
    override suspend fun fetchChannels(
        categoryId: String,
        provinceCode: Long,
        offset: Int,
        limit: Int,
    ): List<Channel> = emptyList()

    override suspend fun searchChannels(
        q: String,
        categoryId: String,
        provinceCode: Long,
        offset: Int,
        limit: Int,
    ): List<Channel> = emptyList()

    override suspend fun refreshFavoritePrograms(favorites: List<FavoriteChannel>) = favorites
    override suspend fun resolveReplayUrl(channel: Channel, program: Program) = ""
    override suspend fun resolveStream(channel: Channel) = ResolvedStream(channel.playUrlLow, false)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramRepositoryTest {

    /** 仓库自持作用域用测试调度器接管，虚拟时间才管得住在途请求。 */
    private fun TestScope.repo(
        source: RadioSource,
        now: () -> Long = { 0L },
        ttlMillis: Long = ProgramRepository.TTL_MILLIS,
        maxEntries: Int = ProgramRepository.MAX_ENTRIES,
    ) = ProgramRepository(
        sourceOf = { source },
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        now = now,
        ttlMillis = ttlMillis,
        maxEntries = maxEntries,
    )

    @Test
    fun `同键并发只发一次请求，两个调用者拿到同一份结果`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = FakePlaybillSource(gate = gate, result = { listOf(program(1, 2)) })
        val repo = repo(src)

        val a = async { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
        val b = async { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
        runCurrent()
        gate.complete(Unit)

        assertEquals(listOf(program(1, 2)), a.await())
        assertEquals(listOf(program(1, 2)), b.await())
        assertEquals("同键并发应只有一次网络请求", 1, src.calls)
    }

    @Test
    fun `TTL 内命中缓存不再发请求，过期后重新取`() = runTest {
        var clock = 0L
        val src = FakePlaybillSource(result = { listOf(program(1, 2)) })
        val repo = repo(src, now = { clock }, ttlMillis = 1000L)

        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY)
        clock = 999L
        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY)
        assertEquals(1, src.calls)

        clock = 1000L
        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY)
        assertEquals("TTL 到点应重新取", 2, src.calls)
    }

    @Test
    fun `不同日期、不同电台、不同来源各成一键`() = runTest {
        val src = FakePlaybillSource()
        val repo = repo(src)

        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY)
        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY + 86_400_000L)
        repo.playbill(RadioSourceType.YUNTING, CHANNEL.copy(contentId = "c2"), DAY)
        repo.playbill(RadioSourceType.QINGTING, CHANNEL, DAY)

        assertEquals(4, src.calls)
    }

    @Test
    fun `最后一个等待者被取消时在途请求随之取消`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = FakePlaybillSource(gate = gate)
        val repo = repo(src)

        val job = launch { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
        runCurrent()
        job.cancelAndJoin()
        runCurrent()

        assertTrue("无人等待就该把请求取消掉，别让它跑完", src.cancelled)
    }

    @Test
    fun `一个等待者被取消不连累另一个`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = FakePlaybillSource(gate = gate, result = { listOf(program(1, 2)) })
        val repo = repo(src)

        val doomed = launch { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
        val survivor = async { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
        runCurrent()
        doomed.cancelAndJoin()
        gate.complete(Unit)

        assertEquals(listOf(program(1, 2)), survivor.await())
        assertTrue("共享请求不该被其中一个等待者的取消带走", !src.cancelled)
        assertEquals(1, src.calls)
    }

    @Test
    fun `取消清账后同键还能再次发起请求`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = FakePlaybillSource(gate = gate)
        val repo = repo(src)

        val job = launch { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
        runCurrent()
        job.cancelAndJoin()
        runCurrent()

        // 引用计数若因取消而没减回去，这一键会永远被当成「在途」，后续调用挂死在废 job 上。
        val second = launch { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
        runCurrent()

        assertEquals("清账失败会让同键再也发不出请求", 2, src.calls)
        second.cancelAndJoin()
    }

    @Test
    fun `取数失败原样抛给调用方且不写缓存`() = runTest {
        val boom = IOException("network down")
        val src = FakePlaybillSource(failWith = boom)
        val repo = repo(src)

        val thrown = runCatching { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
            .exceptionOrNull()
        // 比类型与消息而非实例：异常经 `Deferred.await()` 跨协程边界时，协程库的
        // stacktrace recovery 会复制一份带调用栈的副本，`===`/`equals` 都不成立。
        assertTrue("应原样抛出，实际 $thrown", thrown is IOException)
        assertEquals(boom.message, thrown?.message)

        runCatching { repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY) }
        assertEquals("失败不该被缓存成空节目单", 2, src.calls)
    }

    @Test
    fun `currentWindow 取覆盖此刻的那一档`() = runTest {
        val src = FakePlaybillSource(
            result = { listOf(program(0, 100), program(100, 200), program(200, 300)) },
        )
        val repo = repo(src, now = { 150L })

        assertEquals(100L..200L, repo.currentWindow(RadioSourceType.YUNTING, CHANNEL, DAY))
    }

    @Test
    fun `currentWindow 取数失败静默返回 null`() = runTest {
        val src = FakePlaybillSource(failWith = IOException("network down"))
        val repo = repo(src)

        assertNull(repo.currentWindow(RadioSourceType.YUNTING, CHANNEL, DAY))
    }

    @Test
    fun `缓存没有覆盖此刻的一档时无视 TTL 重新取`() = runTest {
        var clock = 50L
        // 第一次只有已播完的一档；后端补上下一档后，第二次才拿得到覆盖此刻的窗口。
        var served = listOf(program(0, 100))
        val src = FakePlaybillSource(result = { served })
        val repo = repo(src, now = { clock }, ttlMillis = 1000L)

        assertEquals(0L..100L, repo.currentWindow(RadioSourceType.YUNTING, CHANNEL, DAY))

        clock = 120L  // 仍在 TTL 内，但手上这份已经答不上「此刻在播什么」
        served = listOf(program(0, 100), program(100, 200))
        assertEquals(100L..200L, repo.currentWindow(RadioSourceType.YUNTING, CHANNEL, DAY))
        assertEquals("答不上就得重取，否则副标题会停在上一节目", 2, src.calls)
    }

    @Test
    fun `覆盖此刻时按 TTL 命中缓存，不重复请求`() = runTest {
        var clock = 50L
        val src = FakePlaybillSource(result = { listOf(program(0, 100), program(100, 200)) })
        val repo = repo(src, now = { clock }, ttlMillis = 1000L)

        repo.currentWindow(RadioSourceType.YUNTING, CHANNEL, DAY)
        clock = 60L
        repo.currentWindow(RadioSourceType.YUNTING, CHANNEL, DAY)

        assertEquals(1, src.calls)
    }

    @Test
    fun `缓存条数有界，最久未用的被淘汰`() = runTest {
        val src = FakePlaybillSource(result = { listOf(program(1, 2)) })
        val repo = repo(src, maxEntries = 2)

        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY)           // 键 A
        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY + 1)       // 键 B
        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY)           // 命中 A，A 变成最近使用
        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY + 2)       // 键 C，挤掉 B
        assertEquals(3, src.calls)

        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY)
        assertEquals("A 刚用过，不该被淘汰", 3, src.calls)

        repo.playbill(RadioSourceType.YUNTING, CHANNEL, DAY + 1)
        assertEquals("B 最久未用，应已被淘汰", 4, src.calls)
    }
}
