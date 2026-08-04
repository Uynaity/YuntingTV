package cn.radio.tv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * 连接状态机测试 —— 覆盖的正是 P0：
 * 旧实现在主线程 Runnable 里裸调 `controllerFuture.get()`（连接失败即杀进程），
 * 且 `controller()` 用 `filterNotNull().first()` 永久挂起（连接失败即卡死首屏）。
 *
 * 这里用泛型形参喂 String 当"控制器"，无需 Android 环境即可跑完整路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackConnectionTest {

    @Test
    fun `连接抛异常时转为 Failed 而不是抛给调用方`() = runTest {
        val conn = PlaybackConnection<String>(this) {
            throw IOException("bind failed")
        }

        val result = conn.awaitController()

        assertNull("失败必须返回 null，不能挂起也不能抛出", result)
        val state = conn.state.value
        assertTrue("状态应为 Failed，实际 $state", state is ConnectionState.Failed)
        assertTrue((state as ConnectionState.Failed).cause is IOException)
    }

    @Test
    fun `连接永不完成时超时返回 null 而不是永久挂起`() = runTest {
        val conn = PlaybackConnection<String>(this, timeout = 10.seconds) {
            awaitCancellation()  // 模拟服务绑定悬挂：低端 TV 上的真实场景
        }

        val result = conn.awaitController()

        assertNull(result)
        assertTrue(conn.state.value is ConnectionState.Failed)
    }

    @Test
    fun `连接成功时返回控制器`() = runTest {
        val conn = PlaybackConnection<String>(this) { "controller" }

        assertEquals("controller", conn.awaitController())
        assertEquals(
            ConnectionState.Connected("controller"),
            conn.state.value,
        )
    }

    @Test
    fun `connect 幂等：并发调用只发起一次连接`() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<String>()
        val conn = PlaybackConnection<String>(this) {
            calls.incrementAndGet()
            gate.await()
        }

        conn.connect()
        conn.connect()
        conn.connect()
        launch { conn.awaitController() }
        // 用 runCurrent 而非 advanceUntilIdle：后者会把虚拟时钟推过连接超时，
        // 把这个「连接进行中」的场景变成超时失败场景。
        runCurrent()

        assertEquals(1, calls.get())

        gate.complete("c")
        runCurrent()
        assertEquals(1, calls.get())
    }

    @Test
    fun `失败后允许重连`() = runTest {
        val calls = AtomicInteger()
        val conn = PlaybackConnection<String>(this) {
            if (calls.incrementAndGet() == 1) throw IOException("first attempt fails") else "ok"
        }

        assertNull(conn.awaitController())
        assertEquals("ok", conn.awaitController())
        assertEquals(2, calls.get())
    }

    @Test
    fun `未连接时 connected 为 null 且不触发连接`() = runTest {
        val calls = AtomicInteger()
        val conn = PlaybackConnection<String>(this) {
            calls.incrementAndGet()
            "c"
        }

        assertNull(conn.connected)
        advanceUntilIdle()
        assertEquals("按需连接：只读访问不得触发绑定服务", 0, calls.get())
    }

    @Test
    fun `release 后不再连接且 awaitController 返回 null`() = runTest {
        val calls = AtomicInteger()
        val conn = PlaybackConnection<String>(this) {
            calls.incrementAndGet()
            "c"
        }

        conn.release()

        assertNull(conn.awaitController())
        advanceUntilIdle()
        assertEquals(0, calls.get())
    }

    @Test
    fun `多个等待方在一次连接上汇合`() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<String>()
        val conn = PlaybackConnection<String>(this) {
            calls.incrementAndGet()
            gate.await()
        }

        val results = mutableListOf<String?>()
        repeat(3) { launch { results += conn.awaitController() } }
        runCurrent()
        gate.complete("shared")
        runCurrent()

        assertEquals(1, calls.get())
        assertEquals(3, results.size)
        results.forEach { assertSame("shared", it) }
    }
}
