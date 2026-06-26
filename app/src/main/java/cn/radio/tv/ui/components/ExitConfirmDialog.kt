package cn.radio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * 退出确认弹窗。提供「取消 / 确定」两个按钮，默认焦点落在「取消」以防误退。
 *
 * @param onConfirm 用户选择「确定」—— 调用方据此退出程序。
 * @param onDismiss 用户选择「取消」或按返回键关闭弹窗。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExitConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocusRequester = remember { FocusRequester() }

    // 弹窗出现后把焦点送到「取消」按钮
    LaunchedEffect(Unit) {
        runCatching { cancelFocusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.widthIn(min = 320.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "退出应用",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "确定要退出应用吗？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(cancelFocusRequester),
                    ) {
                        Text("取消")
                    }
                    Button(onClick = onConfirm) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
