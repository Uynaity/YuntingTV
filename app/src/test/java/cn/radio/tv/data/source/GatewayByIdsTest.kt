package cn.radio.tv.data.source

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.remote.GatewayApi
import cn.radio.tv.data.remote.ReplayDto
import cn.radio.tv.data.remote.StreamDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** 只实现 by-ids 的假网关；记录入参与调用次数。 */
private class FakeGatewayApi(
    private val onByIds: (String, Long, String) -> ApiResponse<List<Channel>>,
) : GatewayApi {

    var calls = 0
        private set
    var lastContentIds: String? = null
        private set

    override suspend fun getChannelsByIds(
        source: String,
        provinceCode: Long,
        contentIds: String,
    ): ApiResponse<List<Channel>> {
        calls++
        lastContentIds = contentIds
        return onByIds(source, provinceCode, contentIds)
    }

    override suspend fun getProvinces(source: String) = ApiResponse<List<Province>>(0, null, null)
    override suspend fun getCategories(source: String) = ApiResponse<List<Category>>(0, null, null)
    override suspend fun getChannels(
        source: String,
        provinceCode: Long,
        categoryId: String,
        offset: Int,
        limit: Int,
    ) = ApiResponse<List<Channel>>(0, null, null)

    override suspend fun searchChannels(
        source: String,
        provinceCode: Long,
        categoryId: String,
        q: String,
        offset: Int,
        limit: Int,
    ) = ApiResponse<List<Channel>>(0, null, null)

    override suspend fun getPrograms(source: String, contentId: String, date: String) =
        ApiResponse<List<Program>>(0, null, null)

    override suspend fun getReplay(source: String, contentId: String, programId: String) =
        ApiResponse(0, null, ReplayDto())

    override suspend fun getStream(source: String, contentId: String) =
        ApiResponse(0, null, StreamDto())
}

private fun httpError(code: Int) = HttpException(
    Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())),
)

/**
 * `/v1/channels/by-ids` 的客户端契约与能力降级。
 *
 * 端点是后加的，线上可能还是旧网关（返回 404）。这时客户端必须安静降级并保留旧快照，
 * 且**不能每次刷新都再撞一发** —— 那个 404 是部署事实，不是偶发失败。
 */
class GatewayByIdsTest {

    // byIdsSupported 是进程级开关，用例之间会互相污染，故每个用例前后各复位一次。
    @Before
    @After
    fun resetCapability() {
        val f = GatewaySource::class.java.getDeclaredField("byIdsSupported")
        f.isAccessible = true
        f.setBoolean(null, true)
    }

    @Test
    fun `id 列表按逗号拼接下发`() = runTest {
        val api = FakeGatewayApi { _, _, _ ->
            ApiResponse(0, null, listOf(Channel(contentId = "a", subtitle = "新节目")))
        }
        val src = GatewaySource(RadioSourceType.QINGTING, api)

        val out = src.fetchChannelsByIds(407, listOf("a", "b", "c"))

        assertEquals("a,b,c", api.lastContentIds)
        assertEquals(listOf("新节目"), out.map { it.subtitle })
    }

    @Test
    fun `空 id 列表直接短路，不发请求`() = runTest {
        val api = FakeGatewayApi { _, _, _ -> ApiResponse(0, null, emptyList()) }
        val src = GatewaySource(RadioSourceType.YUNTING, api)

        assertEquals(emptyList<Channel>(), src.fetchChannelsByIds(0, emptyList()))
        assertEquals(0, api.calls)
    }

    @Test
    fun `端点未部署时返回空，且不再重复请求`() = runTest {
        val api = FakeGatewayApi { _, _, _ -> throw httpError(404) }
        val src = GatewaySource(RadioSourceType.YUNTING, api)

        assertEquals(emptyList<Channel>(), src.fetchChannelsByIds(0, listOf("a")))
        assertEquals(emptyList<Channel>(), src.fetchChannelsByIds(0, listOf("b")))
        assertEquals(emptyList<Channel>(), src.fetchChannelsByIds(0, listOf("c")))

        assertEquals("404 之后不该再撞第二发", 1, api.calls)
    }

    @Test
    fun `能力开关跨来源共享，另一个来源不必再撞一次 404`() = runTest {
        val api = FakeGatewayApi { _, _, _ -> throw httpError(404) }

        GatewaySource(RadioSourceType.YUNTING, api).fetchChannelsByIds(0, listOf("a"))
        GatewaySource(RadioSourceType.QINGTING, api).fetchChannelsByIds(407, listOf("b"))
        GatewaySource(RadioSourceType.TUNEIN, api).fetchChannelsByIds(100436, listOf("c"))

        assertEquals("端点有无是整套部署的属性，三个来源共用一个判断", 1, api.calls)
    }

    @Test
    fun `其他 HTTP 错误照常上抛，不误判成能力缺失`() = runTest {
        val api = FakeGatewayApi { _, _, _ -> throw httpError(503) }
        val src = GatewaySource(RadioSourceType.YUNTING, api)

        val thrown = runCatching { src.fetchChannelsByIds(0, listOf("a")) }.exceptionOrNull()
        assertTrue("503 是偶发故障，不该关掉能力开关，实际 $thrown", thrown is HttpException)

        // 开关没被关掉，下一次仍会正常尝试。
        runCatching { src.fetchChannelsByIds(0, listOf("b")) }
        assertEquals(2, api.calls)
    }

    @Test
    fun `网络异常照常上抛，交由调用方保留旧快照`() = runTest {
        val api = FakeGatewayApi { _, _, _ -> throw IOException("network down") }
        val src = GatewaySource(RadioSourceType.YUNTING, api)

        val thrown = runCatching { src.fetchChannelsByIds(0, listOf("a")) }.exceptionOrNull()
        assertTrue("实际 $thrown", thrown is IOException)
    }

    @Test
    fun `业务码非 0 视为失败`() = runTest {
        val api = FakeGatewayApi { _, _, _ -> ApiResponse(-1, "boom", null) }
        val src = GatewaySource(RadioSourceType.YUNTING, api)

        val thrown = runCatching { src.fetchChannelsByIds(0, listOf("a")) }.exceptionOrNull()
        assertTrue("实际 $thrown", thrown is IllegalStateException)
    }
}
