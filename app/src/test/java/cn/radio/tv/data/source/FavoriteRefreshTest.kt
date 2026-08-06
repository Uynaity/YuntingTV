package cn.radio.tv.data.source

import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 收藏刷新的降级口径。
 *
 * 这条路径原先是「按地区拉全量列表再按 contentId 匹配」——为几个副标题下载整个目录，
 * 且发生在打开收藏页、回前台这些用户看得见的时刻。换成批量按 id 查之后，本组用例钉住
 * 三件事：只问自己那几个 id、取不到就保留旧快照、取消不被当成「刷新失败」。
 */
private class RecordingSource(
    override val type: RadioSourceType = RadioSourceType.YUNTING,
    private val byIds: (Long, List<String>) -> List<Channel> = { _, _ -> emptyList() },
) : BaseRadioSource() {

    /**
     * 每次 fetchChannelsByIds 的入参。
     *
     * 必须是并发安全容器：各地区是并行刷新的（`async` + 信号量），
     * 用普通 ArrayList 记录会丢更新 —— 断言随之偶发失败，且看着像生产代码漏发了请求。
     */
    val calls: MutableList<Pair<Long, List<String>>> = CopyOnWriteArrayList()

    /** 全量列表若还有人调，这里会记上 —— 用例据此证明那条路彻底断了。 */
    val fetchChannelsCalls = AtomicInteger()

    override suspend fun fetchChannelsByIds(
        provinceCode: Long,
        contentIds: List<String>,
    ): List<Channel> {
        calls += provinceCode to contentIds
        return byIds(provinceCode, contentIds)
    }

    override suspend fun fetchChannels(
        categoryId: String,
        provinceCode: Long,
        offset: Int,
        limit: Int,
    ): List<Channel> {
        fetchChannelsCalls.incrementAndGet()
        return emptyList()
    }

    override suspend fun fetchProvinces(): List<Province> = emptyList()
    override suspend fun fetchCategories(): List<Category> = emptyList()
    override suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program> =
        emptyList()
}

private fun fav(id: String, province: Long, subtitle: String = "旧节目") = FavoriteChannel(
    channel = Channel(contentId = id, title = "台$id", subtitle = subtitle),
    provinceCode = province,
    source = RadioSourceType.YUNTING,
)

class FavoriteRefreshTest {

    @Test
    fun `按地区分组，每组只问自己那几个 id`() = runTest {
        val src = RecordingSource()
        val favorites = listOf(fav("1", 110), fav("2", 110), fav("3", 310))

        src.refreshFavoritePrograms(favorites)

        assertEquals("两个地区应各发一次", 2, src.calls.size)
        val byProvince = src.calls.toMap()
        assertEquals(listOf("1", "2"), byProvince[110L])
        assertEquals(listOf("3"), byProvince[310L])
        assertEquals("不得再回到按地区拉全量列表那条路", 0, src.fetchChannelsCalls.get())
    }

    @Test
    fun `取到新快照就覆盖，顺序不变`() = runTest {
        val src = RecordingSource(byIds = { _, ids ->
            ids.map { Channel(contentId = it, title = "台$it", subtitle = "新节目$it") }
        })
        val favorites = listOf(fav("1", 110), fav("2", 310), fav("3", 110))

        val out = src.refreshFavoritePrograms(favorites)

        assertEquals(listOf("1", "2", "3"), out.map { it.channel.contentId })
        assertEquals(listOf("新节目1", "新节目2", "新节目3"), out.map { it.channel.subtitle })
    }

    @Test
    fun `电台下架时保留旧快照，不从列表里消失`() = runTest {
        // 只回 id=1，id=2 当作已下架。
        val src = RecordingSource(byIds = { _, ids ->
            ids.filter { it == "1" }.map { Channel(contentId = it, subtitle = "新节目") }
        })
        val favorites = listOf(fav("1", 110), fav("2", 110))

        val out = src.refreshFavoritePrograms(favorites)

        assertEquals(2, out.size)
        assertEquals("新节目", out[0].channel.subtitle)
        assertEquals("查不到的必须留着旧的", "旧节目", out[1].channel.subtitle)
    }

    @Test
    fun `某地区失败只影响该组，其他地区照常刷新`() = runTest {
        val src = RecordingSource(byIds = { province, ids ->
            if (province == 110L) throw IOException("network down")
            ids.map { Channel(contentId = it, subtitle = "新节目") }
        })
        val favorites = listOf(fav("1", 110), fav("2", 310))

        val out = src.refreshFavoritePrograms(favorites)

        assertEquals("失败地区保留旧快照", "旧节目", out[0].channel.subtitle)
        assertEquals("其他地区不受牵连", "新节目", out[1].channel.subtitle)
    }

    @Test
    fun `接口缺失时默认实现返回空，收藏原样保留`() = runTest {
        // BaseRadioSource 的默认 fetchChannelsByIds 返回空 —— 等同网关未部署该端点。
        val src = object : BaseRadioSource() {
            override val type = RadioSourceType.YUNTING
            override suspend fun fetchProvinces(): List<Province> = emptyList()
            override suspend fun fetchCategories(): List<Category> = emptyList()
            override suspend fun fetchChannels(
                categoryId: String,
                provinceCode: Long,
                offset: Int,
                limit: Int,
            ): List<Channel> = error("能力缺失时不该退回全量下载")

            override suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long) =
                emptyList<Program>()
        }
        val favorites = listOf(fav("1", 110), fav("2", 310))

        val out = src.refreshFavoritePrograms(favorites)

        assertEquals(favorites, out)
    }

    @Test
    fun `取消原样上抛，不被降级成刷新失败`() = runTest {
        val src = RecordingSource(byIds = { _, _ -> throw CancellationException("cancelled") })

        val thrown = runCatching { src.refreshFavoritePrograms(listOf(fav("1", 110))) }
            .exceptionOrNull()

        assertTrue("取消必须保持取消语义，实际 $thrown", thrown is CancellationException)
    }

    @Test
    fun `空收藏直接返回，不发请求`() = runTest {
        val src = RecordingSource()

        assertEquals(emptyList<FavoriteChannel>(), src.refreshFavoritePrograms(emptyList()))
        assertEquals(0, src.calls.size)
    }
}
