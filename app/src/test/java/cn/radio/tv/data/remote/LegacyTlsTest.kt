package cn.radio.tv.data.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Test

/** 只按预设结果作答的假信任管理器，用来观察 [CompositeTrustManager] 的委托顺序。 */
private class FakeTrustManager(private val rejects: Boolean) : X509TrustManager {

    var called = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        called = true
        if (rejects) throw CertificateException("rejected by fake")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * 内置根证书本身，以及「系统优先、内置兜底」的委托契约。
 *
 * 委托顺序是这里的重点：内置根只能是**补充**，一旦顺序反了或者系统那一路被跳过，
 * 走别的 CA 的域名（`yecao.app`）就会被误伤，而那种问题只在 Android 6 真机上才看得见。
 */
class LegacyTlsTest {

    @Test
    fun `内置的是 ISRG Root X1 与 X2 两个自签根`() {
        val roots = LegacyTls.bundledRoots
        assertEquals(2, roots.size)
        roots.forEach { assertEquals("自签根的 subject 应等于 issuer", it.subjectDN, it.issuerDN) }
        assertEquals(
            listOf(ISRG_ROOT_X1_SHA256, ISRG_ROOT_X2_SHA256),
            roots.map { it.sha256() },
        )
    }

    @Test
    fun `系统信任的链不碰内置根`() {
        val system = FakeTrustManager(rejects = false)
        val bundled = FakeTrustManager(rejects = false)

        CompositeTrustManager(system, bundled).checkServerTrusted(emptyArray(), "RSA")

        assertTrue(system.called)
        assertTrue("系统已经放行，不应再问内置根", !bundled.called)
    }

    @Test
    fun `系统拒绝后回落到内置根`() {
        val bundled = FakeTrustManager(rejects = false)

        CompositeTrustManager(FakeTrustManager(rejects = true), bundled)
            .checkServerTrusted(emptyArray(), "RSA")

        assertTrue(bundled.called)
    }

    @Test
    fun `两边都拒绝时抛出，并带上系统那条原因`() {
        val systemRejection = try {
            FakeTrustManager(rejects = true).checkServerTrusted(emptyArray(), "RSA")
            null
        } catch (expected: CertificateException) {
            expected
        }

        try {
            CompositeTrustManager(FakeTrustManager(rejects = true), FakeTrustManager(rejects = true))
                .checkServerTrusted(emptyArray(), "RSA")
            fail("两个信任管理器都拒绝时应当抛出")
        } catch (thrown: CertificateException) {
            assertEquals(1, thrown.suppressed.size)
            assertEquals(systemRejection?.message, thrown.suppressed.single().message)
        }
    }

    @Test
    fun `acceptedIssuers 是系统与内置的并集`() {
        val system = object : X509TrustManager by FakeTrustManager(rejects = false) {
            override fun getAcceptedIssuers() = arrayOf(LegacyTls.bundledRoots.first())
        }
        val composite = CompositeTrustManager(system, FakeTrustManager(rejects = false))

        assertArrayEquals(arrayOf(LegacyTls.bundledRoots.first()), composite.acceptedIssuers)
    }

    @Test
    fun `bundledRoots 是同一批实例，不会每次解析`() {
        assertSame(LegacyTls.bundledRoots, LegacyTls.bundledRoots)
    }

    private fun X509Certificate.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02X".format(it) }

    private companion object {
        // letsencrypt.org 公布的根证书指纹；内置 PEM 被改动或截断时这两条会先炸。
        const val ISRG_ROOT_X1_SHA256 =
            "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6"
        const val ISRG_ROOT_X2_SHA256 =
            "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470"
    }
}
