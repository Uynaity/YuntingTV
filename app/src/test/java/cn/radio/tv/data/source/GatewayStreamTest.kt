package cn.radio.tv.data.source

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.remote.StreamDto
import cn.radio.tv.data.remote.StubGatewayApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只实现 /v1/stream 的假网关；下发内容由用例给定。 */
private class FakeStreamApi(private val dto: StreamDto?) : StubGatewayApi() {

    override suspend fun getStream(source: String, contentId: String) =
        ApiResponse(0, null, dto)
}

/**
 * `/v1/stream` 的地址选取契约，重点是「TuneIn 代理」开关。
 *
 * 默认（开关关）直连以省服务端带宽，开关开了才走透传。除了「开关关且服务端确实给了
 * 直连地址」这一条，其余情形都必须落到代理地址上 —— 这些回退分支是本文件的主要防线。
 */
class GatewayStreamTest {

    private val channel = Channel(contentId = "s355090", playUrlLow = "https://手上的旧地址/l.mp3")

    private fun source(dto: StreamDto?) = GatewaySource(FakeStreamApi(dto))

    /** 本文件只测 TuneIn：直连地址是它专有的，其余来源服务端固定回空串。 */
    private suspend fun GatewaySource.stream(useProxy: Boolean = false) =
        resolveStream(RadioSourceType.TUNEIN, channel, useProxy)

    @Test
    fun `开关关闭且服务端给了直连地址时，绕开代理（默认行为）`() = runTest {
        val src = source(StreamDto(url = "https://gw/proxy/s355090", directUrl = "https://up/l.m3u8"))

        val out = src.stream()

        assertEquals("https://up/l.m3u8", out.url)
        assertEquals("显式传 false 与默认值须一致", out.url, src.stream(useProxy = false).url)
        assertTrue("流类型仍由 streamType 决定，与走哪条地址无关", !out.isHls)
    }

    @Test
    fun `开关开启时无视直连地址，走代理`() = runTest {
        val src = source(StreamDto(url = "https://gw/proxy/s355090", directUrl = "https://up/l.m3u8"))

        assertEquals("https://gw/proxy/s355090", src.stream(useProxy = true).url)
    }

    @Test
    fun `旧网关不下发 directUrl 时，开关关着也静默回退代理`() = runTest {
        // 旧网关的响应里根本没有该字段，反序列化后即 StreamDto 的默认空串。
        val src = source(StreamDto(url = "https://gw/proxy/s355090"))

        assertEquals(
            "字段缺失不是错误，必须与开着开关的结果一致",
            src.stream(useProxy = true).url,
            src.stream(useProxy = false).url,
        )
    }

    @Test
    fun `服务端约定的空 url 仍回退 playUrlLow，不受开关影响`() = runTest {
        // 云听那条「沿用你手上的地址」约定：url 与 directUrl 皆空。
        val src = source(StreamDto())

        assertEquals(channel.playUrlLow, src.stream(useProxy = false).url)
        assertEquals(channel.playUrlLow, src.stream(useProxy = true).url)
    }

    @Test
    fun `流类型照常下发，直连不改变 HLS 判定`() = runTest {
        val src = source(
            StreamDto(url = "https://gw/proxy/s355090", directUrl = "https://up/l.m3u8", streamType = "hls"),
        )

        assertTrue(src.stream(useProxy = false).isHls)
        assertTrue(src.stream(useProxy = true).isHls)
    }

    @Test
    fun `激活状态原样透传，与选哪条地址无关`() = runTest {
        val src = source(
            StreamDto(
                url = "https://gw/proxy/s355090",
                directUrl = "https://up/l.m3u8",
                proxyActivated = true,
                proxyExpiresAt = 1_800_000_000L,
            ),
        )

        for (useProxy in listOf(false, true)) {
            val out = src.stream(useProxy = useProxy)
            assertTrue("useProxy=$useProxy 时激活状态被吞了", out.proxyActivated)
            assertEquals(1_800_000_000L, out.proxyExpiresAtSeconds)
        }
    }

    @Test
    fun `门禁关闭时服务端回激活但到期为 0，不能被当成未激活`() = runTest {
        // ENFORCE_ACTIVATION=false 的下发形状：activated=true、expiresAt=0。
        // 若把「到期为 0」读成未激活，服务端的应急回滚开关就等于失效。
        val src = source(StreamDto(url = "https://gw/proxy/s355090", proxyActivated = true))

        val out = src.stream(useProxy = true)

        assertTrue(out.proxyActivated)
        assertEquals(0L, out.proxyExpiresAtSeconds)
    }

    @Test
    fun `旧网关不下发激活字段时按未激活处理，不因缺字段炸掉`() = runTest {
        val out = source(StreamDto(url = "https://gw/proxy/s355090")).stream()

        assertTrue(!out.proxyActivated)
        assertEquals(0L, out.proxyExpiresAtSeconds)
    }

    @Test
    fun `响应 data 为空时退回 playUrlLow，开关不影响降级`() = runTest {
        val src = source(null)

        val out = src.stream(useProxy = false)

        assertEquals(channel.playUrlLow, out.url)
        assertTrue(!out.isHls)
    }
}
