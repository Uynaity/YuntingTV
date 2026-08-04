package cn.radio.tv.data.browse

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.source.RadioSource
import cn.radio.tv.data.source.RadioSourceType
import cn.radio.tv.data.source.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 记录调用参数的假来源。参数顺序陷阱靠它兜住。 */
private class FakeSource(
    override val type: RadioSourceType = RadioSourceType.YUNTING,
    private val pages: (offset: Int, limit: Int) -> List<Channel> = { _, _ -> emptyList() },
    private val onFetch: ((String, Long, Int, Int) -> Unit)? = null,
    private val onSearch: ((String, String, Long, Int, Int) -> Unit)? = null,
    private val failWith: Throwable? = null,
) : RadioSource {
    override suspend fun fetchProvinces(): List<Province> = emptyList()
    override suspend fun fetchCategories(): List<Category> = emptyList()

    override suspend fun fetchChannels(
        categoryId: String,
        provinceCode: Long,
        offset: Int,
        limit: Int,
    ): List<Channel> {
        onFetch?.invoke(categoryId, provinceCode, offset, limit)
        failWith?.let { throw it }
        return pages(offset, limit)
    }

    override suspend fun searchChannels(
        q: String,
        categoryId: String,
        provinceCode: Long,
        offset: Int,
        limit: Int,
    ): List<Channel> {
        onSearch?.invoke(q, categoryId, provinceCode, offset, limit)
        failWith?.let { throw it }
        return pages(offset, limit)
    }

    override suspend fun refreshFavoritePrograms(favorites: List<FavoriteChannel>) = favorites
    override suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program> = emptyList()
    override suspend fun currentProgramWindow(channel: Channel, todayStartMillis: Long): LongRange? = null
    override suspend fun resolveReplayUrl(channel: Channel, program: Program) = ""
    override suspend fun resolveStream(channel: Channel) = ResolvedStream(channel.playUrlLow, false)
}

private fun channels(n: Int, from: Int = 0) =
    List(n) { Channel(contentId = "${from + it}", title = "台${from + it}") }

private val QUERY = BrowseQuery(
    source = RadioSourceType.YUNTING,
    provinceCode = 33,
    categoryId = "7",
)

class ChannelPagingSourceTest {

    private fun refreshParams(size: Int = RadioSource.PAGE_SIZE) =
        PagingSource.LoadParams.Refresh<Int>(key = null, loadSize = size, placeholdersEnabled = false)

    private fun appendParams(key: Int, size: Int = RadioSource.PAGE_SIZE) =
        PagingSource.LoadParams.Append(key = key, loadSize = size, placeholdersEnabled = false)

    @Test
    fun `满页时 nextKey 指向下一段 offset`() = runTest {
        val src = FakeSource(pages = { _, limit -> channels(limit) })
        val result = ChannelPagingSource(src, QUERY).load(refreshParams())

        val page = result as PagingSource.LoadResult.Page
        assertEquals(RadioSource.PAGE_SIZE, page.data.size)
        assertEquals(RadioSource.PAGE_SIZE, page.nextKey)
        assertNull("只向后翻，不应有 prevKey", page.prevKey)
    }

    @Test
    fun `不满页即末页，nextKey 为 null`() = runTest {
        val src = FakeSource(pages = { _, _ -> channels(17) })
        val result = ChannelPagingSource(src, QUERY).load(refreshParams())

        assertNull((result as PagingSource.LoadResult.Page).nextKey)
    }

    @Test
    fun `空页也是末页`() = runTest {
        val src = FakeSource(pages = { _, _ -> emptyList() })
        val result = ChannelPagingSource(src, QUERY).load(appendParams(120))

        val page = result as PagingSource.LoadResult.Page
        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun `append 时把 key 当作 offset 传给数据源`() = runTest {
        var seenOffset = -1
        var seenLimit = -1
        val src = FakeSource(
            pages = { _, limit -> channels(limit) },
            onFetch = { _, _, offset, limit -> seenOffset = offset; seenLimit = limit },
        )
        ChannelPagingSource(src, QUERY).load(appendParams(key = 180))

        assertEquals(180, seenOffset)
        assertEquals(RadioSource.PAGE_SIZE, seenLimit)
        }

    @Test
    fun `普通浏览把地区与分类按名传参，不受顺序反转影响`() = runTest {
        var seenCategory: String? = null
        var seenProvince: Long? = null
        val src = FakeSource(
            pages = { _, _ -> emptyList() },
            onFetch = { c, p, _, _ -> seenCategory = c; seenProvince = p },
        )
        ChannelPagingSource(src, QUERY).load(refreshParams())

        assertEquals("7", seenCategory)
        assertEquals(33L, seenProvince)
    }

    @Test
    fun `搜索模式走 searchChannels 且参数不错位`() = runTest {
        var seen: List<Any?> = emptyList()
        val src = FakeSource(
            pages = { _, _ -> emptyList() },
            onSearch = { q, c, p, o, l -> seen = listOf(q, c, p, o, l) },
        )
        val query = QUERY.copy(query = "交通")
        ChannelPagingSource(src, query).load(appendParams(60))

        assertEquals(listOf("交通", "7", 33L, 60, RadioSource.PAGE_SIZE), seen)
    }

    @Test
    fun `网络失败转为 LoadResult_Error 而不是抛出`() = runTest {
        val boom = IOException("network down")
        val src = FakeSource(failWith = boom)

        val result = ChannelPagingSource(src, QUERY).load(refreshParams())

        assertEquals(boom, (result as PagingSource.LoadResult.Error).throwable)
    }

    @Test
    fun `取消异常原样上抛，不被当成加载失败`() = runTest {
        val src = FakeSource(failWith = CancellationException("cancelled"))
        val source = ChannelPagingSource(src, QUERY)

        val thrown = runCatching { source.load(refreshParams()) }.exceptionOrNull()

        assertTrue("取消必须保持取消语义，实际 $thrown", thrown is CancellationException)
    }

    @Test
    fun `刷新回到首页而不是锚定旧 offset`() {
        val source = ChannelPagingSource(FakeSource(), QUERY)
        val state = PagingState<Int, Channel>(
            pages = emptyList(),
            anchorPosition = 137,
            config = androidx.paging.PagingConfig(pageSize = RadioSource.PAGE_SIZE),
            leadingPlaceholderCount = 0,
        )
        assertNull(source.getRefreshKey(state))
    }
}
