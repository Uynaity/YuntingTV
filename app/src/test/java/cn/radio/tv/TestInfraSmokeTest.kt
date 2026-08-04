package cn.radio.tv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 测试地基自检：确认 unit test source set 可编译、可运行，且虚拟时间调度生效。
 * 本仓库此前没有任何测试（只有 main source set），这个文件同时充当后续测试的模板。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestInfraSmokeTest {

    @Test
    fun `虚拟时间推进不消耗真实时间`() = runTest {
        val state = MutableStateFlow(0)
        launch {
            delay(60_000)
            state.value = 1
        }
        assertEquals(0, state.value)
        advanceUntilIdle()
        assertEquals(1, state.value)
    }
}
