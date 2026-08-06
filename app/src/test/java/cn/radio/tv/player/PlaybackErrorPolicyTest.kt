package cn.radio.tv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorPolicyTest {

    @Test
    fun `网络类错误可重试`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        ).forEach { assertTrue("错误码 $it 应可重试", PlaybackErrorPolicy.isRetryable(it)) }
    }

    @Test
    fun `解析解码DRM 错误不可重试`() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        ).forEach { assertFalse("错误码 $it 不该重试", PlaybackErrorPolicy.isRetryable(it)) }
    }

    @Test
    fun `地址无效类错误不可重试`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        ).forEach { assertFalse("错误码 $it 不该重试", PlaybackErrorPolicy.isRetryable(it)) }
    }

    @Test
    fun `4xx 不重试但 408 与 429 重试`() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertFalse(PlaybackErrorPolicy.isRetryable(code, 404))
        assertFalse(PlaybackErrorPolicy.isRetryable(code, 403))
        assertFalse(PlaybackErrorPolicy.isRetryable(code, 451))
        assertTrue(PlaybackErrorPolicy.isRetryable(code, 408))
        assertTrue(PlaybackErrorPolicy.isRetryable(code, 429))
    }

    @Test
    fun `5xx 服务端错误可重试`() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertTrue(PlaybackErrorPolicy.isRetryable(code, 500))
        assertTrue(PlaybackErrorPolicy.isRetryable(code, 503))
    }

    @Test
    fun `退避按指数增长并封顶`() {
        val noJitter = 0.0
        assertEquals(2_000L, PlaybackErrorPolicy.backoffMs(0, noJitter))
        assertEquals(4_000L, PlaybackErrorPolicy.backoffMs(1, noJitter))
        assertEquals(8_000L, PlaybackErrorPolicy.backoffMs(2, noJitter))
        assertEquals(16_000L, PlaybackErrorPolicy.backoffMs(3, noJitter))
        assertEquals(30_000L, PlaybackErrorPolicy.backoffMs(4, noJitter))
        assertEquals("必须封顶", 30_000L, PlaybackErrorPolicy.backoffMs(99, noJitter))
    }

    @Test
    fun `抖动只增不减且不超过比例上限`() {
        repeat(20) { attempt ->
            val base = PlaybackErrorPolicy.backoffMs(attempt, 0.0)
            val full = PlaybackErrorPolicy.backoffMs(attempt, 1.0)
            assertTrue(full >= base)
            assertTrue(
                "抖动超出 ${PlaybackErrorPolicy.JITTER_FRACTION}",
                full <= base + (base * PlaybackErrorPolicy.JITTER_FRACTION).toLong(),
            )
        }
    }

    @Test
    fun `旧策略的病态场景：永久错误不再产生重试风暴`() {
        // 旧实现：60 秒窗口内每 3 秒重建一次 ≈ 20 次。
        // 现在 404 直接判定不可重试，一次都不重试。
        assertFalse(
            PlaybackErrorPolicy.isRetryable(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpStatus = 404,
            )
        )
    }
}
