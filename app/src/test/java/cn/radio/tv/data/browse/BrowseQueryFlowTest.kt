package cn.radio.tv.data.browse

import cn.radio.tv.data.source.RadioSourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 查询流的整形规则：打字防抖、切筛选立即、同查询不重发。
 *
 * 这三条此前散在 `RadioViewModel` 的搜索管道与 `loadChannels()` 两处，各有各的守卫
 * （代次计数器 / 两个 job 引用 / `copy(typed=false)` 后再去重）。收敛成一个函数后，
 * 规则本身可以在纯 JVM 上按虚拟时间验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseQueryFlowTest {

    private val debounceMs = 1000L

    private fun query(
        province: Long = 0L,
        category: String = "0",
        q: String = "",
        source: RadioSourceType = RadioSourceType.YUNTING,
    ) = BrowseQuery(source = source, provinceCode = province, categoryId = category, query = q)

    @Test
    fun `尚未确定首个查询时不发请求`() = runTest {
        val out = flow<QueryRequest?> { emit(null) }.toBrowseQueries(debounceMs).toList()

        assertEquals(emptyList<BrowseQuery>(), out)
    }

    @Test
    fun `连续打字只发最后一次`() = runTest {
        val out = flow {
            emit(QueryRequest(query(q = "北"), typed = true))
            delay(100)
            emit(QueryRequest(query(q = "北京"), typed = true))
            delay(100)
            emit(QueryRequest(query(q = "北京广"), typed = true))
            delay(2000)
        }.toBrowseQueries(debounceMs).toList()

        assertEquals(listOf(query(q = "北京广")), out)
    }

    @Test
    fun `切筛选不等防抖`() = runTest {
        val started = currentTimeMs()
        val out = flow {
            emit(QueryRequest(query(province = 11), typed = false))
        }.toBrowseQueries(debounceMs).toList()

        assertEquals(listOf(query(province = 11)), out)
        // 虚拟时间没有推进 1 秒，说明 typed=false 没有走防抖分支。
        assertEquals(0L, currentTimeMs() - started)
    }

    @Test
    fun `打字后立刻切筛选，只发筛选那次`() = runTest {
        val out = flow {
            emit(QueryRequest(query(q = "北"), typed = true))
            delay(100)  // 还在防抖窗口内
            emit(QueryRequest(query(province = 11, q = "北"), typed = false))
            delay(2000)
        }.toBrowseQueries(debounceMs).toList()

        // 打字那次被防抖丢弃；新范围立即生效，且不因为「查询词没变」被去重吞掉。
        assertEquals(listOf(query(province = 11, q = "北")), out)
    }

    @Test
    fun `同一个查询重复提交只发一次`() = runTest {
        val out = flow {
            emit(QueryRequest(query(province = 11), typed = false))
            delay(2000)
            // 打开搜索、退出收藏视图等动作都会重新提交一次当前查询。
            emit(QueryRequest(query(province = 11), typed = false))
            delay(2000)
        }.toBrowseQueries(debounceMs).toList()

        assertEquals(listOf(query(province = 11)), out)
    }

    @Test
    fun `退出搜索回到筛选查询会重新发一次`() = runTest {
        val out = flow {
            emit(QueryRequest(query(province = 11), typed = false))
            delay(2000)
            emit(QueryRequest(query(province = 11, q = "新闻"), typed = true))
            delay(2000)
            emit(QueryRequest(query(province = 11), typed = false))
            delay(2000)
        }.toBrowseQueries(debounceMs).toList()

        // 浏览与搜索合成同一条分页流的代价：退出搜索重拉一次首页，
        // 换掉的是两套并行分页状态。这里把这个代价钉成断言，改动时不会悄悄变。
        assertEquals(
            listOf(query(province = 11), query(province = 11, q = "新闻"), query(province = 11)),
            out,
        )
    }
}

/** runTest 的虚拟时钟读数。 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.test.TestScope.currentTimeMs(): Long = testScheduler.currentTime
