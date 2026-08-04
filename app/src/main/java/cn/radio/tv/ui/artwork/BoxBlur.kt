package cn.radio.tv.ui.artwork

/**
 * 盒式模糊（多趟叠加逼近高斯）。
 *
 * 为什么自己写而不用平台能力：
 * - `RenderScript` 自 API 31 起废弃，且在部分设备上已是 CPU 兜底实现，行为不可预期；
 * - `RenderEffect`（API 31+）作用于渲染层，拿不到可缓存的位图，而且此前正是它在全屏
 *   原图上跑，把 GL 显存从 17MB 顶到 230MB。
 *
 * 这里只在 [ArtworkRepository.BLUR_SIZE] 级别（约 192px）的小图上运行，
 * 约 3.7 万像素 × 3 趟，耗时以毫秒计，且结果可缓存复用。
 *
 * 就地修改 [pixels]（ARGB_8888，长度须为 [w] * [h]）。
 */
internal fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3) {
    require(pixels.size == w * h) { "pixels 长度 ${pixels.size} 与 ${w}x$h 不符" }
    if (radius < 1 || w < 1 || h < 1) return
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        blurPass(pixels, scratch, w, h, radius)  // 横向：pixels -> scratch
        blurPass(scratch, pixels, h, w, radius)  // 纵向：scratch 转置回 pixels
    }
}

/**
 * 一趟一维滑动窗口均值，输出转置写回（宽高互换），
 * 于是连调两次即完成横+纵，无需单独的转置步骤。
 */
private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
    val window = radius * 2 + 1
    for (y in 0 until h) {
        val rowStart = y * w
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        // 预热窗口：左侧越界部分用首像素填充（边缘钳制，避免边框发暗）
        for (i in -radius..radius) {
            val p = src[rowStart + i.coerceIn(0, w - 1)]
            a += p ushr 24 and 0xFF
            r += p ushr 16 and 0xFF
            g += p ushr 8 and 0xFF
            b += p and 0xFF
        }
        for (x in 0 until w) {
            dst[x * h + y] = ((a / window) shl 24) or
                    ((r / window) shl 16) or
                    ((g / window) shl 8) or
                    (b / window)
            val outP = src[rowStart + (x - radius).coerceIn(0, w - 1)]
            val inP = src[rowStart + (x + radius + 1).coerceIn(0, w - 1)]
            a += (inP ushr 24 and 0xFF) - (outP ushr 24 and 0xFF)
            r += (inP ushr 16 and 0xFF) - (outP ushr 16 and 0xFF)
            g += (inP ushr 8 and 0xFF) - (outP ushr 8 and 0xFF)
            b += (inP and 0xFF) - (outP and 0xFF)
        }
    }
}
