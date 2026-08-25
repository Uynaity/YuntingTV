package cn.radio.tv.data.browse

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.remote.StubGatewayApi
import cn.radio.tv.data.source.GatewaySource
import cn.radio.tv.data.source.RadioSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 记录调用参数的假网关。参数顺序陷阱靠它兜住。 */
private class FakeApi(
    private val pages: (offset: Int, limit: Int) -> List<Channel> = { _, _ -> emptyList() },
    private val onFetch: ((String, Long, Int, Int) -> Unit)? = null,
    private val onSearch: ((String, Int, Int) -> Unit)? = null,
    private val failWith: Throwable? = null,
) : StubGatewayApi() {

    override suspend fun getChannels(
        source: String,
        provinceCode: Long,
        categoryId: String,
        offset: Int,
        limit: Int,
    ): ApiResponse<List<Channel>> {
        onFetch?.invoke(categoryId, provinceCode, offset, limit)
        failWith?.let { throw it }
        return ApiResponse(0, null, pages(offset, limit))
    }

    override suspend fun searchChannels(
        source: String,
        provinceCode: Long,
        categoryId: String,
        q: String,
        offset: Int,
        limit: Int,
        scope: String?,
    ): ApiResponse<List<Channel>> {
        onSearch?.invoke(q, offset, limit)
        failWith?.let { throw it }
        return ApiResponse(0, null, pages(offset, limit))
    }
}

private fun gateway(api: StubGatewayApi) = GatewaySource(api)

private fun channels(n: Int, from: Int = 0) =
    List(n) { Channel(contentId = "${from + it}", title = "台${from + it}") }

private val QUERY = BrowseQuery(
    source = RadioSourceType.YUNTING,
    provinceCode = 33,
    categoryId = "7",
)

class ChannelPagingSourceTest {

    private fun refreshParams(size: Int = GatewaySource.PAGE_SIZE) =
        PagingSource.LoadParams.Refresh<Int>(key = null, loadSize = size, placeholdersEnabled = false)

    private fun appendParams(key: Int, size: Int = GatewaySource.PAGE_SIZE) =
        PagingSource.LoadParams.Append(key = key, loadSize = size, placeholdersEnabled = false)

    @Test
    fun `满页时 nextKey 指向下一段 offset`() = runTest {
        val api = FakeApi(pages = { _, limit -> channels(limit) })
        val result = ChannelPagingSource(gateway(api), QUERY).load(refreshParams())

        val page = result as PagingSource.LoadResult.Page
        assertEquals(GatewaySource.PAGE_SIZE, page.data.size)
        assertEquals(GatewaySource.PAGE_SIZE, page.nextKey)
        assertNull("只向后翻，不应有 prevKey", page.prevKey)
    }

    @Test
    fun `不满页即末页，nextKey 为 null`() = runTest {
        val api = FakeApi(pages = { _, _ -> channels(17) })
        val result = ChannelPagingSource(gateway(api), QUERY).load(refreshParams())

        assertNull((result as PagingSource.LoadResult.Page).nextKey)
    }

    @Test
    fun `空页也是末页`() = runTest {
        val api = FakeApi(pages = { _, _ -> emptyList() })
        val result = ChannelPagingSource(gateway(api), QUERY).load(appendParams(120))

        val page = result as PagingSource.LoadResult.Page
        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun `append 时把 key 当作 offset 传给数据源`() = runTest {
        var seenOffset = -1
        var seenLimit = -1
        val api = FakeApi(
            pages = { _, limit -> channels(limit) },
            onFetch = { _, _, offset, limit -> seenOffset = offset; seenLimit = limit },
        )
        ChannelPagingSource(gateway(api), QUERY).load(appendParams(key = 180))

        assertEquals(180, seenOffset)
        assertEquals(GatewaySource.PAGE_SIZE, seenLimit)
    }

    @Test
    fun `普通浏览把地区与分类按名传参，不受顺序反转影响`() = runTest {
        var seenCategory: String? = null
        var seenProvince: Long? = null
        val api = FakeApi(
            pages = { _, _ -> emptyList() },
            onFetch = { c, p, _, _ -> seenCategory = c; seenProvince = p },
        )
        // 网关接口的形参顺序是 (provinceCode, categoryId)，而 fetchChannels 是
        // (categoryId, provinceCode) —— 中间这一跳若按位置传就会静默串位。
        ChannelPagingSource(gateway(api), QUERY).load(refreshParams())

        assertEquals("7", seenCategory)
        assertEquals(33L, seenProvince)
    }

    @Test
    fun `搜索模式走 searchChannels，只传查询词与分页`() = runTest {
        var seen: List<Any?> = emptyList()
        var fetched = false
        val api = FakeApi(
            pages = { _, _ -> emptyList() },
            onSearch = { q, o, l -> seen = listOf(q, o, l) },
            onFetch = { _, _, _, _ -> fetched = true },
        )
        // 搜索范围是整份目录，故 QUERY 里的地区/分类不该有任何去处 ——
        // 签名上就没有它们的位置，这条用例守的是「搜索分支没被接回浏览分支」。
        val query = QUERY.copy(query = "交通")
        ChannelPagingSource(gateway(api), query).load(appendParams(60))

        assertEquals(listOf("交通", 60, GatewaySource.PAGE_SIZE), seen)
        assertEquals(false, fetched)
    }

    @Test
    fun `网络失败转为 LoadResult_Error 而不是抛出`() = runTest {
        val api = FakeApi(failWith = IOException("network down"))

        val result = ChannelPagingSource(gateway(api), QUERY).load(refreshParams())

        // 比类型与消息而非实例：异常跨 `withContext` 边界时，协程库的 stacktrace recovery
        // 会复制一份带调用栈的副本，`===`/`equals` 都不成立。
        val thrown = (result as PagingSource.LoadResult.Error).throwable
        assertTrue("应原样转成 Error，实际 $thrown", thrown is IOException)
        assertEquals("network down", thrown.message)
    }

    @Test
    fun `取消异常原样上抛，不被当成加载失败`() = runTest {
        val api = FakeApi(failWith = CancellationException("cancelled"))
        val source = ChannelPagingSource(gateway(api), QUERY)

        val thrown = runCatching { source.load(refreshParams()) }.exceptionOrNull()

        assertTrue("取消必须保持取消语义，实际 $thrown", thrown is CancellationException)
    }

    @Test
    fun `刷新回到首页而不是锚定旧 offset`() {
        val source = ChannelPagingSource(gateway(FakeApi()), QUERY)
        val state = PagingState<Int, Channel>(
            pages = emptyList(),
            anchorPosition = 137,
            config = androidx.paging.PagingConfig(pageSize = GatewaySource.PAGE_SIZE),
            leadingPlaceholderCount = 0,
        )
        assertNull(source.getRefreshKey(state))
    }
}
