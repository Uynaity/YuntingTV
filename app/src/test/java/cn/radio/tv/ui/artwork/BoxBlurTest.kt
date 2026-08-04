package cn.radio.tv.ui.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯函数，无 Android 依赖，可在 JVM 上直接验证。 */
class BoxBlurTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun channels(p: Int) = listOf(
        p ushr 24 and 0xFF, p ushr 16 and 0xFF, p ushr 8 and 0xFF, p and 0xFF,
    )

    @Test
    fun `纯色图模糊后仍是同一颜色`() {
        val color = argb(255, 40, 120, 200)
        val px = IntArray(16 * 16) { color }

        boxBlur(px, 16, 16, radius = 3)

        px.forEach { assertEquals(channels(color), channels(it)) }
    }

    @Test
    fun `单点亮斑被摊开到邻域且总亮度大致守恒`() {
        val w = 33
        val h = 33
        val px = IntArray(w * h) { argb(255, 0, 0, 0) }
        px[16 * w + 16] = argb(255, 255, 255, 255)
        val before = px.sumOf { it ushr 16 and 0xFF }

        boxBlur(px, w, h, radius = 2)

        val center = px[16 * w + 16] ushr 16 and 0xFF
        val neighbour = px[16 * w + 17] ushr 16 and 0xFF
        assertTrue("中心应被摊薄，实际 $center", center < 255)
        assertTrue("邻域应被点亮，实际 $neighbour", neighbour > 0)

        // 盒式模糊是均值滤波，能量近似守恒（边缘钳制会有少量偏差）。
        val after = px.sumOf { it ushr 16 and 0xFF }
        assertTrue("总亮度偏差过大: $before -> $after", kotlin.math.abs(after - before) <= before)
    }

    @Test
    fun `左右半分图模糊后边界产生渐变而非硬边`() {
        val w = 32
        val h = 8
        val px = IntArray(w * h) { i -> if (i % w < w / 2) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }

        boxBlur(px, w, h, radius = 3)

        val row = 4 * w
        val mid = px[row + w / 2] ushr 16 and 0xFF
        assertTrue("边界处应是中间调，实际 $mid", mid in 1..254)
        // 远离边界的两端应基本保持原色
        assertTrue(px[row] ushr 16 and 0xFF < 30)
        assertTrue(px[row + w - 1] ushr 16 and 0xFF > 225)
    }

    @Test
    fun `radius 为 0 或尺寸非法时不改动也不崩`() {
        val px = intArrayOf(1, 2, 3, 4)
        boxBlur(px, 2, 2, radius = 0)
        assertEquals(listOf(1, 2, 3, 4), px.toList())
    }

    @Test
    fun `尺寸与数组长度不符时明确失败`() {
        val e = runCatching { boxBlur(IntArray(3), 2, 2, radius = 1) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
