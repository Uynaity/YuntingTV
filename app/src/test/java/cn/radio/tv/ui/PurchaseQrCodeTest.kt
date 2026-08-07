package cn.radio.tv.ui

import cn.radio.tv.ui.components.PURCHASE_URL
import cn.radio.tv.ui.components.encodeQrPixels
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码回环验证：把 App 真正会画到屏幕上的那批像素，交给 zxing 的解码器读回来。
 *
 * 「图渲染出来了」和「能扫出来」是两回事 —— 静区不足、黑白反相、尺寸太小都会让二维码看着
 * 正常却扫不动，而这类错误肉眼完全看不出来。人工用手机扫一次只能证明「当时那一版是好的」，
 * 之后任何人动了 MARGIN、配色或尺寸都不会有人再扫一遍；放进单元测试才能一直守住。
 */
class PurchaseQrCodeTest {

    /** 220dp 在 2.0 密度（1080p TV）下的实际像素数，与 `PurchaseDialog` 的取值一致。 */
    private val tvSizePx = 440

    private fun decode(pixels: IntArray, side: Int): String? {
        val source = IntArrayLuminanceSource(pixels, side, pixels.size / side)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        // 不给 TRY_HARDER：真实扫码器读的是清晰正对的屏幕，用默认档更接近实际条件，
        // 也更容易暴露「勉强能解」的边界情况。
        return runCatching {
            MultiFormatReader().decode(bitmap, emptyMap<DecodeHintType, Any>()).text
        }.getOrNull()
    }

    @Test
    fun `购买链接的二维码能被解码回原链接`() {
        val (pixels, side) = requireNotNull(encodeQrPixels(PURCHASE_URL, tvSizePx)) {
            "编码失败，购买链接根本没生成出二维码"
        }
        assertEquals("解码结果与购买链接不一致", PURCHASE_URL, decode(pixels, side))
    }

    @Test
    fun `手机与电视两种密度下都能解码`() {
        // 1.0（低密度盒子）到 3.5（高密度手机）之间，220dp 覆盖的像素范围。
        for (sizePx in listOf(220, 330, 440, 660, 770)) {
            val (pixels, side) = requireNotNull(encodeQrPixels(PURCHASE_URL, sizePx)) {
                "sizePx=$sizePx 编码失败"
            }
            assertEquals("sizePx=$sizePx 解码失败", PURCHASE_URL, decode(pixels, side))
        }
    }

    @Test
    fun `是黑码白底而不是反相`() {
        val (pixels, side) = requireNotNull(encodeQrPixels(PURCHASE_URL, tvSizePx))
        // 反相的二维码（白码黑底）在多数手机相机上直接扫不出来。定位图案固定在左上角，
        // 越过静区之后的第一个模块必须是黑的。
        val quiet = side / 20 + 1
        assertEquals("左上角定位图案不是黑色，二维码可能被反相了", 0xFF000000.toInt(), pixels[quiet * side + quiet])
        assertEquals("四角外缘应是白色静区", 0xFFFFFFFF.toInt(), pixels[0])
    }

    @Test
    fun `四周留有静区`() {
        val (pixels, side) = requireNotNull(encodeQrPixels(PURCHASE_URL, tvSizePx))
        val height = pixels.size / side
        // 最外一圈必须全白。静区被吃掉是扫不出来的头号原因，且肉眼几乎看不出来。
        for (x in 0 until side) {
            assertEquals("顶边第 $x 列不是白的", 0xFFFFFFFF.toInt(), pixels[x])
            assertEquals("底边第 $x 列不是白的", 0xFFFFFFFF.toInt(), pixels[(height - 1) * side + x])
        }
        for (y in 0 until height) {
            assertEquals("左边第 $y 行不是白的", 0xFFFFFFFF.toInt(), pixels[y * side])
            assertEquals("右边第 $y 行不是白的", 0xFFFFFFFF.toInt(), pixels[y * side + side - 1])
        }
    }

    @Test
    fun `内容为空时不崩溃而是返回 null`() {
        // 调用方据此退化成「只显示链接原文」，而不是抛异常把设置页带崩。
        assertNotNull("非空内容应能编码", encodeQrPixels("x", tvSizePx))
        assertTrue("空内容应返回 null", encodeQrPixels("", tvSizePx) == null)
    }
}

/** 把 ARGB 像素数组当作灰度图喂给 zxing。core 里没有现成的实现（那在 javase 包里）。 */
private class IntArrayLuminanceSource(
    private val pixels: IntArray,
    width: Int,
    height: Int,
) : LuminanceSource(width, height) {

    private val luminances = ByteArray(pixels.size) { i ->
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        // 与 zxing javase 的 BufferedImageLuminanceSource 相同的加权。
        (((r * 306) + (g * 601) + (b * 117)) shr 10).toByte()
    }

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = if (row != null && row.size >= width) row else ByteArray(width)
        System.arraycopy(luminances, y * width, out, 0, width)
        return out
    }

    override fun getMatrix(): ByteArray = luminances
}
