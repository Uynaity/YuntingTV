package cn.radio.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 激活码购买页。改这里一处即可 —— 别在 UI 里散落字面量。 */
const val PURCHASE_URL = "https://pay.ldxp.cn/shop/hkuai"

/** 二维码边长。TV 上按 2.0 密度约 400px，隔着沙发也扫得到。 */
private val QR_SIZE = 220.dp

/**
 * 购买激活码弹窗：二维码 + 链接原文。
 *
 * 一个组件服务两端 —— TV 上没有浏览器，主路径是拿手机扫码；手机上则是「浏览器起不来」时的兜底，
 * 此时链接原文比二维码更有用（可长按复制），所以两者始终同时给出，不按设备裁剪。
 *
 * 二维码**本地生成、不联网取图**：会想买码的人本就可能正卡在网络受限的场景里
 * （TuneIn 代理的目标用户），依赖联网取二维码等于在最需要它的时候失效。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PurchaseDialog(onDismiss: () -> Unit) {
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { closeFocusRequester.requestFocus() }
    }

    val sizePx = with(LocalDensity.current) { QR_SIZE.roundToPx() }
    // 内容与尺寸都是固定的，只算一次。30 字符的编码是毫秒级，不必丢后台线程
    // （丢过去反而要处理加载态）。
    val qrBitmap = remember(sizePx) { encodeQr(PURCHASE_URL, sizePx) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.widthIn(min = 320.dp, max = 460.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "购买激活码",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "用手机相机扫码，或在浏览器中打开下方链接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                )

                if (qrBitmap != null) {
                    // 白底 + 四周留白是硬要求，跟 App 的深色主题相反是**故意的**：
                    // 深色底上画深色模块对比度不足，四周没有静区扫码器也定位不到，
                    // 两者任一不满足都会「图看着正常却扫不出来」。
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        colors = SurfaceDefaults.colors(containerColor = Color.White),
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "购买页二维码",
                            // 位图就是按这个尺寸生成的，关掉插值免得边缘被磨糊。
                            filterQuality = FilterQuality.None,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(QR_SIZE),
                        )
                    }
                }

                // 手机上可长按复制；TV 上无害。扫码失败时这是唯一的出路，所以哪怕
                // 二维码没生成出来也照常展示。
                SelectionContainer {
                    Text(
                        text = PURCHASE_URL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp, bottom = 20.dp),
                    )
                }

                DialogButton(
                    text = "关闭",
                    onClick = onDismiss,
                    focusRequester = closeFocusRequester,
                )
            }
        }
    }
}

// 不用 android.graphics.Color.BLACK/WHITE：那是 Android 类，把这段拖进 Android 依赖后
// 就没法在纯 JVM 测试里回环解码了 —— 而「能不能扫出来」正是这里唯一值得测的东西。
private const val QR_BLACK = 0xFF000000.toInt()
private const val QR_WHITE = 0xFFFFFFFF.toInt()

/**
 * 把 [content] 编码成黑码白底的 ARGB 像素方阵，返回「像素数组 + 边长」。失败返回 null。
 *
 * 与 [encodeQr] 拆开是为了可测：这一半没有任何 Android 依赖，单元测试能把它的输出直接喂给
 * zxing 的解码器做回环，验证扫出来的确实是 [content]（见 `PurchaseQrCodeTest`）。
 */
internal fun encodeQrPixels(content: String, sizePx: Int): Pair<IntArray, Int>? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        // MARGIN 即静区宽度（单位：模块数）。默认值随版本变化，这里显式给足；
        // 静区不足是扫不出来的头号原因，外层再用 padding 补视觉留白。
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

    val side = matrix.width
    val pixels = IntArray(side * matrix.height)
    for (y in 0 until matrix.height) {
        val offset = y * side
        for (x in 0 until side) {
            pixels[offset + x] = if (matrix[x, y]) QR_BLACK else QR_WHITE
        }
    }
    pixels to side
}.getOrNull()

/**
 * 把 [content] 编码成黑码白底的二维码位图。失败返回 null —— 调用方此时仍会展示链接原文，
 * 不至于给出一个只有「关闭」按钮的空弹窗。
 */
private fun encodeQr(content: String, sizePx: Int): Bitmap? {
    val (pixels, side) = encodeQrPixels(content, sizePx) ?: return null
    return Bitmap.createBitmap(side, pixels.size / side, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, side, 0, 0, side, pixels.size / side)
    }
}
