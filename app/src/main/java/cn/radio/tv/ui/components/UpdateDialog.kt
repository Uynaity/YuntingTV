package cn.radio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cn.radio.tv.ui.theme.GoldStar

/**
 * 发现新版本弹窗。未下载时提供「取消 / 立即更新」两按钮（默认焦点落「取消」，防误触）；
 * 下载中按钮区替换为进度条 + 百分比。样式与交互复刻 [ExitConfirmDialog]。
 *
 * @param onConfirm 「立即更新」——调用方开始下载安装。
 * @param onDismiss 「取消」或返回键——关闭弹窗（进行中的下载由调用方取消）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateDialog(
    versionName: String,
    sizeBytes: Long,
    downloading: Boolean,
    progress: Float,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocusRequester = remember { FocusRequester() }

    // 未下载时把焦点送到「取消」按钮
    LaunchedEffect(downloading) {
        if (!downloading) runCatching { cancelFocusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.widthIn(min = 360.dp, max = 480.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "发现新版本 v$versionName",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "安装包大小：${formatSize(sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                )

                if (downloading) {
                    val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(50),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(GoldStar, RoundedCornerShape(50)),
                        )
                    }
                    Text(
                        text = "下载中 $pct%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            16.dp,
                            Alignment.CenterHorizontally,
                        ),
                    ) {
                        DialogButton(
                            text = "取消",
                            onClick = onDismiss,
                            focusRequester = cancelFocusRequester,
                        )
                        DialogButton(text = "立即更新", onClick = onConfirm)
                    }
                }
            }
        }
    }
}

// ponytail: 与 ExitConfirmDialog 的按钮同款，复制而非提取——两弹窗独立演进，省一次跨文件重构。
@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .focusableChrome(
                shape = RoundedCornerShape(50),
                container = if (focused) Color.White else MaterialTheme.colorScheme.surfaceVariant,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
                focusRequester = focusRequester,
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) Color.Black else Color.White,
        )
    }
}

/** 字节数格式化为 MB（一位小数），供包大小展示。 */
private fun formatSize(bytes: Long): String =
    if (bytes <= 0) "未知" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
